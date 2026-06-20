package com.houzhengbo.interview.network;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.GuideDocument;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.utils.MarkdownAstExtractor;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Offline-first sync worker.
 *
 * Design principles:
 * - Progress reported via WorkManager Data (PROGRESS_CURRENT / PROGRESS_TOTAL / PHASE)
 * - Cancellation checked via isStopped() before each file download
 * - GitHub rate-limit (403 / X-RateLimit-Remaining=0) → mark FAILED, stop, no infinite retry
 * - ETag + commit-SHA incremental sync: skip unchanged files
 * - parserVersion change forces re-parse even if SHA unchanged
 * - In-memory dedup before any DB write (don't rely on DB UNIQUE constraint)
 * - Each document is its own transaction; single-file failure is isolated
 * - On full failure the previous library is preserved (no rollback wipes it)
 * - Failed file paths persisted to SharedPreferences for later retry
 */
public class GithubRepoSyncWorker extends Worker {

    private static final String TAG = "GithubRepoSyncWorker";

    /** WorkManager tag for cancellation */
    public static final String TAG_SYNC = "github_sync";

    /** Current parser version – bump this string to force re-parse even if SHA hasn't changed.
     *  Sourced from MarkdownAstExtractor so the two can never drift apart. */
    public static final String CURRENT_PARSER_VERSION = MarkdownAstExtractor.PARSER_VERSION;

    // Progress keys emitted via setProgressAsync()
    public static final String PROGRESS_CURRENT = "progress_current";
    public static final String PROGRESS_TOTAL   = "progress_total";
    public static final String PROGRESS_PHASE   = "progress_phase";
    public static final String PROGRESS_SUCCESS  = "progress_success";
    public static final String PROGRESS_SKIP     = "progress_skip";
    public static final String PROGRESS_FAIL     = "progress_fail";

    /** Input data key – JSON array of paths to retry (set for retry-only runs) */
    public static final String INPUT_RETRY_PATHS = "retry_paths_json";

    private static final String[] REPOS = {"Snailclimb/JavaGuide"};

    private static final String AI_INTERVIEW_DIR = "docs/ai/interview-questions/";

    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 2_000;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    private final Gson gson = new Gson();

    public GithubRepoSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppDatabase db = InterviewApplication.getInstance().getDatabase();
        Context ctx    = getApplicationContext();

        // Determine whether this is a retry-only run
        String retryPathsJson = getInputData().getString(INPUT_RETRY_PATHS);
        boolean isRetryRun = retryPathsJson != null && !retryPathsJson.equals("[]");

        SyncStatusManager.markDownloading(ctx, db);

        int totalSuccess = 0;
        int totalSkip    = 0;
        int totalFail    = 0;
        int skipUnchanged = 0;
        int skipDirectory = 0;
        int skipEmptyParse = 0;
        boolean rateLimited = false;

        List<String> failedPaths = new ArrayList<>();

        try {
            for (String repo : REPOS) {
                if (isStopped()) break;

                // ── 1. Fetch file tree ────────────────────────────────────
                List<JsonObject> candidates = fetchCandidates(ctx, repo, isRetryRun, retryPathsJson);
                if (candidates == null) {
                    // null means rate-limited or unrecoverable error
                    rateLimited = true;
                    totalFail++;
                    break;
                }

                reportProgress(0, candidates.size(), "下载文档", totalSuccess, totalSkip, totalFail);

                // ── 2. Download & parse each candidate ───────────────────
                int idx = 0;
                for (JsonObject item : candidates) {
                    if (isStopped()) break;

                    String path = item.get("path").getAsString();
                    String sha  = item.has("sha") ? item.get("sha").getAsString() : "";
                    idx++;
                    reportProgress(idx, candidates.size(), "解析写入 " + path, totalSuccess, totalSkip, totalFail);

                    // SHA + parserVersion skip check (per-document granularity).
                    // 跳过条件：文档 SHA 未变 AND 该文档下没有任何题用旧 parserVersion。
                    // 用文档级比对（而不是全局 KEY_PARSER_VER），这样 bump parserVersion 后
                    // 存量 SHA 未变的文档也会被正确重解析，而不是被错误跳过。
                    GuideDocument existingDoc = db.interviewDao().getGuideDocumentByRepoAndPath(repo, path);
                    boolean shaUnchanged = existingDoc != null && sha.equals(existingDoc.contentHash);
                    boolean parserUpToDate = existingDoc != null
                            && db.interviewDao().countQuestionsWithOldParser(path, CURRENT_PARSER_VERSION) == 0;
                    boolean hasCurrentQuestions = existingDoc != null
                            && db.interviewDao().countQuestionsWithParser(repo, path, CURRENT_PARSER_VERSION) > 0;
                    if (shaUnchanged && parserUpToDate && hasCurrentQuestions) {
                        totalSkip++;
                        skipUnchanged++;
                        continue;
                    }

                    // Download markdown with retry + exponential back-off
                    String rawUrl = "https://raw.githubusercontent.com/"
                            + repo.split("/")[0] + "/" + repo.split("/")[1]
                            + "/main/" + path;
                    String content = fetchWithRetry(rawUrl);
                    if (content == null) {
                        totalFail++;
                        failedPaths.add(path);
                        continue;
                    }

                    // Front matter is only a soft signal. Some JavaGuide pages are valid question
                    // sources even when the title says "知识点" or "常见问题" instead of "面试题".
                    boolean isAiPath = path.toLowerCase().startsWith("docs/ai/");
                    if (!isAiPath && hasFrontMatterWithoutQuestionHint(content)) {
                        Log.w(TAG, "Front matter has no explicit question hint, parsing anyway: " + path);
                    }

                    // Strip front matter
                    if (content.startsWith("---\n") || content.startsWith("---\r\n")) {
                        int end = content.indexOf("---", 3);
                        if (end != -1) content = content.substring(end + 3).trim();
                    }

                    // 正文质量门槛：AI 文档有时只是链接导航页。先让专用解析器尝试提题，
                    // 只有解析为空时才按目录页处理，避免把短但有效的问答页提前误跳过。
                    boolean needsQualityGate = repo.endsWith("AIGuide") ||
                            (repo.endsWith("JavaGuide") && path.toLowerCase().startsWith("docs/ai/"));

                    String parentCategory = resolveParentCategory(path);
                    String category = resolveSubCategory(path);
                    String docUrl   = "https://github.com/" + repo + "/blob/main/" + path;

                    // AI 面试文档使用”H2 讲解 + 高频面试题列表”，需要专用解析规则。
                    List<InterviewQuestion> parsed = isAiPath
                            ? MarkdownAstExtractor.extractAiInterviewQuestions(
                                    content, repo, path, sha, docUrl, category)
                            : MarkdownAstExtractor.extractQuestions(
                                    content, repo, path, sha, docUrl, category);

                    // 设置一级大类（H2 标题会覆盖二级 category，但 parentCategory 不变）
                    for (InterviewQuestion q : parsed) {
                        q.parentCategory = parentCategory;
                    }

                    if (parsed.isEmpty()) {
                        if (needsQualityGate && (content.length() < 500 || isLikelyDirectoryPage(content))) {
                            Log.w(TAG, "Skipping (no parsed questions, likely a directory page): " + path);
                            skipDirectory++;
                        } else {
                            Log.w(TAG, "Skipping (no questions parsed): " + path);
                            skipEmptyParse++;
                        }
                        totalSkip++;
                        continue;
                    }

                    // In-memory dedup: (repo, path, anchor) → keep last occurrence
                    List<InterviewQuestion> deduped = deduplicateInMemory(parsed);

                    // Write – each document is its own transaction
                    boolean docSuccess = saveDocumentTransaction(db, existingDoc, repo, path, sha, content, category, docUrl, deduped);
                    if (docSuccess) {
                        totalSuccess++;
                    } else {
                        totalFail++;
                        failedPaths.add(path);
                    }
                }
            }

            // ── 3. Finalise status ────────────────────────────────────────
            String finalStatus;
            if (rateLimited) {
                finalStatus = SyncStatusManager.FAILED;
            } else if (totalFail > 0 && totalSuccess == 0) {
                finalStatus = SyncStatusManager.FAILED;
            } else if (totalFail > 0) {
                finalStatus = SyncStatusManager.PARTIAL;
            } else {
                finalStatus = SyncStatusManager.READY;
            }

            // Mark NOT_DOWNLOADED only if we literally have nothing in DB
            int totalActive = db.interviewDao().getTotalActiveQuestionsCount();
            if (totalActive == 0 && finalStatus.equals(SyncStatusManager.FAILED)) {
                finalStatus = SyncStatusManager.NOT_DOWNLOADED;
            }

            SyncStatusManager.updateStatus(ctx, db, finalStatus,
                    db.interviewDao().getTotalActiveQuestionsCount(),
                    totalSuccess, totalSkip, totalFail, CURRENT_PARSER_VERSION);

            // Persist failed paths for retry
            SyncStatusManager.saveFailedPaths(ctx, buildJsonArray(failedPaths));

            Log.i(TAG, "Skip summary: unchanged=" + skipUnchanged
                    + ", directory=" + skipDirectory
                    + ", emptyParse=" + skipEmptyParse);

            // Send notification
            String notifTitle = rateLimited ? "GitHub 请求受限"
                    : (totalFail == 0 ? "题库同步完成" : "题库同步完成（部分失败）");
            String notifBody  = rateLimited
                    ? "GitHub 请求次数受限，请稍后再试"
                    : "成功: " + totalSuccess + "  跳过: " + totalSkip + "  失败: " + totalFail;
            sendNotification(ctx, notifTitle, notifBody);

            return (totalFail == 0 && !rateLimited) ? Result.success() : Result.failure();

        } catch (Exception e) {
            Log.e(TAG, "Unexpected error during sync", e);
            SyncStatusManager.updateStatus(ctx, db, SyncStatusManager.FAILED,
                    db.interviewDao().getTotalActiveQuestionsCount(),
                    totalSuccess, totalSkip, totalFail + 1, CURRENT_PARSER_VERSION);
            sendNotification(ctx, "题库同步异常", e.getMessage());
            return Result.failure();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tree fetching with ETag caching
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns list of candidate JsonObjects (path + sha), or null on rate-limit / fatal error.
     * Uses ETag and cached JSON to minimise API calls.
     */
    private List<JsonObject> fetchCandidates(Context ctx, String repo,
                                             boolean retryOnly, String retryPathsJson) {
        try {
            String treeUrl = "https://api.github.com/repos/" + repo + "/git/trees/main?recursive=1";
            String cachedEtag = SyncStatusManager.getCachedEtag(ctx, repo);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(treeUrl)
                    .header("User-Agent", "InterviewApp");
            if (cachedEtag != null && !cachedEtag.isEmpty()) {
                reqBuilder.header("If-None-Match", cachedEtag);
            }

            try (Response resp = client.newCall(reqBuilder.build()).execute()) {
                if (resp.code() == 304) {
                    // Not modified – use cache
                    String cached = SyncStatusManager.getCachedTree(ctx, repo);
                    if (cached != null) {
                        return filterCandidates(repo, cached, retryOnly, retryPathsJson);
                    }
                }
                if (resp.code() == 403 || resp.code() == 429) {
                    Log.w(TAG, "GitHub rate limit hit for " + repo);
                    return null;
                }
                // Check rate-limit header
                String remaining = resp.header("X-RateLimit-Remaining");
                if ("0".equals(remaining)) {
                    Log.w(TAG, "X-RateLimit-Remaining=0 for " + repo);
                    return null;
                }
                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.w(TAG, "Tree API failed: " + resp.code());
                    return new ArrayList<>(); // non-fatal: skip this repo
                }
                String json = resp.body().string();
                String newEtag = resp.header("ETag");
                SyncStatusManager.cacheTree(ctx, repo, json, newEtag);
                return filterCandidates(repo, json, retryOnly, retryPathsJson);
            }
        } catch (IOException e) {
            Log.e(TAG, "fetchCandidates failed for " + repo, e);
            return new ArrayList<>();
        }
    }

    private List<JsonObject> filterCandidates(String repo, String json,
                                              boolean retryOnly, String retryPathsJson) {
        List<JsonObject> result = new ArrayList<>();
        JsonObject root = gson.fromJson(json, JsonObject.class);
        JsonArray tree  = root.has("tree") ? root.getAsJsonArray("tree") : new JsonArray();

        // Build retry set if needed
        java.util.Set<String> retrySet = null;
        if (retryOnly && retryPathsJson != null) {
            retrySet = new java.util.HashSet<>();
            JsonArray arr = gson.fromJson(retryPathsJson, JsonArray.class);
            for (JsonElement el : arr) retrySet.add(el.getAsString());
        }

        for (JsonElement el : tree) {
            JsonObject item = el.getAsJsonObject();
            String path = item.has("path") ? item.get("path").getAsString() : "";
            String type = item.has("type") ? item.get("type").getAsString() : "";
            if (!"blob".equals(type) || !path.endsWith(".md")) continue;
            if (!isTargetDocument(repo, path)) continue;
            if (retrySet != null && !retrySet.contains(path)) continue;
            result.add(item);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP with retry + exponential back-off
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns body string, or null after MAX_RETRIES exhausted or rate-limited. */
    private String fetchWithRetry(String url) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (isStopped()) return null;
            try {
                Request req = new Request.Builder().url(url)
                        .header("User-Agent", "InterviewApp").build();
                try (Response resp = client.newCall(req).execute()) {
                    if (resp.code() == 403 || resp.code() == 429) return null; // rate-limited
                    if (resp.isSuccessful() && resp.body() != null) {
                        return resp.body().string();
                    }
                    // 5xx – retry after back-off
                    if (attempt < MAX_RETRIES - 1) {
                        Thread.sleep(BASE_BACKOFF_MS << attempt);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "fetchWithRetry attempt " + attempt + " failed for " + url, e);
                if (attempt < MAX_RETRIES - 1) {
                    try { Thread.sleep(BASE_BACKOFF_MS << attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // In-memory dedup
    // ─────────────────────────────────────────────────────────────────────────

    private List<InterviewQuestion> deduplicateInMemory(List<InterviewQuestion> raw) {
        Map<String, InterviewQuestion> seen = new HashMap<>();
        int dupCount = 0;
        for (InterviewQuestion q : raw) {
            String key = q.sourceRepository + "|" + q.sourceDocumentPath + "|" + q.sourceHeadingAnchor;
            InterviewQuestion prev = seen.put(key, q); // last occurrence wins
            if (prev != null) dupCount++;
        }
        // 重复 anchor 是 SQLiteConstraintException 的唯一根因；发生即告警（不抛异常，保留兜底合并）。
        if (dupCount > 0) {
            String doc = raw.isEmpty() ? "?" : raw.get(0).sourceDocumentPath;
            Log.w(TAG, "Detected " + dupCount + " duplicate (repo|path|anchor) keys in " + doc
                    + " — last occurrence kept. Investigate extractor anchor generation.");
        }
        return new ArrayList<>(seen.values());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-document transaction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Saves one GuideDocument + its questions in a single transaction.
     * Returns true on success, false on any error (caller increments fail counter).
     */
    private boolean saveDocumentTransaction(AppDatabase db, GuideDocument existingDoc,
                                            String repo, String path, String sha, String content,
                                            String category, String docUrl,
                                            List<InterviewQuestion> deduped) {
        try {
            db.runInTransaction(() -> {
                // Upsert GuideDocument
                GuideDocument doc = existingDoc != null ? existingDoc : new GuideDocument();
                doc.title      = path.substring(path.lastIndexOf('/') + 1).replace(".md", "");
                doc.category   = category;
                doc.content    = content;
                doc.path       = path;
                doc.contentHash = sha;
                doc.originalUrl = docUrl;
                doc.updatedAt  = System.currentTimeMillis();
                doc.sourceRepository = repo;
                if (existingDoc == null) {
                    db.interviewDao().insertGuideDocument(doc);
                } else {
                    db.interviewDao().updateGuideDocument(doc);
                }

                // Load existing questions for this doc
                List<InterviewQuestion> existing = db.interviewDao().getQuestionsBySourceDocumentPath(path);
                Map<String, InterviewQuestion> existingByAnchor = new HashMap<>();
                if (existing != null) {
                    for (InterviewQuestion eq : existing) {
                        String k = eq.sourceHeadingAnchor != null ? eq.sourceHeadingAnchor : eq.questionText;
                        existingByAnchor.put(k, eq);
                    }
                }

                // Merge parsed questions preserving user scores/favorites
                for (InterviewQuestion nq : deduped) {
                    String anchor = nq.sourceHeadingAnchor != null ? nq.sourceHeadingAnchor : nq.questionText;
                    InterviewQuestion eq = existingByAnchor.remove(anchor);
                    if (eq != null) {
                        // Preserve user progress; update content fields
                        eq.questionText           = nq.questionText;
                        eq.referenceAnswerMarkdown = nq.referenceAnswerMarkdown;
                        eq.plainTextAnswer        = nq.plainTextAnswer;
                        eq.sourceCommitSha        = nq.sourceCommitSha;
                        eq.parserVersion          = nq.parserVersion;
                        eq.sourceHeadingAnchor    = nq.sourceHeadingAnchor;
                        eq.sourceRepository       = nq.sourceRepository;
                        eq.archived               = false;
                        eq.updatedAt              = System.currentTimeMillis();
                        db.interviewDao().updateQuestion(eq);
                    } else {
                        db.interviewDao().insertQuestion(nq);
                    }
                }

                // Archive questions no longer present in this document
                for (InterviewQuestion stale : existingByAnchor.values()) {
                    stale.archived = true;
                    db.interviewDao().updateQuestion(stale);
                }
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "saveDocumentTransaction failed for " + path, e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Progress reporting
    // ─────────────────────────────────────────────────────────────────────────

    private void reportProgress(int current, int total, String phase,
                                int success, int skip, int fail) {
        setProgressAsync(new Data.Builder()
                .putInt(PROGRESS_CURRENT, current)
                .putInt(PROGRESS_TOTAL,   total)
                .putString(PROGRESS_PHASE, phase)
                .putInt(PROGRESS_SUCCESS, success)
                .putInt(PROGRESS_SKIP,    skip)
                .putInt(PROGRESS_FAIL,    fail)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Document filtering
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isTargetDocument(String repo, String path) {
        String lp = path.toLowerCase();
        // 排除：作者介绍 / 简历 / 资源推荐 / 学习路线 / 项目宣传 / 废弃 / 待完成 / 源码分析 / 导航页
        String[] excludeKeywords = {
                "about-the-author", "resume", "recommend", "roadmap",
                "deprecated", "todo", "source-code", "source-analysis",
                "学习路线", "简历指南", "资源推荐", "项目宣传", "待完成", "纯源码"
        };
        for (String kw : excludeKeywords) {
            if (lp.contains(kw)) return false;
        }
        if (repo.endsWith("JavaGuide")) {
            if (lp.equals("readme.md") || lp.contains("nav")) return false;
            if (lp.startsWith(AI_INTERVIEW_DIR)) {
                return lp.equals(AI_INTERVIEW_DIR + "llm-interview-questions.md")
                        || lp.equals(AI_INTERVIEW_DIR + "agent-interview-questions.md")
                        || lp.equals(AI_INTERVIEW_DIR + "rag-interview-questions.md")
                        || lp.equals(AI_INTERVIEW_DIR + "ai-system-design-interview-questions.md");
            }
            boolean inDir = lp.startsWith("docs/java/") ||
                            lp.startsWith("docs/database/") ||
                            lp.startsWith("docs/distributed-system/") ||
                            lp.startsWith("docs/high-performance/") ||
                            lp.startsWith("docs/high-availability/") ||
                            lp.startsWith("docs/system-design/") ||
                            lp.startsWith("docs/cs-basics/");
            boolean isQFile = lp.contains("question") || lp.contains("interview");
            return inDir && isQFile;
        }
        return false;
    }

    /**
     * Front matter is useful for logging, but it should not decide whether a document is parsed.
     * JavaGuide often uses titles like "知识点总结" or "常见问题" for interview-worthy content.
     */
    private boolean hasFrontMatterWithoutQuestionHint(String rawContent) {
        if (rawContent == null) return false;
        if (!rawContent.startsWith("---\n") && !rawContent.startsWith("---\r\n")) {
            return false;
        }
        int end = rawContent.indexOf("\n---", 3);
        if (end == -1) return false;
        String frontMatter = rawContent.substring(0, end);
        String lower = frontMatter.toLowerCase();
        return !(frontMatter.contains("面试题")
                || frontMatter.contains("常见问题")
                || frontMatter.contains("知识点")
                || lower.contains("interview")
                || lower.contains("question")
                || lower.contains("faq"));
    }

    /**
     * 检测内容是否是"目录导航页"（AIGuide 仓库常见：正文在 javaguide.cn，
     * 仓库内只放标题 + 链接列表）。判据：markdown 链接 [text](url) 的数量
     * 占总行数比例过高（>40%），说明正文几乎全是链接而非问答内容。
     * 这类页面解析出来的"答案"会是链接描述，必须跳过，不创建伪题。
     */
    private boolean isLikelyDirectoryPage(String content) {
        String[] lines = content.split("\n", -1);
        if (lines.length == 0) return false;
        int linkLines = 0;
        int nonEmpty = 0;
        // 匹配含 markdown 链接 [text](http...) 或裸 http 链接的行
        java.util.regex.Pattern linkPattern =
                java.util.regex.Pattern.compile("\\[[^\\]]*\\]\\(https?://|\\bhttps?://\\S+");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            nonEmpty++;
            if (linkPattern.matcher(trimmed).find()) linkLines++;
        }
        if (nonEmpty == 0) return true;
        return linkLines * 100 / nonEmpty > 40;
    }

    /** 一级大类：基于文件路径前缀映射到 JavaGuide 网站侧边栏分类结构。
     *  注意：更具体的前缀必须排在更宽泛的兜底前缀之前（如 message-queue 在 high-performance 前）。 */
    private String resolveParentCategory(String path) {
        String lp = path.toLowerCase();
        if (lp.startsWith("docs/java/basis/"))           return "Java基础";
        if (lp.startsWith("docs/java/collection/"))      return "Java集合";
        if (lp.startsWith("docs/java/concurrent/"))      return "Java并发";
        if (lp.startsWith("docs/java/io/"))              return "Java IO";
        if (lp.startsWith("docs/java/jvm/"))              return "JVM";
        if (lp.startsWith("docs/java/new-features/"))    return "Java新特性";
        if (lp.startsWith("docs/database/mysql/"))       return "MySQL";
        if (lp.startsWith("docs/database/redis/"))       return "Redis";
        if (lp.startsWith("docs/database/elasticsearch/")) return "Elasticsearch";
        if (lp.startsWith("docs/database/mongodb/"))     return "MongoDB";
        if (lp.startsWith("docs/database/sql/"))         return "SQL";
        if (lp.startsWith("docs/database/"))             return "数据库基础";
        if (lp.startsWith("docs/distributed-system/"))   return "分布式";
        // 消息队列必须在 high-performance 兜底之前（message-queue 是其子目录）
        if (lp.startsWith("docs/high-performance/message-queue/")) return "消息队列";
        if (lp.startsWith("docs/high-performance/"))     return "高性能";
        if (lp.startsWith("docs/high-availability/"))    return "高可用";
        // Spring 必须在 system-design 兜底之前（framework/spring 是其子目录）
        if (lp.startsWith("docs/system-design/framework/spring/")) return "Spring";
        if (lp.startsWith("docs/system-design/"))        return "系统设计";
        // 操作系统必须在 cs-basics 兜底之前
        if (lp.startsWith("docs/cs-basics/operating-system/")) return "操作系统";
        if (lp.startsWith("docs/cs-basics/network/"))    return "计算机网络";
        if (lp.startsWith("docs/cs-basics/data-structure/")) return "数据结构";
        if (lp.startsWith("docs/cs-basics/algorithms/")) return "算法";
        if (lp.startsWith("docs/cs-basics/"))            return "计算机基础";
        if (lp.startsWith("docs/ai/"))                   return "AI";
        if (lp.contains("smartcloud-interview-question-bank")
                || lp.contains("smartcloud-agent-rag-tools-interview")
                || lp.contains("smartcloud-claude-code-inspired-interview")
                || lp.contains("multi-agent-architecture")) return "AI";
        return "综合";
    }

    /** 二级小类：沿用旧的分类映射逻辑（后续会被 H2 标题覆盖） */
    private String resolveSubCategory(String path) {
        String lp = path.toLowerCase();
        if (lp.endsWith("/llm-interview-questions.md"))                    return "大模型基础";
        if (lp.endsWith("/agent-interview-questions.md"))                  return "AI Agent";
        if (lp.endsWith("/rag-interview-questions.md"))                    return "RAG";
        if (lp.endsWith("/ai-system-design-interview-questions.md"))       return "AI 系统设计";
        if (lp.endsWith("smartcloud-interview-question-bank.md"))          return "SmartCloud 总览";
        if (lp.endsWith("smartcloud-agent-rag-tools-interview.md"))        return "SmartCloud Agent/RAG";
        if (lp.endsWith("smartcloud-claude-code-inspired-interview.md"))   return "SmartCloud Claude Code";
        if (lp.endsWith("multi-agent-architecture.md"))                    return "SmartCloud 补充";
        if (lp.contains("collection"))                                       return "集合";
        if (lp.contains("concurrent") || lp.contains("thread"))             return "并发";
        if (lp.contains("jvm"))                                              return "JVM";
        if (lp.contains("spring"))                                           return "Spring";
        if (lp.contains("database") || lp.contains("mysql") ||
            lp.contains("redis") || lp.contains("sql"))                     return "数据库";
        if (lp.contains("system-design") || lp.contains("distributed") ||
            lp.contains("design-pattern") || lp.contains("microservice"))   return "系统设计";
        if (lp.contains("ai") || lp.contains("gpt") || lp.contains("llm")) return "AI";
        if (lp.contains("java"))                                             return "Java基础";
        return "综合";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private static final String CHANNEL_ID = "github_sync_channel";
    private static final int    NOTIF_ID   = 1001;

    private void sendNotification(Context ctx, String title, String body) {
        android.app.NotificationManager nm =
                (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    CHANNEL_ID, "题库同步", android.app.NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
        }
        nm.notify(NOTIF_ID, new androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String buildJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(items.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}

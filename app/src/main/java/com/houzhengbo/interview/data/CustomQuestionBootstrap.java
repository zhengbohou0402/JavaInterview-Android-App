package com.houzhengbo.interview.data;

import android.content.Context;
import android.util.Log;

import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.utils.MarkdownAstExtractor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 首次启动时从 assets/custom_questions.md 导入简历定制面试题。
 * 使用 sourceRepository = "custom/resume" 标识，避免与 GitHub 同步冲突。
 */
public class CustomQuestionBootstrap {

    private static final String TAG = "CustomBootstrap";
    private static final String ASSET_PATH = "custom_questions.md";
    static final String SOURCE_REPO = "custom/resume";
    private static final String SOURCE_PATH = "local/custom_questions.md";
    private static final String BOOTSTRAP_SHA = "bootstrap-v7";

    /**
     * 在后台线程调用。按题目身份增量同步 assets：
     * 已有题更新内容并保留练习数据，新题直接插入。
     */
    public static void bootstrapIfNeeded(Context ctx, AppDatabase db) {
        try {
            String markdown = readAsset(ctx, ASSET_PATH);
            if (markdown == null || markdown.isEmpty()) {
                Log.w(TAG, "Asset file is empty or unreadable: " + ASSET_PATH);
                return;
            }

            List<InterviewQuestion> questions = MarkdownAstExtractor.extractQuestions(
                    markdown,
                    SOURCE_REPO,
                    SOURCE_PATH,
                    BOOTSTRAP_SHA,
                    "local://custom_questions.md",
                    "简历专项"
            );

            if (questions.isEmpty()) {
                Log.w(TAG, "No valid questions parsed from " + ASSET_PATH);
                return;
            }

            final int[] inserted = {0};
            final int[] updated = {0};
            Set<String> currentAnchors = new HashSet<>();
            for (InterviewQuestion q : questions) {
                q.sourceType = "CUSTOM";
                q.parentCategory = "简历专项";
                currentAnchors.add(q.sourceHeadingAnchor);
            }
            db.runInTransaction(() -> {
                for (InterviewQuestion q : questions) {
                    InterviewQuestion existing = db.interviewDao().getQuestionByCompositeKey(
                            q.sourceRepository, q.sourceDocumentPath, q.sourceHeadingAnchor);
                    if (existing == null) {
                        db.interviewDao().insertQuestion(q);
                        inserted[0]++;
                    } else {
                        existing.questionText = q.questionText;
                        existing.referenceAnswerMarkdown = q.referenceAnswerMarkdown;
                        existing.plainTextAnswer = q.plainTextAnswer;
                        existing.category = q.category;
                        existing.sourceCommitSha = BOOTSTRAP_SHA;
                        existing.sourceUrl = q.sourceUrl;
                        existing.parserVersion = q.parserVersion;
                        existing.sourceType = "CUSTOM";
                        existing.archived = false;
                        existing.updatedAt = System.currentTimeMillis();
                        db.interviewDao().updateQuestion(existing);
                        updated[0]++;
                    }
                }
                // 改名或删除的内置题只归档，不物理删除，避免破坏历史练习记录。
                for (InterviewQuestion existing : db.interviewDao().getQuestionsByRepo(SOURCE_REPO)) {
                    if (SOURCE_PATH.equals(existing.sourceDocumentPath)
                            && !currentAnchors.contains(existing.sourceHeadingAnchor)
                            && !existing.archived) {
                        existing.archived = true;
                        existing.updatedAt = System.currentTimeMillis();
                        db.interviewDao().updateQuestion(existing);
                    }
                }
            });

            Log.i(TAG, "Custom questions synced. inserted=" + inserted[0]
                    + ", updated=" + updated[0]);
        } catch (Exception e) {
            Log.e(TAG, "Failed to import custom questions", e);
        }
    }

    private static String readAsset(Context ctx, String path) {
        try (InputStream is = ctx.getAssets().open(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Cannot read asset: " + path, e);
            return null;
        }
    }
}

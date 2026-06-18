package com.houzhengbo.interview.utils;

import com.houzhengbo.interview.data.entity.InterviewQuestion;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * JavaGuide 题库 Markdown 解析器（javaguide-ast-v2）。
 *
 * 设计原则（见 JavaGuide 题库质量规范）：
 * - 纯 AST 切分，禁止 split("\n") + startsWith("#") 识别标题。
 * - H2 = 章节分类（不作题），H3 = 候选题，H4+ = 答案子章节（不作独立题）。
 * - 一个问题的答案范围：从问题标题之后到下一个同级或更高级（level <= 3）标题之前。
 * - 保留答案内部 H4/列表/引用/代码块/表格原文。
 * - 删除图片/广告/公众号/打赏/参考资料导航/纯链接列表。
 * - 题目身份 = sourceRepository + sourceDocumentPath + sourceHeadingAnchor（anchor 全局唯一）。
 * - plainTextAnswer 不截断，存全文。
 */
public class MarkdownAstExtractor {

    public static final String PARSER_VERSION = "javaguide-ast-v2";

    private static final Pattern AI_QUESTION_BULLET = Pattern.compile(
            "(?m)^\\s*[-*+]\\s+(.+?[?？])\\s*$");

    /** 问题关键词：含其一即视为候选问题标题 */
    private static final Pattern INCLUDE_WORDS = Pattern.compile(
            "什么|为什么|如何|怎么|哪些|区别|联系|优缺点|原理|过程|作用|场景|是否|能否");

    /** vs / 对比类：识别后做自然语言规范化 */
    private static final Pattern VERSUS_PATTERN = Pattern.compile(
            "\\bvs\\b|\\bVS\\b|对比|比较", Pattern.CASE_INSENSITIVE);

    /** 标题里禁止出现的人工拼接/泛问句式（防御性黑名单） */
    private static final Pattern BLACKLIST_PHRASES = Pattern.compile(
            "请概括|请详细阐述|请简述|请说明|请描述");

    /** "在 XX 中" 这类无主语短语：单独成标题时排除（要求里明确禁止） */
    private static final Pattern IN_CONTEXT_PHRASE = Pattern.compile("^在.{0,12}中[，,。：:]?");

    /** 答案里需要整段剔除的导航/参考资料小节标题 */
    private static final Pattern NOISE_SECTION_PATTERN = Pattern.compile(
            "^#{1,6}\\s*(参考资料|相关阅读|扩展阅读|延伸阅读|参考链接|相关链接|推荐阅读|文章推荐|公众号|关注|打赏|赞助)\\s*$",
            Pattern.MULTILINE);

    /** 连续 ≥3 行的纯链接列表（- [text](http) 或 [text](http) 独占行） */
    private static final Pattern PURE_LINK_LIST_PATTERN = Pattern.compile(
            "(?:^|\\n)((?:\\s*[-*+]?\\s*\\[[^\\]]*\\]\\([^)]*\\)\\s*\\n){3,})",
            Pattern.MULTILINE);

    /** 广告/公众号/打赏/扫码 单行文案 */
    private static final Pattern AD_LINE_PATTERN = Pattern.compile(
            "(?i).*(欢迎关注公众号|扫码关注|扫码加群|点个赞|转发支持|关注微信|阅读原文|关注博主|微信扫码|赞赏作者|打赏作者).*");

    /** 标题前缀装饰：常见 emoji 星标（含变体选择符 U+FE0F）与 markdown 强调符。
     *  注意必须用 \\x{...} 而非 \\u...：Java 正则的 \\u 只支持 4 位 hex，
     *  emoji 在 U+1F300+ 区需用 \\x{...}（任意位数）才能正确表达。 */
    private static final Pattern DECORATION_PREFIX = Pattern.compile(
            "^[\\x{2B50}\\x{FE0F}\\x{2600}-\\x{27BF}\\x{1F300}-\\x{1F9FF}\\x{1F600}-\\x{1F64F}]*[\\s]*");

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry
    // ─────────────────────────────────────────────────────────────────────────

    public static List<InterviewQuestion> extractQuestions(String markdownContent,
                                                           String repoName,
                                                           String documentPath,
                                                           String commitSha,
                                                           String url,
                                                           String category) {
        List<InterviewQuestion> questions = new ArrayList<>();
        if (markdownContent == null || markdownContent.isEmpty()) {
            return questions;
        }

        Parser parser = Parser.builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build();
        Node document = parser.parse(markdownContent);

        // 1) 收集所有 heading 节点（保留文档顺序）
        List<Heading> headings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                headings.add(heading);
                visitChildren(heading);
            }
        });

        // 行号 → 字符偏移映射表。SourceSpan 在 commonmark 0.21.0 只提供
        // getLineIndex()，需用它把 AST 节点投射回原文取出答案文本。
        int[] lineOffsets = buildLineOffsetTable(markdownContent);

        // 2) 跟踪当前 H2 分类（H2 永远不作题，仅作为后续 H3 的 category 覆盖）
        String currentCategory = category;

        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int level = heading.getLevel();

            if (level == 2) {
                String h2Text = stripDecoration(extractPlainText(heading)).trim();
                if (!h2Text.isEmpty()) {
                    currentCategory = h2Text;
                }
                continue;
            }

            // 只允许 H3 作为候选题；H4+ 一律并入所属 H3 的答案，不作独立题
            if (level != 3) {
                continue;
            }

            String rawTitle = stripDecoration(extractPlainText(heading)).trim();
            String title = stripLeadingNumber(rawTitle);
            if (!isValidQuestionHeading(title)) {
                continue;
            }

            // 答案范围：本 heading 之后 → 下一个 level<=3 的 heading 之前
            int startOffset = offsetAfterHeading(heading, lineOffsets);
            int endOffset = markdownContent.length();
            for (int j = i + 1; j < headings.size(); j++) {
                if (headings.get(j).getLevel() <= 3) {
                    endOffset = offsetAtLineStart(headings.get(j), lineOffsets);
                    break;
                }
            }
            if (endOffset < startOffset) endOffset = startOffset;

            String rawAnswerMd = markdownContent.substring(startOffset, endOffset);
            String cleanedMd = cleanMarkdown(rawAnswerMd);
            String plainText = stripMarkdownAndHtml(cleanedMd);

            // 有效正文少于 50 个中文字符 → 放弃
            if (countChineseChars(plainText) < 50) {
                continue;
            }

            // vs / 对比规范化：questionText 用自然句
            String questionText = normalizeVersusTitle(title);

            InterviewQuestion q = new InterviewQuestion();
            q.questionText = questionText;
            q.referenceAnswerMarkdown = cleanedMd;
            q.plainTextAnswer = plainText; // 不截断，存全文
            q.category = currentCategory != null ? currentCategory : category;
            q.sourceRepository = repoName;
            q.sourceDocumentPath = documentPath;
            q.sourceCommitSha = commitSha;
            q.sourceHeadingAnchor = buildAnchor(questionText);
            q.sourceUrl = url;
            q.parserVersion = PARSER_VERSION;
            q.sourceType = "GUIDE";
            q.difficulty = "Medium";
            q.createdAt = System.currentTimeMillis();
            q.updatedAt = System.currentTimeMillis();
            q.nextReviewTime = System.currentTimeMillis();
            questions.add(q);
        }

        return questions;
    }

    /**
     * JavaGuide AI 面试题采用“H2 讲解 + 高频面试题项目符号”的结构，不是普通题库的 H3 问答结构。
     * 每个项目符号生成一道题，并将同一 H2 下、面试题列表之前的讲解作为来源明确的参考答案。
     */
    public static List<InterviewQuestion> extractAiInterviewQuestions(String markdownContent,
                                                                      String repoName,
                                                                      String documentPath,
                                                                      String commitSha,
                                                                      String url,
                                                                      String fallbackCategory) {
        List<InterviewQuestion> questions = new ArrayList<>();
        if (markdownContent == null || markdownContent.isEmpty()) {
            return questions;
        }

        Parser parser = Parser.builder()
                .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .build();
        Node document = parser.parse(markdownContent);
        List<Heading> h2Headings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                if (heading.getLevel() == 2) {
                    h2Headings.add(heading);
                }
                visitChildren(heading);
            }
        });

        int[] lineOffsets = buildLineOffsetTable(markdownContent);
        for (int i = 0; i < h2Headings.size(); i++) {
            Heading heading = h2Headings.get(i);
            int sectionStart = offsetAfterHeading(heading, lineOffsets);
            int sectionEnd = i + 1 < h2Headings.size()
                    ? offsetAtLineStart(h2Headings.get(i + 1), lineOffsets)
                    : markdownContent.length();
            if (sectionEnd <= sectionStart) continue;

            String section = markdownContent.substring(sectionStart, sectionEnd);
            int markerIndex = findAiQuestionMarker(section);
            if (markerIndex < 0) continue;

            String cleanedAnswer = cleanMarkdown(section.substring(0, markerIndex));
            String plainAnswer = stripMarkdownAndHtml(cleanedAnswer);
            if (countChineseChars(plainAnswer) < 30) continue;

            String category = stripDecoration(extractPlainText(heading)).trim();
            if (category.isEmpty()) category = fallbackCategory;

            java.util.regex.Matcher matcher =
                    AI_QUESTION_BULLET.matcher(section.substring(markerIndex));
            while (matcher.find()) {
                String title = stripLeadingNumber(stripDecoration(matcher.group(1)));
                if (!isValidQuestionHeading(title)) continue;

                String questionText = normalizeVersusTitle(title);
                InterviewQuestion q = new InterviewQuestion();
                q.questionText = questionText;
                q.referenceAnswerMarkdown = cleanedAnswer;
                q.plainTextAnswer = plainAnswer;
                q.category = category;
                q.sourceRepository = repoName;
                q.sourceDocumentPath = documentPath;
                q.sourceCommitSha = commitSha;
                q.sourceHeadingAnchor = buildAnchor(category + "-" + questionText);
                q.sourceUrl = url;
                q.parserVersion = PARSER_VERSION;
                q.sourceType = "GUIDE";
                q.difficulty = "Medium";
                q.createdAt = System.currentTimeMillis();
                q.updatedAt = System.currentTimeMillis();
                q.nextReviewTime = System.currentTimeMillis();
                questions.add(q);
            }
        }
        return questions;
    }

    private static int findAiQuestionMarker(String section) {
        int chinese = section.indexOf("高频面试题：");
        int ascii = section.indexOf("高频面试题:");
        if (chinese < 0) return ascii;
        if (ascii < 0) return chinese;
        return Math.min(chinese, ascii);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 标题识别与规范化
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 问题标题判定：去掉前缀序号后的标题是否像一个真正的问题。
     * 只在调用方已经过滤掉 H2/H4+ 之后对 H3 标题调用。
     */
    private static boolean isValidQuestionHeading(String title) {
        if (title == null || title.length() < 3) return false;

        // 防御性黑名单：人工拼接/泛问句式
        if (BLACKLIST_PHRASES.matcher(title).find()) return false;
        // "在 XX 中……" 这类无主语短语单独成标题 → 排除
        if (IN_CONTEXT_PHRASE.matcher(title).find()) return false;

        // 1) 问号
        if (title.endsWith("?") || title.endsWith("？")) return true;
        // 2) 关键词
        if (INCLUDE_WORDS.matcher(title).find()) return true;
        // 3) vs / 对比 / 比较
        if (VERSUS_PATTERN.matcher(title).find()) return true;

        return false;
    }

    /**
     * 把 "X vs Y vs Z" / "X 和 Y 对比" / "X/Y/Z 比较" 规范成自然问句：
     *  - 2 个对象 → "X 和 Y 有什么区别？"
     *  - ≥3 个对象 → "X、Y 和 Z 有什么区别？"
     * 非 vs 类标题原样返回。
     */
    private static String normalizeVersusTitle(String title) {
        if (!VERSUS_PATTERN.matcher(title).find()) {
            return title;
        }
        // 直接把 vs/VS/对比/比较 与标点分隔符一起作为切分点。
        // 注意：不能先 replaceAll vs 再用中文分隔符 split —— "JVM vs JDK vs JRE"
        // 替换成空格后没有中文分隔符，会被切成 1 个 token（历史 bug）。
        String[] parts = title.split("(?i)\\bvs\\b|对比|比较|[/、，,]|和|与|及");
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            String t = p.replaceAll("[?？]", "").trim();
            if (!t.isEmpty()) tokens.add(t);
        }
        if (tokens.size() < 2) {
            return title; // 无法解析出两个对象，退回原标题
        }
        if (tokens.size() == 2) {
            return tokens.get(0) + " 和 " + tokens.get(1) + " 有什么区别？";
        }
        // ≥3 个：前 n-1 用顿号串接，最后一个用"和"
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < tokens.size() - 1; k++) {
            if (k > 0) sb.append("、");
            sb.append(tokens.get(k));
        }
        sb.append(" 和 ").append(tokens.get(tokens.size() - 1)).append(" 有什么区别？");
        return sb.toString();
    }

    /** 剥掉标题里的序号前缀："1." "1.1" "一、" "①" 等 */
    private static String stripLeadingNumber(String title) {
        return title.replaceAll("^[0-9]+(\\.[0-9]+)*[\\.、\\s]+", "")
                .replaceAll("^[一二三四五六七八九十]+[、\\.\\s]+", "")
                .replaceAll("^[①②③④⑤⑥⑦⑧⑨⑩]+\\s*", "")
                .trim();
    }

    /** 去掉标题里的装饰字符：emoji 星标（含 VS16）、markdown 强调符、首尾空白 */
    private static String stripDecoration(String text) {
        if (text == null) return "";
        return DECORATION_PREFIX.matcher(text).replaceFirst("")
                .replaceAll("[*`_]", "")
                .trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 答案文本清洗
    // ─────────────────────────────────────────────────────────────────────────

    private static String cleanMarkdown(String md) {
        if (md == null || md.isEmpty()) return "";
        // 1) 删除"参考资料/相关阅读"等导航小节（连同其下内容直到下一个同级或更高级标题）
        String cleaned = stripNoiseSections(md);
        // 2) 删除 markdown 图片 ![alt](url)
        cleaned = cleaned.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "");
        // 3) 删除连续 ≥3 行的纯链接列表
        cleaned = PURE_LINK_LIST_PATTERN.matcher(cleaned).replaceAll("\n");
        // 4) 删除广告/公众号/打赏/扫码 单行
        cleaned = AD_LINE_PATTERN.matcher(cleaned).replaceAll("");
        return cleaned.trim();
    }

    /** 删除 "参考资料/相关阅读/公众号/打赏..." 这类标题及其后续内容块 */
    private static String stripNoiseSections(String md) {
        String[] lines = md.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (isNoiseHeading(trimmed)) {
                int noiseLevel = headingLevel(trimmed);
                // 跳过该标题及其下所有内容，直到遇到 level<=noiseLevel 的下一个标题
                i++;
                while (i < lines.length) {
                    String nextTrim = lines[i].trim();
                    if (isHeading(nextTrim) && headingLevel(nextTrim) <= noiseLevel) {
                        i--; // 回退一行，让外层重新处理这个同级标题
                        break;
                    }
                    i++;
                }
                continue;
            }
            out.append(line).append("\n");
        }
        return out.toString();
    }

    private static boolean isNoiseHeading(String line) {
        if (!isHeading(line)) return false;
        String text = line.replaceAll("^#{1,6}\\s*", "").trim();
        return NOISE_SECTION_PATTERN.matcher("## " + text + "\n").find()
                || NOISE_SECTION_PATTERN.matcher(line).find();
    }

    private static boolean isHeading(String line) {
        return line.matches("^#{1,6}\\s+.*");
    }

    private static int headingLevel(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == '#') n++;
        return n;
    }

    /** 把 markdown 转成纯文本（剥 HTML/列表标记/引用/反引号），用于搜索与 AI 评分 */
    private static String stripMarkdownAndHtml(String md) {
        if (md == null) return "";
        String plain = md;
        plain = plain.replaceAll("<[^>]*>", "");                  // strip HTML tags
        plain = plain.replaceAll("(?m)^#{1,6}\\s+", "");           // strip heading markers
        plain = plain.replaceAll("(?m)^[\\-\\+\\*>]+\\s+", "");    // strip list/quote markers
        plain = plain.replaceAll("`{1,3}", "");                    // strip code ticks
        plain = plain.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", ""); // strip images (safety)
        return plain.trim();
    }

    private static int countChineseChars(String text) {
        if (text == null) return 0;
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fa5) count++;
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AST helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** 提取一个节点内所有 Text/Code 的纯文本字面量 */
    private static String extractPlainText(Node node) {
        StringBuilder sb = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                sb.append(text.getLiteral());
            }
            @Override
            public void visit(Code code) {
                sb.append(code.getLiteral());
            }
        });
        return sb.toString();
    }

    /**
     * 构造 lineIndex → 字符偏移 的查找表。lineOffsets[i] 表示第 i 行（0-based）
     * 在原文中的起始字符偏移；末尾追加一个等于 content.length() 的哨兵，
     * 这样 lineIndex 恰好等于行数时也能返回文档末尾。
     */
    private static int[] buildLineOffsetTable(String content) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                offsets.add(i + 1);
            }
        }
        offsets.add(content.length()); // 哨兵
        int[] result = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) {
            result[i] = offsets.get(i);
        }
        return result;
    }

    /** 任意 0-based 行号对应原文中的字符偏移；越界返回文档末尾 */
    private static int offsetAtLineStart(int lineIndex, int[] lineOffsets) {
        if (lineIndex < 0) return 0;
        if (lineIndex >= lineOffsets.length) return lineOffsets[lineOffsets.length - 1];
        return lineOffsets[lineIndex];
    }

    /** heading 所在行的起始字符偏移（用第一个 source span 的 lineIndex） */
    private static int offsetAtLineStart(Heading heading, int[] lineOffsets) {
        List<SourceSpan> spans = heading.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            return 0;
        }
        return offsetAtLineStart(spans.get(0).getLineIndex(), lineOffsets);
    }

    /** 标题正文结束、答案正文开始的位置（heading 起始行的下一行首） */
    private static int offsetAfterHeading(Heading heading, int[] lineOffsets) {
        List<SourceSpan> spans = heading.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            return 0;
        }
        return offsetAtLineStart(spans.get(0).getLineIndex() + 1, lineOffsets);
    }

    /** anchor 生成：小写 + 非字母数字(中文保留)转 -，与历史规则尽量一致 */
    private static String buildAnchor(String questionText) {
        String anchor = questionText.toLowerCase()
                .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-");
        anchor = anchor.replaceAll("^-+|-+$", "");
        return "#" + anchor;
    }
}

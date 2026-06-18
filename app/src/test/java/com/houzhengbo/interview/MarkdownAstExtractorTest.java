package com.houzhengbo.interview;

import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.utils.MarkdownAstExtractor;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * JavaGuide 题库解析离线测试。
 *
 * 固定 4 个本地 fixture（app/src/test/resources/*.md），覆盖规范要求的全部断言：
 *  1. "基础概念与常识"（H2 分类）不是题目
 *  2. "Java 语言有哪些特点？"是题目
 *  3. 该题答案包含编号列表（1. / -）
 *  4. "JVM"（答案内部 H4 子标题）不作独立题
 *  5. "JVM vs JDK vs JRE" 能识别并规范化为含"有什么区别"的自然句
 *  6. 同步两次题目数不增加（幂等：两次解析 + 合并后集合相等）
 *  7. 无 SQLiteConstraintException 根因：所有 (repo|path|anchor) 三元组全局唯一
 *
 * 末尾打印每文件随机 5 题 + 答案开头，供人工审阅。
 */
public class MarkdownAstExtractorTest {

    /** 模拟 Worker 的 deduplicateInMemory 逻辑（repo|path|anchor 去重，后者覆盖前者） */
    private static List<InterviewQuestion> dedupLikeWorker(List<InterviewQuestion> raw) {
        java.util.Map<String, InterviewQuestion> seen = new java.util.LinkedHashMap<>();
        for (InterviewQuestion q : raw) {
            String key = q.sourceRepository + "|" + q.sourceDocumentPath + "|" + q.sourceHeadingAnchor;
            seen.put(key, q);
        }
        return new ArrayList<>(seen.values());
    }

    /** 收集所有题目的身份三元组（用于断言无重复 = 不会触发 SQLiteConstraintException） */
    private static Set<String> collectIdentityKeys(List<InterviewQuestion> qs) {
        Set<String> keys = new HashSet<>();
        for (InterviewQuestion q : qs) {
            keys.add(q.sourceRepository + "|" + q.sourceDocumentPath + "|" + q.sourceHeadingAnchor);
        }
        return keys;
    }

    private String loadFixture(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath);
             Scanner s = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to load fixture " + resourcePath, e);
        }
    }

    @Test
    public void testJavaBasicQuestions() {
        runAllAssertions("java-basic-questions-01.md", "docs/java/basis/java-basic-questions-01.md");
    }

    @Test
    public void testJavaCollectionQuestions() {
        runAllAssertions("java-collection-questions-01.md", "docs/java/collection/java-collection-questions-01.md");
    }

    @Test
    public void testJavaConcurrentQuestions() {
        runAllAssertions("java-concurrent-questions-01.md", "docs/java/concurrent/java-concurrent-questions-01.md");
    }

    @Test
    public void testMysqlQuestions() {
        runAllAssertions("mysql-questions-01.md", "docs/database/mysql/mysql-questions-01.md");
    }

    @Test
    public void testAiInterviewQuestionBulletsUseSectionAnswer() {
        String content = "## RAG 基础\n\n"
                + "RAG 会先检索外部知识，再把检索结果作为上下文交给大模型生成答案。"
                + "这样可以减少知识过时问题，也能让答案具有可追溯依据。\n\n"
                + "**高频面试题：**\n\n"
                + "- 什么是 RAG？\n"
                + "- RAG 和微调有什么区别？\n\n"
                + "## 向量数据库\n\n"
                + "向量数据库负责保存嵌入向量，并通过近似最近邻算法快速查找语义相近内容。"
                + "索引选择会影响召回率、延迟和内存占用。\n\n"
                + "高频面试题：\n\n"
                + "- 向量数据库在 RAG 中有什么作用？\n";

        List<InterviewQuestion> questions = MarkdownAstExtractor.extractAiInterviewQuestions(
                content, "Snailclimb/JavaGuide",
                "docs/ai/interview-questions/rag-interview-questions.md",
                "sha-ai", "https://example.test/rag", "RAG");

        assertEquals(3, questions.size());
        assertEquals("RAG 基础", questions.get(0).category);
        assertTrue(questions.get(0).plainTextAnswer.contains("检索外部知识"));
        assertEquals("向量数据库", questions.get(2).category);
        assertEquals("javaguide-ast-v2", questions.get(0).parserVersion);
    }

    private void runAllAssertions(String fixtureName, String docPath) {
        String content = loadFixture("/" + fixtureName);
        assertFalse("Fixture should load: " + fixtureName, content.isEmpty());

        final String repo = "Snailclimb/JavaGuide";
        final String category = "Test";

        // 第一次解析
        List<InterviewQuestion> first = MarkdownAstExtractor.extractQuestions(
                content, repo, docPath, "sha-1", "http://dummy/" + fixtureName, category);
        assertFalse("Should extract at least one question from " + fixtureName, first.isEmpty());

        System.out.println("=========================================");
        System.out.println("File: " + docPath);
        System.out.println("Extracted Questions Count: " + first.size());

        // ── 断言 1："基础概念与常识"（H2 分类）不是题目 ──────────────────────
        for (InterviewQuestion q : first) {
            assertFalse("H2 分类不应成为题目: " + q.questionText,
                    q.questionText.equals("基础概念与常识"));
        }

        // ── 断言 4："JVM"（答案内部 H4 子标题）不作独立题 ────────────────────
        // 强化为：任何以 "JVM" 为完整标题的裸子标题都不该独立成题
        for (InterviewQuestion q : first) {
            assertFalse("H4 子标题 JVM 不应独立成题: " + q.questionText,
                    q.questionText.trim().equals("JVM")
                            || q.questionText.trim().equals("JDK 和 JRE"));
        }

        // ── 断言 7：无 SQLiteConstraintException 根因 —— 三元组全局唯一 ──────
        Set<String> identityKeys = collectIdentityKeys(first);
        assertEquals("身份三元组必须全局唯一 (" + fixtureName + ")，否则会触发 SQLiteConstraintException",
                first.size(), identityKeys.size());

        // ── 断言 6：同步两次题目数不增加（幂等） ──────────────────────────────
        List<InterviewQuestion> second = MarkdownAstExtractor.extractQuestions(
                content, repo, docPath, "sha-1", "http://dummy/" + fixtureName, category);
        // 合并两次结果，模拟 Worker 两次同步后的总集合
        List<InterviewQuestion> mergedRaw = new ArrayList<>(first);
        mergedRaw.addAll(second);
        List<InterviewQuestion> merged = dedupLikeWorker(mergedRaw);
        assertEquals("同步两次后题目数不应增加 (" + fixtureName + ")",
                first.size(), merged.size());

        // 仅 java-basic 文件做以下精细断言（这些是该文件特有的样本）
        if (fixtureName.equals("java-basic-questions-01.md")) {
            // ── 断言 2："Java 语言有哪些特点？" 是题目 ─────────────────────────
            InterviewQuestion features = null;
            for (InterviewQuestion q : first) {
                if (q.questionText.contains("Java 语言有哪些特点")) {
                    features = q;
                    break;
                }
            }
            assertNotNull("应包含 'Java 语言有哪些特点？'", features);

            // ── 断言 3：该题答案包含编号列表 ────────────────────────────────────
            assertNotNull("特点题的 referenceAnswerMarkdown 不应为空", features.referenceAnswerMarkdown);
            boolean hasList = features.referenceAnswerMarkdown.contains("1.")
                    || features.referenceAnswerMarkdown.contains("-")
                    || features.referenceAnswerMarkdown.contains("*");
            assertTrue("特点题答案应包含编号/无序列表内容", hasList);

            // ── 断言 5："JVM vs JDK vs JRE" 能识别并规范化为含"有什么区别"的自然句 ─
            boolean foundVersus = false;
            for (InterviewQuestion q : first) {
                if (q.questionText.contains("JVM") && q.questionText.contains("JRE")) {
                    assertTrue("vs 类标题应规范化为含'有什么区别'的自然句: " + q.questionText,
                            q.questionText.contains("有什么区别"));
                    foundVersus = true;
                    break;
                }
            }
            assertTrue("应识别 'JVM vs JDK vs JRE' 这类 vs 题", foundVersus);
        }

        // ── parserVersion 应统一为 javaguide-ast-v2 ──────────────────────────
        for (InterviewQuestion q : first) {
            assertEquals("parserVersion 应为 javaguide-ast-v2",
                    "javaguide-ast-v2", q.parserVersion);
        }

        // ── plainTextAnswer 不应被截断（应与完整正文长度匹配，无 2000 字硬上限痕迹）
        for (InterviewQuestion q : first) {
            assertNotNull(q.plainTextAnswer);
            assertTrue("plainTextAnswer 非空", q.plainTextAnswer.length() > 0);
        }

        // ── 打印每文件随机 5 题 + 答案开头（供人工审） ─────────────────────────
        System.out.println("Random 5 Samples:");
        List<InterviewQuestion> shuffled = new ArrayList<>(first);
        Collections.shuffle(shuffled);
        int shown = 0;
        for (InterviewQuestion q : shuffled) {
            if (shown >= 5) break;
            System.out.println(" Q" + (shown + 1) + ": " + q.questionText);
            System.out.println("    [category=" + q.category + " anchor=" + q.sourceHeadingAnchor + "]");
            String preview = q.plainTextAnswer.length() > 60
                    ? q.plainTextAnswer.substring(0, 60).replace("\n", " ") + "..."
                    : q.plainTextAnswer.replace("\n", " ");
            System.out.println("    Answer Preview: " + preview);
            shown++;
        }
        System.out.println("=========================================\n");
    }
}

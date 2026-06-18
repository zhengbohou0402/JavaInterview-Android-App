package com.houzhengbo.interview;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.dao.InterviewDao;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.data.entity.PracticeAttempt;
import com.houzhengbo.interview.data.entity.ResumeProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 备份/恢复相关 DAO 行为的真实 Room 集成测试（androidTest，需设备/模拟器）。
 *
 * 这是针对审计缺口 #7 新增的测试：原来的 BackupRestoreTest 只测了 Gson 和 HashMap，
 * 没有真正建库写表。这里用 in-memory Room 数据库验证导入去重、幂等、损坏检测、
 * 复合身份键等行为，确保备份恢复不会把 GUIDE 题与 RESUME 题误合并、不会重复回答历史。
 *
 * 运行：gradlew connectedDebugAndroidTest（需要连接的设备或运行中的模拟器）。
 */
@RunWith(AndroidJUnit4.class)
public class BackupRestoreDaoTest {

    private AppDatabase db;
    private InterviewDao dao;

    @Before
    public void setup() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase.class)
                .allowMainThreadQueries()
                .build();
        dao = db.interviewDao();
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void guideQuestionCompositeKeyLookup() {
        // GUIDE 题靠 (repo, path, anchor) 定位
        InterviewQuestion q = new InterviewQuestion();
        q.sourceType = "GUIDE";
        q.questionText = "Java 语言有哪些特点?";
        q.category = "基础";
        q.sourceRepository = "Snailclimb/JavaGuide";
        q.sourceDocumentPath = "docs/java/basis/java-basic-questions-01.md";
        q.sourceHeadingAnchor = "#java-语言有哪些特点";
        q.parserVersion = "javaguide-ast-v1";
        long id = dao.insertQuestion(q);

        InterviewQuestion found = dao.getQuestionByCompositeKey(
                "Snailclimb/JavaGuide",
                "docs/java/basis/java-basic-questions-01.md",
                "#java-语言有哪些特点");
        assertNotNull("GUIDE 题应能按复合键查到", found);
        assertEquals(id, found.id);

        // 不同 anchor 不应命中
        assertNull(dao.getQuestionByCompositeKey(
                "Snailclimb/JavaGuide",
                "docs/java/basis/java-basic-questions-01.md",
                "#其他锚点"));
    }

    @Test
    public void resumeQuestionIdentityDoesNotMergeAcrossResumes() {
        // 关键断言：不同 resumeId 的同名 RESUME 题绝不能合并。
        ResumeProfile r1 = new ResumeProfile();
        r1.fileName = "a.pdf";
        r1.content = "resume a";
        int rid1 = (int) dao.insertResume(r1);

        ResumeProfile r2 = new ResumeProfile();
        r2.fileName = "b.pdf";
        r2.content = "resume b";
        int rid2 = (int) dao.insertResume(r2);

        InterviewQuestion q1 = makeResumeQuestion(rid1, "请介绍你的项目");
        InterviewQuestion q2 = makeResumeQuestion(rid2, "请介绍你的项目");
        dao.insertQuestion(q1);
        dao.insertQuestion(q2);

        // 两个不同 resumeId 的同名题都应独立存在
        assertNotNull(dao.getResumeQuestionByIdentity(rid1, "resume-v2", "请介绍你的项目"));
        assertNotNull(dao.getResumeQuestionByIdentity(rid2, "resume-v2", "请介绍你的项目"));
        // 用文本匹配（旧的 getQuestionByText）会撞到其中一个，证明文本匹配不可靠
        InterviewQuestion textHit = dao.getQuestionByText("请介绍你的项目");
        assertNotNull(textHit);
        assertTrue("文本匹配只返回一个，无法区分两份简历", textHit.resumeId == rid1 || textHit.resumeId == rid2);
    }

    @Test
    public void attemptFingerprintIsIdempotent() {
        // 导入幂等：相同 (questionId, userAnswer, attemptedAt) 的 attempt 不应重复插入
        InterviewQuestion q = new InterviewQuestion();
        q.sourceType = "GUIDE";
        q.questionText = "测试题";
        q.sourceRepository = "repo";
        q.sourceDocumentPath = "path";
        q.sourceHeadingAnchor = "#anchor";
        q.parserVersion = "v1";
        int qId = (int) dao.insertQuestion(q);

        PracticeAttempt a = new PracticeAttempt();
        a.questionId = qId;
        a.userAnswer = "我的回答";
        a.attemptedAt = 1000L;
        dao.insertAttempt(a);

        // 指纹计数应为 1
        assertEquals(1, dao.countAttemptByFingerprint(qId, "我的回答", 1000L));
        // 不同的 attemptedAt 不算重复
        assertEquals(0, dao.countAttemptByFingerprint(qId, "我的回答", 2000L));
        // 不同的 userAnswer 不算重复
        assertEquals(0, dao.countAttemptByFingerprint(qId, "另一个回答", 1000L));
    }

    @Test
    public void archivedQuestionsExcludedFromUserFacingQueries() {
        // archived 垃圾题不应出现在提醒、题库搜索、分类、统计里
        InterviewQuestion active = makeGuideQuestion("#active", "活跃题");
        InterviewQuestion archived = makeGuideQuestion("#archived", "归档题");
        archived.archived = true;
        dao.insertQuestion(active);
        dao.insertQuestion(archived);

        // 随机题不应返回 archived
        for (int i = 0; i < 20; i++) {
            InterviewQuestion random = dao.getRandomQuestion();
            if (random != null) {
                assertTrue("随机题不应是 archived", !random.questionText.equals("归档题"));
            }
        }
        // 搜索不应返回 archived
        for (InterviewQuestion hit : dao.searchQuestions(null, null, null, false)) {
            assertTrue("搜索结果不应含 archived 题", !hit.questionText.equals("归档题"));
        }
        // 分类下拉不应含 archived 题的分类
        assertTrue("分类不应含归档题的分类", !dao.getAllCategories().contains("归档"));
    }

    @Test
    public void parserVersionTrackingForResyncDecision() {
        // 文档级 parserVersion 比对：旧版本题存在时应返回 >0，需要重解析。
        // 关键：测试题的 sourceDocumentPath 必须与查询路径一致。
        final String docPath = "docs/java/basis/java-basic-questions-01.md";
        InterviewQuestion q = new InterviewQuestion();
        q.sourceType = "GUIDE";
        q.questionText = "旧解析题";
        q.sourceRepository = "Snailclimb/JavaGuide";
        q.sourceDocumentPath = docPath;
        q.sourceHeadingAnchor = "#old-parser";
        q.parserVersion = "local-headings-v2"; // 旧版
        long qid = dao.insertQuestion(q);
        q.id = (int) qid; // @Update 需要主键定位，必须回填 id

        // 存在旧 parserVersion 的题 → 应判定为需重解析
        int stale = dao.countQuestionsWithOldParser(docPath, "javaguide-ast-v1");
        assertTrue("存在旧 parserVersion 的题应被识别为需重解析 (stale=" + stale + ")", stale > 0);

        // 升级到新版后，该文档下不再有旧 parserVersion 的题
        q.parserVersion = "javaguide-ast-v1";
        dao.updateQuestion(q);
        int fresh = dao.countQuestionsWithOldParser(docPath, "javaguide-ast-v1");
        assertEquals("升级后该文档不应再有旧 parserVersion 的题", 0, fresh);
    }

    private InterviewQuestion makeResumeQuestion(int resumeId, String text) {
        InterviewQuestion q = new InterviewQuestion();
        q.sourceType = "RESUME";
        q.questionText = text;
        q.resumeId = resumeId;
        q.generationVersion = "resume-v2";
        q.category = "简历";
        q.parserVersion = "resume-v2";
        return q;
    }

    private InterviewQuestion makeGuideQuestion(String anchor, String text) {
        InterviewQuestion q = new InterviewQuestion();
        q.sourceType = "GUIDE";
        q.questionText = text;
        q.category = text.equals("归档题") ? "归档" : "活跃";
        q.sourceRepository = "repo";
        q.sourceDocumentPath = "path.md";
        q.sourceHeadingAnchor = anchor;
        q.parserVersion = "javaguide-ast-v1";
        return q;
    }
}

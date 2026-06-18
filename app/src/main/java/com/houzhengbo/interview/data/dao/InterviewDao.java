package com.houzhengbo.interview.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import com.houzhengbo.interview.data.entity.*;
import java.util.List;

@Dao
public interface InterviewDao {
    @Query("SELECT * FROM guide_document")
    List<GuideDocument> getAllGuideDocuments();

    @Insert
    void insertGuideDocument(GuideDocument document);

    @Query("SELECT * FROM guide_document WHERE path = :path LIMIT 1")
    GuideDocument getGuideDocumentByPath(String path);

    /** Look up a guide document by its full composite identity (repo + path). */
    @Query("SELECT * FROM guide_document WHERE sourceRepository = :repo AND path = :path LIMIT 1")
    GuideDocument getGuideDocumentByRepoAndPath(String repo, String path);

    @Update
    void updateGuideDocument(GuideDocument document);

    @Query("SELECT * FROM app_settings WHERE id = 1")
    AppSettings getSettings();

    @Insert
    void insertSettings(AppSettings settings);

    @Update
    void updateSettings(AppSettings settings);

    @Insert
    long insertResume(ResumeProfile profile);

    @Query("SELECT * FROM resume_profile ORDER BY importedAt DESC LIMIT 1")
    ResumeProfile getLatestResume();

    @Query("DELETE FROM resume_profile")
    void deleteAllResumes();

    @Query("DELETE FROM interview_question WHERE resumeId = :resumeId")
    void deleteQuestionsByResumeId(int resumeId);

    @Insert
    long insertQuestion(InterviewQuestion question);

    @Query("SELECT * FROM interview_question WHERE sourceDocumentPath = :path LIMIT 1")
    InterviewQuestion getQuestionBySourcePath(String path);

    @Query("SELECT * FROM interview_question WHERE sourceDocumentPath = :path")
    List<InterviewQuestion> getQuestionsBySourceDocumentPath(String path);

    @Query("SELECT * FROM interview_question WHERE archived = 0 ORDER BY nextReviewTime ASC LIMIT 10")
    List<InterviewQuestion> getQuestionsForReview();

    @Query("SELECT * FROM interview_question WHERE archived = 0 ORDER BY RANDOM() LIMIT 1")
    InterviewQuestion getRandomQuestion();

    @Query("SELECT * FROM interview_question WHERE id = :id LIMIT 1")
    InterviewQuestion getQuestionById(int id);

    @Query("SELECT * FROM interview_question")
    List<InterviewQuestion> getAllQuestions();

    @Query("SELECT * FROM practice_attempt")
    List<PracticeAttempt> getAllAttempts();

    @Query("SELECT * FROM ai_evaluation")
    List<AiEvaluation> getAllEvaluations();

    @Query("SELECT DISTINCT category FROM interview_question WHERE category IS NOT NULL AND archived = 0")
    List<String> getAllCategories();

    @Query("SELECT DISTINCT parentCategory FROM interview_question WHERE parentCategory IS NOT NULL AND archived = 0 ORDER BY parentCategory")
    List<String> getAllParentCategories();

    @Query("SELECT DISTINCT category FROM interview_question WHERE parentCategory = :parentCategory AND category IS NOT NULL AND archived = 0 ORDER BY category")
    List<String> getCategoriesByParent(String parentCategory);

    @Query("SELECT * FROM interview_question WHERE parentCategory = :parentCategory AND archived = 0 ORDER BY RANDOM()")
    List<InterviewQuestion> getQuestionsByParentCategory(String parentCategory);

    @Query("SELECT DISTINCT parentCategory FROM interview_question WHERE archived = 0 " +
           "AND parentCategory IS NOT NULL AND parentCategory != '' " +
           "AND (:sectionGroup IS NULL " +
           " OR (:sectionGroup = 'PROJECT' AND (sourceType IN ('CUSTOM', 'RESUME') OR sourceRepository = 'custom/resume' OR parentCategory = '简历专项')) " +
           " OR (:sectionGroup = 'KNOWLEDGE' AND NOT (sourceType IN ('CUSTOM', 'RESUME') OR sourceRepository = 'custom/resume' OR parentCategory = '简历专项'))) " +
           "ORDER BY parentCategory")
    List<String> getParentCategoriesByFilters(String sectionGroup);

    @Query("SELECT DISTINCT category FROM interview_question WHERE archived = 0 " +
           "AND category IS NOT NULL AND category != '' " +
           "AND (:parentCategory IS NULL OR parentCategory = :parentCategory) " +
           "AND (:sectionGroup IS NULL " +
           " OR (:sectionGroup = 'PROJECT' AND (sourceType IN ('CUSTOM', 'RESUME') OR sourceRepository = 'custom/resume' OR parentCategory = '简历专项')) " +
           " OR (:sectionGroup = 'KNOWLEDGE' AND NOT (sourceType IN ('CUSTOM', 'RESUME') OR sourceRepository = 'custom/resume' OR parentCategory = '简历专项'))) " +
           "ORDER BY category")
    List<String> getCategoriesByFilters(String sectionGroup, String parentCategory);

    @Query("SELECT * FROM interview_question WHERE archived = 0 AND " +
           "(:keyword IS NULL OR questionText LIKE '%' || :keyword || '%') AND " +
           "(:category IS NULL OR category = :category) AND " +
           "(:favoriteOnly = 0 OR favorite = 1) AND " +
           "(:sectionGroup IS NULL OR " +
           " (:sectionGroup = 'PROJECT' AND (" +
           "  sourceType = 'RESUME' OR category LIKE 'RAG 项目%' OR category LIKE '实习%' OR " +
           "  category LIKE '黑马点评%' OR category LIKE '简历项目%' OR category LIKE '插码平台%')) OR " +
           " (:sectionGroup = 'KNOWLEDGE' AND NOT (" +
           "  sourceType = 'RESUME' OR category LIKE 'RAG 项目%' OR category LIKE '实习%' OR " +
           "  category LIKE '黑马点评%' OR category LIKE '简历项目%' OR category LIKE '插码平台%'))) " +
           "ORDER BY nextReviewTime ASC")
    List<InterviewQuestion> searchQuestions(String keyword, String category, String sectionGroup,
                                            boolean favoriteOnly);

    /** 新版搜索：支持 parentCategory + category 两级筛选 */
    @Query("SELECT * FROM interview_question WHERE archived = 0 AND " +
           "(:keyword IS NULL OR questionText LIKE '%' || :keyword || '%') AND " +
           "(:parentCategory IS NULL OR parentCategory = :parentCategory) AND " +
           "(:category IS NULL OR category = :category) AND " +
           "(:favoriteOnly = 0 OR favorite = 1) AND " +
           "(:sectionGroup IS NULL " +
           " OR (:sectionGroup = 'PROJECT' AND (sourceType IN ('CUSTOM', 'RESUME') OR sourceRepository = 'custom/resume' OR parentCategory = '简历专项')) " +
           " OR (:sectionGroup = 'KNOWLEDGE' AND NOT (sourceType IN ('CUSTOM', 'RESUME') OR sourceRepository = 'custom/resume' OR parentCategory = '简历专项'))) " +
           "ORDER BY nextReviewTime ASC")
    List<InterviewQuestion> searchQuestionsWithParent(String keyword, String parentCategory,
                                                      String category, String sectionGroup,
                                                      boolean favoriteOnly);

    @Query("UPDATE interview_question SET favorite = :favorite, updatedAt = :updatedAt WHERE id = :questionId")
    void updateFavorite(int questionId, boolean favorite, long updatedAt);

    @Update
    void updateQuestion(InterviewQuestion question);

    @Query("SELECT COUNT(*) FROM interview_question")
    int getTotalQuestionsCount();

    @Query("SELECT COUNT(*) FROM interview_question WHERE archived = 0 AND masteryLevel >= 80")
    int getMasteredQuestionsCount();

    @Query("SELECT COUNT(*) FROM interview_question WHERE archived = 0 AND nextReviewTime <= :currentTime AND attemptCount > 0")
    int getReviewQuestionsCount(long currentTime);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertAttempt(PracticeAttempt attempt);

    @Update
    void updateAttempt(PracticeAttempt attempt);

    @Query("DELETE FROM resume_profile WHERE id = :id")
    void deleteResumeById(int id);

    @Query("SELECT * FROM interview_question WHERE questionText = :text LIMIT 1")
    InterviewQuestion getQuestionByText(String text);

    @Query("SELECT * FROM interview_question WHERE archived = 0 AND nextReviewTime <= :currentTime AND attemptCount > 0 ORDER BY nextReviewTime ASC LIMIT 1")
    InterviewQuestion getNextReviewQuestion(long currentTime);

    @Query("DELETE FROM interview_question WHERE sourceDocumentPath = :path")
    void deleteQuestionsBySourcePath(String path);

    @androidx.room.Delete
    void deleteQuestion(InterviewQuestion question);

    @Query("DELETE FROM practice_attempt")
    void deleteAllAttempts();

    @Query("DELETE FROM ai_evaluation")
    void deleteAllEvaluations();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertEvaluation(AiEvaluation evaluation);

    @Query("DELETE FROM interview_question")
    void deleteAllQuestions();

    @Query("SELECT * FROM resume_profile")
    List<ResumeProfile> getAllResumes();

    // ── Offline-first sync helpers ──────────────────────────────────────────

    /** Count non-archived questions from a specific repository */
    @Query("SELECT COUNT(*) FROM interview_question WHERE sourceRepository = :repo AND archived = 0")
    int getQuestionCountByRepo(String repo);

    @Query("SELECT * FROM interview_question WHERE sourceRepository = :repo")
    List<InterviewQuestion> getQuestionsByRepo(String repo);

    @Query("SELECT COUNT(*) FROM interview_question WHERE sourceDocumentPath LIKE :pathPattern AND archived = 0")
    int getQuestionCountByPathPattern(String pathPattern);

    /** Count all non-archived questions (for profile display) */
    @Query("SELECT COUNT(*) FROM interview_question WHERE archived = 0")
    int getTotalActiveQuestionsCount();

    /** Active (non-archived) questions for review */
    @Query("SELECT * FROM interview_question WHERE archived = 0 ORDER BY nextReviewTime ASC LIMIT 10")
    List<InterviewQuestion> getActiveQuestionsForReview();

    /** Random active question for home screen */
    @Query("SELECT * FROM interview_question WHERE archived = 0 ORDER BY RANDOM() LIMIT 1")
    InterviewQuestion getRandomActiveQuestion();

    /** Delete only GUIDE questions from GitHub repos (preserves RESUME questions, custom/resume questions, and practice history) */
    @Query("DELETE FROM interview_question WHERE sourceType = 'GUIDE' AND (sourceRepository IS NULL OR sourceRepository != 'custom/resume')")
    void deleteGuideQuestions();

    /** Delete all guide documents */
    @Query("DELETE FROM guide_document")
    void deleteAllGuideDocuments();

    /** Get question by composite unique key */
    @Query("SELECT * FROM interview_question WHERE sourceRepository = :repo AND sourceDocumentPath = :path AND sourceHeadingAnchor = :anchor LIMIT 1")
    InterviewQuestion getQuestionByCompositeKey(String repo, String path, String anchor);

    /** 按 RESUME 身份键查题（resumeId + generationVersion + questionText）。NULL 安全用 IS。 */
    @Query("SELECT * FROM interview_question WHERE " +
           "resumeId IS :resumeId AND generationVersion IS :generationVersion AND questionText = :questionText LIMIT 1")
    InterviewQuestion getResumeQuestionByIdentity(Integer resumeId, String generationVersion, String questionText);

    /** attempt 幂等判定：(questionId, userAnswer, attemptedAt) 是否已存在。用于备份导入去重。 */
    @Query("SELECT COUNT(*) FROM practice_attempt WHERE questionId = :questionId AND attemptedAt = :attemptedAt AND (userAnswer IS :userAnswer OR userAnswer = :userAnswer)")
    int countAttemptByFingerprint(int questionId, String userAnswer, long attemptedAt);

    /**
     * 该文档下是否存在 parserVersion 不是 currentVersion 的题（含 null 旧版）。
     * 用于增量同步判定：返回 >0 说明文档需要用新解析器重解析。
     * 这是"按文档级"判断 parserVersion 是否最新，避免依赖全局 KEY_PARSER_VER
     * 导致 bump parserVersion 后存量 SHA 未变文档被错误跳过。
     */
    @Query("SELECT COUNT(*) FROM interview_question WHERE sourceDocumentPath = :path AND (parserVersion IS NULL OR parserVersion != :currentVersion)")
    int countQuestionsWithOldParser(String path, String currentVersion);

    /** Count questions from a document that were produced by the current parser version. */
    @Query("SELECT COUNT(*) FROM interview_question WHERE sourceRepository = :repo AND sourceDocumentPath = :path AND parserVersion = :parserVersion")
    int countQuestionsWithParser(String repo, String path, String parserVersion);

    /** Upsert settings (insert-or-replace) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSettings(AppSettings settings);
}

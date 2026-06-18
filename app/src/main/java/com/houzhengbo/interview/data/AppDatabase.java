package com.houzhengbo.interview.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import com.houzhengbo.interview.data.entity.*;
import com.houzhengbo.interview.data.dao.*;

@Database(entities = {GuideDocument.class, InterviewQuestion.class, ResumeProfile.class, PracticeAttempt.class, AiEvaluation.class, AppSettings.class}, version = 10, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract InterviewDao interviewDao();

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Reconstruct app_settings to drop aiApiKey column
            database.execSQL("CREATE TABLE IF NOT EXISTS `app_settings_new` (`id` INTEGER NOT NULL, `aiProvider` TEXT, `aiBaseUrl` TEXT, `aiModel` TEXT, `reminderTime` TEXT, `randomPopupEnabled` INTEGER NOT NULL, `wifiOnlySync` INTEGER NOT NULL, `lastSyncTime` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            database.execSQL("INSERT INTO `app_settings_new` (`id`, `aiProvider`, `aiBaseUrl`, `aiModel`, `reminderTime`, `randomPopupEnabled`, `wifiOnlySync`, `lastSyncTime`) SELECT `id`, `aiProvider`, `aiBaseUrl`, `aiModel`, `reminderTime`, `randomPopupEnabled`, `wifiOnlySync`, `lastSyncTime` FROM `app_settings`");
            database.execSQL("DROP TABLE `app_settings`");
            database.execSQL("ALTER TABLE `app_settings_new` RENAME TO `app_settings`");
            
            // interview_question columns addition
            database.execSQL("ALTER TABLE interview_question ADD COLUMN sourceDocumentPath TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN sourceDocumentHash TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN generationModel TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN generationVersion TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN resumeId INTEGER");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN lastPracticedAt INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
        }
    };

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // AppSettings: remove encryptedApiKey
            database.execSQL("CREATE TABLE IF NOT EXISTS `app_settings_new` (`id` INTEGER NOT NULL, `aiProvider` TEXT, `aiBaseUrl` TEXT, `aiModel` TEXT, `reminderTime` TEXT, `randomPopupEnabled` INTEGER NOT NULL, `wifiOnlySync` INTEGER NOT NULL, `lastSyncTime` INTEGER NOT NULL, PRIMARY KEY(`id`))");
            database.execSQL("INSERT INTO `app_settings_new` (`id`, `aiProvider`, `aiBaseUrl`, `aiModel`, `reminderTime`, `randomPopupEnabled`, `wifiOnlySync`, `lastSyncTime`) SELECT `id`, `aiProvider`, `aiBaseUrl`, `aiModel`, `reminderTime`, `randomPopupEnabled`, `wifiOnlySync`, `lastSyncTime` FROM `app_settings`");
            database.execSQL("DROP TABLE `app_settings`");
            database.execSQL("ALTER TABLE `app_settings_new` RENAME TO `app_settings`");

            // PracticeAttempt: Add score, status, hitPoints, missingPoints, improvedAnswer, followUpQuestion
            database.execSQL("CREATE TABLE IF NOT EXISTS `practice_attempt_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `questionId` INTEGER NOT NULL, `userAnswer` TEXT, `score` INTEGER, `status` TEXT, `hitPoints` TEXT, `missingPoints` TEXT, `improvedAnswer` TEXT, `followUpQuestion` TEXT, `aiFeedback` TEXT, `attemptedAt` INTEGER NOT NULL)");
            database.execSQL("INSERT INTO `practice_attempt_new` (`id`, `questionId`, `userAnswer`, `score`, `aiFeedback`, `attemptedAt`) SELECT `id`, `questionId`, `userAnswer`, `score`, `aiFeedback`, `attemptedAt` FROM `practice_attempt`");
            database.execSQL("DROP TABLE `practice_attempt`");
            database.execSQL("ALTER TABLE `practice_attempt_new` RENAME TO `practice_attempt`");

            // Deduplicate GuideDocument before adding unique index
            database.execSQL("DELETE FROM guide_document WHERE id NOT IN (" +
                    "SELECT MAX(d1.id) FROM guide_document d1 " +
                    "INNER JOIN (" +
                    "SELECT path, MAX(updatedAt) as max_updated FROM guide_document GROUP BY path" +
                    ") d2 ON d1.path = d2.path AND d1.updatedAt = d2.max_updated " +
                    "GROUP BY d1.path)");

            // ── CRITICAL FIX: Only dedup GUIDE questions (sourceDocumentPath IS NOT NULL).
            //    RESUME questions have sourceDocumentPath = NULL; grouping by (NULL, questionText)
            //    would collapse same-text questions from different resumes into one, destroying data.
            //    Leave RESUME questions completely untouched. ──
            database.execSQL("CREATE TABLE IF NOT EXISTS temp_iq_merge AS " +
                    "SELECT MIN(id) as kept_id, " +
                    "MAX(favorite) as favorite, " +
                    "MAX(masteryLevel) as masteryLevel, " +
                    "MAX(highestScore) as highestScore, " +
                    "MAX(lastPracticedAt) as lastPracticedAt, " +
                    "SUM(attemptCount) as attemptCount, " +
                    "MAX(updatedAt) as updatedAt " +
                    "FROM interview_question " +
                    "WHERE sourceDocumentPath IS NOT NULL " +
                    "GROUP BY sourceDocumentPath, questionText " +
                    "HAVING COUNT(id) > 1");

            // Merge stats into kept IDs (GUIDE questions only)
            database.execSQL("UPDATE interview_question " +
                    "SET favorite = (SELECT favorite FROM temp_iq_merge WHERE kept_id = interview_question.id), " +
                    "masteryLevel = (SELECT masteryLevel FROM temp_iq_merge WHERE kept_id = interview_question.id), " +
                    "highestScore = (SELECT highestScore FROM temp_iq_merge WHERE kept_id = interview_question.id), " +
                    "lastPracticedAt = (SELECT lastPracticedAt FROM temp_iq_merge WHERE kept_id = interview_question.id), " +
                    "attemptCount = (SELECT attemptCount FROM temp_iq_merge WHERE kept_id = interview_question.id), " +
                    "updatedAt = (SELECT updatedAt FROM temp_iq_merge WHERE kept_id = interview_question.id) " +
                    "WHERE id IN (SELECT kept_id FROM temp_iq_merge)");

            // Update practice_attempt to point to kept_id (GUIDE questions only, never touch RESUME refs)
            database.execSQL("UPDATE practice_attempt SET questionId = (" +
                    "SELECT MIN(iq.id) " +
                    "FROM interview_question iq " +
                    "INNER JOIN interview_question iq_old ON iq.sourceDocumentPath IS NOT NULL " +
                    "AND iq.sourceDocumentPath = iq_old.sourceDocumentPath " +
                    "AND iq.questionText = iq_old.questionText " +
                    "WHERE iq_old.id = practice_attempt.questionId" +
                    ") WHERE EXISTS (" +
                    "SELECT 1 FROM interview_question iq_old " +
                    "WHERE iq_old.id = practice_attempt.questionId " +
                    "AND iq_old.sourceDocumentPath IS NOT NULL)");

            database.execSQL("DROP TABLE temp_iq_merge");

            // Delete GUIDE duplicates only (sourceDocumentPath IS NOT NULL)
            database.execSQL("DELETE FROM interview_question WHERE sourceDocumentPath IS NOT NULL " +
                    "AND id NOT IN (" +
                    "SELECT MIN(id) FROM interview_question " +
                    "WHERE sourceDocumentPath IS NOT NULL " +
                    "GROUP BY sourceDocumentPath, questionText)");

            // GuideDocument: Add unique index on path
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_guide_document_path` ON `guide_document` (`path`)");

            // InterviewQuestion: Add unique index on sourceDocumentPath and questionText
            // Only applies to GUIDE questions; RESUME questions (NULL sourceDocumentPath) are exempt.
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_interview_question_sourceDocumentPath_questionText` ON `interview_question` (`sourceDocumentPath`, `questionText`)");
        }
    };

    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE interview_question ADD COLUMN sourceRepository TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN sourceHeadingAnchor TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN referenceAnswerMarkdown TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN plainTextAnswer TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN sourceCommitSha TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN parserVersion TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN archived INTEGER NOT NULL DEFAULT 0");

            database.execSQL("DROP INDEX IF EXISTS `index_interview_question_sourceDocumentPath_questionText`");

            database.execSQL("UPDATE interview_question SET archived = 1 WHERE generationVersion = 'local-headings-v2' OR questionText LIKE '请概括%' OR questionText LIKE '请详细阐述%'");

            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_interview_question_sourceRepository_sourceDocumentPath_sourceHeadingAnchor` ON `interview_question` (`sourceRepository`, `sourceDocumentPath`, `sourceHeadingAnchor`)");
        }
    };

    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Add sync status fields to app_settings
            database.execSQL("ALTER TABLE app_settings ADD COLUMN syncStatus TEXT DEFAULT 'NOT_DOWNLOADED'");
            database.execSQL("ALTER TABLE app_settings ADD COLUMN totalDownloaded INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE app_settings ADD COLUMN lastSyncSuccess INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE app_settings ADD COLUMN lastSyncSkip INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE app_settings ADD COLUMN lastSyncFail INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE app_settings ADD COLUMN currentParserVersion TEXT");
        }
    };

    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE interview_question ADD COLUMN resumeSection TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN evaluationPoints TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN followUpQuestions TEXT");
            database.execSQL("ALTER TABLE interview_question ADD COLUMN questionType TEXT");
        }
    };

    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // ── CRITICAL FIX: Dedup RESUME questions BEFORE creating unique index.
            //    Without this, any existing duplicates cause the CREATE UNIQUE INDEX to crash.
            //    Strategy: keep the row with the most attempts (then highest ID as tiebreaker),
            //    merge best stats, repoint practice_attempts, then delete losers. ──

            // Find duplicate groups among RESUME questions
            database.execSQL("CREATE TABLE IF NOT EXISTS temp_resume_dedup AS " +
                    "SELECT questionText, resumeId, generationVersion, " +
                    "  MIN(id) as kept_id " +
                    "FROM interview_question " +
                    "WHERE sourceDocumentPath IS NULL AND resumeId IS NOT NULL " +
                    "GROUP BY resumeId, generationVersion, questionText " +
                    "HAVING COUNT(id) > 1");

            // Merge best stats into the kept row
            database.execSQL("UPDATE interview_question SET " +
                    "favorite = MAX(favorite, COALESCE((SELECT MAX(iq2.favorite) FROM interview_question iq2 " +
                    "  WHERE iq2.resumeId = interview_question.resumeId " +
                    "  AND iq2.generationVersion IS interview_question.generationVersion " +
                    "  AND iq2.questionText = interview_question.questionText " +
                    "  AND iq2.sourceDocumentPath IS NULL AND iq2.id != interview_question.id), 0)), " +
                    "highestScore = MAX(highestScore, COALESCE((SELECT MAX(iq2.highestScore) FROM interview_question iq2 " +
                    "  WHERE iq2.resumeId = interview_question.resumeId " +
                    "  AND iq2.generationVersion IS interview_question.generationVersion " +
                    "  AND iq2.questionText = interview_question.questionText " +
                    "  AND iq2.sourceDocumentPath IS NULL AND iq2.id != interview_question.id), 0)), " +
                    "masteryLevel = MAX(masteryLevel, COALESCE((SELECT MAX(iq2.masteryLevel) FROM interview_question iq2 " +
                    "  WHERE iq2.resumeId = interview_question.resumeId " +
                    "  AND iq2.generationVersion IS interview_question.generationVersion " +
                    "  AND iq2.questionText = interview_question.questionText " +
                    "  AND iq2.sourceDocumentPath IS NULL AND iq2.id != interview_question.id), 0)), " +
                    "attemptCount = attemptCount + COALESCE((SELECT SUM(iq2.attemptCount) FROM interview_question iq2 " +
                    "  WHERE iq2.resumeId = interview_question.resumeId " +
                    "  AND iq2.generationVersion IS interview_question.generationVersion " +
                    "  AND iq2.questionText = interview_question.questionText " +
                    "  AND iq2.sourceDocumentPath IS NULL AND iq2.id != interview_question.id), 0) " +
                    "WHERE id IN (SELECT kept_id FROM temp_resume_dedup)");

            // Repoint practice_attempts from duplicate rows to the kept row
            database.execSQL("UPDATE practice_attempt SET questionId = (" +
                    "SELECT td.kept_id FROM temp_resume_dedup td " +
                    "INNER JOIN interview_question iq ON iq.resumeId = td.resumeId " +
                    "  AND iq.generationVersion IS td.generationVersion " +
                    "  AND iq.questionText = td.questionText " +
                    "  AND iq.sourceDocumentPath IS NULL " +
                    "  AND iq.id = practice_attempt.questionId" +
                    ") WHERE questionId IN (" +
                    "SELECT iq.id FROM interview_question iq " +
                    "INNER JOIN temp_resume_dedup td ON iq.resumeId = td.resumeId " +
                    "  AND iq.generationVersion IS td.generationVersion " +
                    "  AND iq.questionText = td.questionText " +
                    "  AND iq.sourceDocumentPath IS NULL " +
                    "  AND iq.id != td.kept_id)");

            // Delete the duplicate rows (keep only kept_id per group)
            database.execSQL("DELETE FROM interview_question WHERE sourceDocumentPath IS NULL " +
                    "AND resumeId IS NOT NULL " +
                    "AND id NOT IN (SELECT kept_id FROM temp_resume_dedup) " +
                    "AND id IN (SELECT iq.id FROM interview_question iq " +
                    "  INNER JOIN temp_resume_dedup td ON iq.resumeId = td.resumeId " +
                    "  AND iq.generationVersion IS td.generationVersion " +
                    "  AND iq.questionText = td.questionText " +
                    "  AND iq.sourceDocumentPath IS NULL)");

            database.execSQL("DROP TABLE IF EXISTS temp_resume_dedup");

            // Now safe to create unique index for RESUME identity
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_interview_question_resumeId_generationVersion_questionText` ON `interview_question` (`resumeId`, `generationVersion`, `questionText`)");

            // Delete garbage legacy questions (archived=true) that have no practice attempts
            database.execSQL("DELETE FROM interview_question WHERE archived = 1 AND id NOT IN (SELECT questionId FROM practice_attempt)");
        }
    };

    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Add sourceRepository column to guide_document
            database.execSQL("ALTER TABLE guide_document ADD COLUMN sourceRepository TEXT");

            // Infer repository for existing documents based on path patterns.
            // All existing documents were synced from Snailclimb/JavaGuide (the AIGuide sync
            // was broken and produced 0 documents), so default to JavaGuide.
            database.execSQL("UPDATE guide_document SET sourceRepository = 'Snailclimb/JavaGuide' WHERE sourceRepository IS NULL");

            // Drop old unique index on path, create new composite index on (sourceRepository, path)
            database.execSQL("DROP INDEX IF EXISTS `index_guide_document_path`");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_guide_document_sourceRepository_path` ON `guide_document` (`sourceRepository`, `path`)");
        }
    };

    public static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            // Add parentCategory column for two-level category hierarchy
            database.execSQL("ALTER TABLE interview_question ADD COLUMN parentCategory TEXT");

            // Backfill parentCategory based on sourceDocumentPath (文件路径 → 一级大类)
            // Matching JavaGuide website sidebar structure
            database.execSQL("UPDATE interview_question SET parentCategory = CASE" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/basis/%' THEN 'Java基础'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/collection/%' THEN 'Java集合'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/concurrent/%' THEN 'Java并发'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/io/%' THEN 'Java IO'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/jvm/%' THEN 'JVM'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/new-features/%' THEN 'Java新特性'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/mysql/%' THEN 'MySQL'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/redis/%' THEN 'Redis'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/elasticsearch/%' THEN 'Elasticsearch'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/mongodb/%' THEN 'MongoDB'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/sql/%' THEN 'SQL'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/%' THEN '数据库基础'" +
                    " WHEN sourceDocumentPath LIKE 'docs/distributed-system/%' THEN '分布式'" +
                    " WHEN sourceDocumentPath LIKE 'docs/high-performance/%' THEN '高性能'" +
                    " WHEN sourceDocumentPath LIKE 'docs/system-design/%' THEN '系统设计'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/network/%' THEN '计算机网络'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/data-structure/%' THEN '数据结构'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/algorithms/%' THEN '算法'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/%' THEN '计算机基础'" +
                    " WHEN sourceDocumentPath LIKE 'docs/ai/%' THEN 'AI'" +
                    " WHEN sourceRepository = 'custom/resume' THEN '简历专项'" +
                    " WHEN sourceType = 'RESUME' THEN '简历专项'" +
                    " ELSE '综合'" +
                    " END WHERE parentCategory IS NULL");
        }
    };

    /**
     * v9 → v10：细化一级大类映射。
     * 新增细分：操作系统（从"计算机基础"拆出）、Spring（从"系统设计"拆出）、
     * 消息队列（从"高性能"拆出）、高可用（新增同步目录）。
     *
     * 关键：sync 的 merge 不更新 parentCategory（见 GithubRepoSyncWorker.saveDocumentTransaction），
     * bump parserVersion 也无效，所以存量题只能靠这条迁移无条件回填。
     * 不带 WHERE parentCategory IS NULL —— 强制覆盖，让旧分类（如"计算机基础"里的操作系统题）正确更新。
     * CASE 映射必须和 GithubRepoSyncWorker.resolveParentCategory 完全一致（两份手写副本，保持同步）。
     */
    public static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL("UPDATE interview_question SET parentCategory = CASE" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/basis/%' THEN 'Java基础'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/collection/%' THEN 'Java集合'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/concurrent/%' THEN 'Java并发'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/io/%' THEN 'Java IO'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/jvm/%' THEN 'JVM'" +
                    " WHEN sourceDocumentPath LIKE 'docs/java/new-features/%' THEN 'Java新特性'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/mysql/%' THEN 'MySQL'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/redis/%' THEN 'Redis'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/elasticsearch/%' THEN 'Elasticsearch'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/mongodb/%' THEN 'MongoDB'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/sql/%' THEN 'SQL'" +
                    " WHEN sourceDocumentPath LIKE 'docs/database/%' THEN '数据库基础'" +
                    " WHEN sourceDocumentPath LIKE 'docs/distributed-system/%' THEN '分布式'" +
                    " WHEN sourceDocumentPath LIKE 'docs/high-performance/message-queue/%' THEN '消息队列'" +
                    " WHEN sourceDocumentPath LIKE 'docs/high-performance/%' THEN '高性能'" +
                    " WHEN sourceDocumentPath LIKE 'docs/high-availability/%' THEN '高可用'" +
                    " WHEN sourceDocumentPath LIKE 'docs/system-design/framework/spring/%' THEN 'Spring'" +
                    " WHEN sourceDocumentPath LIKE 'docs/system-design/%' THEN '系统设计'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/operating-system/%' THEN '操作系统'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/network/%' THEN '计算机网络'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/data-structure/%' THEN '数据结构'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/algorithms/%' THEN '算法'" +
                    " WHEN sourceDocumentPath LIKE 'docs/cs-basics/%' THEN '计算机基础'" +
                    " WHEN sourceDocumentPath LIKE 'docs/ai/%' THEN 'AI'" +
                    " WHEN sourceRepository = 'custom/resume' THEN '简历专项'" +
                    " WHEN sourceType = 'RESUME' THEN '简历专项'" +
                    " ELSE '综合'" +
                    " END");
        }
    };

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "interview_database")
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}

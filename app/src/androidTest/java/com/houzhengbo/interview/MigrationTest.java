package com.houzhengbo.interview;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.houzhengbo.interview.data.AppDatabase;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class MigrationTest {
    private static final String TEST_DB = "migration-test";

    @Rule
    public MigrationTestHelper helper;

    public MigrationTest() {
        helper = new MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
                AppDatabase.class.getCanonicalName(),
                new FrameworkSQLiteOpenHelperFactory());
    }

    @Test
    public void migrate1To2() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1);

        // Insert some data in version 1 (with aiApiKey)
        db.execSQL("INSERT INTO app_settings (id, aiProvider, aiBaseUrl, aiModel, aiApiKey, reminderTime, randomPopupEnabled, wifiOnlySync, lastSyncTime) " +
                "VALUES (1, 'DeepSeek', 'https://api.deepseek.com/v1', 'deepseek-chat', 'my_key', '12:00', 1, 0, 1000)");

        db.close();

        // Run migration 1->2
        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2);

        // Query settings in v2 to verify aiApiKey is dropped and other fields are retained
        android.database.Cursor cursor = db.query("SELECT * FROM app_settings WHERE id = 1");
        assertEquals(true, cursor.moveToFirst());
        
        // Assert that columns match
        int apiKeyIndex = cursor.getColumnIndex("aiApiKey");
        int encryptedKeyIndex = cursor.getColumnIndex("encryptedApiKey");
        assertEquals(-1, apiKeyIndex); // should be dropped
        assertEquals(-1, encryptedKeyIndex); // should not exist in Room

        int providerIndex = cursor.getColumnIndex("aiProvider");
        assertEquals("DeepSeek", cursor.getString(providerIndex));
        
        int randomPopupIndex = cursor.getColumnIndex("randomPopupEnabled");
        assertEquals(1, cursor.getInt(randomPopupIndex));

        cursor.close();
    }

    @Test
    public void migrate2To3() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 2);

        // Insert settings in version 2
        db.execSQL("INSERT INTO app_settings (id, aiProvider, aiBaseUrl, aiModel, reminderTime, randomPopupEnabled, wifiOnlySync, lastSyncTime) " +
                "VALUES (1, 'OpenAI', 'https://api.openai.com/v1', 'gpt-4', '18:00', 0, 1, 2000)");

        // Insert attempt in version 2 (with primitive score)
        db.execSQL("INSERT INTO practice_attempt (id, questionId, userAnswer, score, aiFeedback, attemptedAt) " +
                "VALUES (10, 101, 'my answer', 85, 'good job', 99999)");

        db.close();

        // Run migration 2->3
        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3);

        // Check settings
        android.database.Cursor cursor = db.query("SELECT * FROM app_settings WHERE id = 1");
        assertEquals(true, cursor.moveToFirst());
        assertEquals("OpenAI", cursor.getString(cursor.getColumnIndexOrThrow("aiProvider")));
        cursor.close();

        // Check practice_attempt in version 3 (new fields should be null/default, score is nullable)
        android.database.Cursor cursorAttempt = db.query("SELECT * FROM practice_attempt WHERE id = 10");
        assertEquals(true, cursorAttempt.moveToFirst());
        assertEquals(85, cursorAttempt.getInt(cursorAttempt.getColumnIndexOrThrow("score")));
        
        // Verify new columns added in v3 are present and default to null
        int statusIdx = cursorAttempt.getColumnIndexOrThrow("status");
        int hitPointsIdx = cursorAttempt.getColumnIndexOrThrow("hitPoints");
        assertEquals(true, cursorAttempt.isNull(statusIdx));
        assertEquals(true, cursorAttempt.isNull(hitPointsIdx));
        
        cursorAttempt.close();
    }

    @Test
    public void migrate1To3() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1);

        // Insert version 1 settings
        db.execSQL("INSERT INTO app_settings (id, aiProvider, aiBaseUrl, aiModel, aiApiKey, reminderTime, randomPopupEnabled, wifiOnlySync, lastSyncTime) " +
                "VALUES (1, 'DeepSeek', 'https://api.deepseek.com/v1', 'deepseek-chat', 'my_key', '12:00', 1, 0, 1000)");

        db.close();

        // Run migration 1->3 directly
        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3);

        // Verify v3 state
        android.database.Cursor cursor = db.query("SELECT * FROM app_settings WHERE id = 1");
        assertEquals(true, cursor.moveToFirst());
        assertEquals("DeepSeek", cursor.getString(cursor.getColumnIndexOrThrow("aiProvider")));
        assertEquals(-1, cursor.getColumnIndex("aiApiKey"));
        assertEquals(-1, cursor.getColumnIndex("encryptedApiKey"));
        cursor.close();
    }

    @Test
    public void migrate2To3_withDuplicates() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 2);

        // Insert duplicate guide documents
        db.execSQL("INSERT INTO guide_document (id, title, category, content, originalUrl, path, contentHash, updatedAt) VALUES " +
                "(1, 'doc1', 'Java', 'c1', 'url1', 'path1', 'hash1', 100), " +
                "(2, 'doc2', 'Java', 'c2', 'url2', 'path1', 'hash2', 200)");

        // Insert duplicate interview questions
        db.execSQL("INSERT INTO interview_question (id, questionText, sourceDocumentPath, favorite, masteryLevel, highestScore, lastPracticedAt, attemptCount, updatedAt, createdAt, lastScore, nextReviewTime, sourceType, category, difficulty) VALUES " +
                "(10, 'What is Java?', 'doc1.md', 0, 50, 80, 1000, 1, 100, 0, 0, 0, 'GUIDE', 'Java', 'Easy'), " +
                "(11, 'What is Java?', 'doc1.md', 1, 80, 95, 2000, 2, 200, 0, 0, 0, 'GUIDE', 'Java', 'Easy')");

        // Insert practice attempts pointing to both duplicates
        db.execSQL("INSERT INTO practice_attempt (id, questionId, userAnswer, score, aiFeedback, attemptedAt) VALUES " +
                "(100, 10, 'ans1', 80, 'ok', 1000), " +
                "(101, 11, 'ans2', 95, 'good', 2000)");

        db.close();

        // Run migration 2->3
        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3);

        // Check guide_document: only id=2 should remain
        android.database.Cursor cursorGuide = db.query("SELECT id FROM guide_document");
        assertEquals(1, cursorGuide.getCount());
        cursorGuide.moveToFirst();
        assertEquals(2, cursorGuide.getInt(0));
        cursorGuide.close();

        // Check interview_question: only id=10 should remain, but merged
        android.database.Cursor cursorIq = db.query("SELECT * FROM interview_question");
        assertEquals(1, cursorIq.getCount());
        cursorIq.moveToFirst();
        assertEquals(10, cursorIq.getInt(cursorIq.getColumnIndexOrThrow("id")));
        assertEquals(1, cursorIq.getInt(cursorIq.getColumnIndexOrThrow("favorite")));
        assertEquals(80, cursorIq.getInt(cursorIq.getColumnIndexOrThrow("masteryLevel")));
        assertEquals(95, cursorIq.getInt(cursorIq.getColumnIndexOrThrow("highestScore")));
        assertEquals(2000, cursorIq.getLong(cursorIq.getColumnIndexOrThrow("lastPracticedAt")));
        assertEquals(3, cursorIq.getInt(cursorIq.getColumnIndexOrThrow("attemptCount")));
        assertEquals(200, cursorIq.getLong(cursorIq.getColumnIndexOrThrow("updatedAt")));
        cursorIq.close();

        // Check practice_attempt: both should now point to questionId=10
        android.database.Cursor cursorAttempt = db.query("SELECT id FROM practice_attempt WHERE questionId = 10");
        assertEquals(2, cursorAttempt.getCount());
        cursorAttempt.close();
    }

    @Test
    public void migrate3To4() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 3);

        // Insert questions in version 3 format (doesn't have sourceRepository, archived, etc.)
        db.execSQL("INSERT INTO interview_question (id, questionText, sourceDocumentPath, generationVersion, favorite, masteryLevel, highestScore, lastPracticedAt, attemptCount, updatedAt, createdAt, lastScore, nextReviewTime, sourceType, category, difficulty) VALUES " +
                "(1, '请概括一下本文内容？', 'doc1.md', 'local-headings-v2', 0, 0, 0, 0, 0, 0, 0, 0, 0, 'GUIDE', 'Java', 'Easy'), " +
                "(2, 'What is Java?', 'doc2.md', 'some-other', 0, 0, 0, 0, 0, 0, 0, 0, 0, 'GUIDE', 'Java', 'Easy')");

        db.close();

        // Run migration 3->4
        db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4);

        android.database.Cursor cursor = db.query("SELECT id, archived, parserVersion FROM interview_question ORDER BY id ASC");
        assertEquals(2, cursor.getCount());

        cursor.moveToFirst();
        assertEquals(1, cursor.getInt(0)); // id = 1
        assertEquals(1, cursor.getInt(1)); // archived = 1

        cursor.moveToNext();
        assertEquals(2, cursor.getInt(0)); // id = 2
        assertEquals(0, cursor.getInt(1)); // archived = 0

        cursor.close();
    }

    @Test
    public void migrate4To5() throws IOException {
        // Create a version-4 DB and insert one settings row
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 4);
        db.execSQL("INSERT INTO app_settings " +
                "(id, aiProvider, aiBaseUrl, aiModel, reminderTime, randomPopupEnabled, wifiOnlySync, lastSyncTime) " +
                "VALUES (1, 'DeepSeek', 'https://api.deepseek.com/v1', 'deepseek-chat', '09:00', 1, 1, 1234567890)");
        db.close();

        // Run migration 4→5
        db = helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5);

        // Verify new columns exist and have correct default values
        android.database.Cursor cursor = db.query("SELECT * FROM app_settings WHERE id = 1");
        assertEquals(true, cursor.moveToFirst());

        // syncStatus column must exist with default 'NOT_DOWNLOADED'
        int syncStatusIdx = cursor.getColumnIndex("syncStatus");
        assertEquals(false, syncStatusIdx == -1); // column exists
        assertEquals("NOT_DOWNLOADED", cursor.getString(syncStatusIdx));

        // totalDownloaded defaults to 0
        int totalDlIdx = cursor.getColumnIndex("totalDownloaded");
        assertEquals(false, totalDlIdx == -1);
        assertEquals(0, cursor.getInt(totalDlIdx));

        // lastSyncSuccess defaults to 0
        int successIdx = cursor.getColumnIndex("lastSyncSuccess");
        assertEquals(false, successIdx == -1);
        assertEquals(0, cursor.getInt(successIdx));

        // lastSyncSkip defaults to 0
        int skipIdx = cursor.getColumnIndex("lastSyncSkip");
        assertEquals(false, skipIdx == -1);
        assertEquals(0, cursor.getInt(skipIdx));

        // lastSyncFail defaults to 0
        int failIdx = cursor.getColumnIndex("lastSyncFail");
        assertEquals(false, failIdx == -1);
        assertEquals(0, cursor.getInt(failIdx));

        // currentParserVersion is nullable, column must exist
        int parserIdx = cursor.getColumnIndex("currentParserVersion");
        assertEquals(false, parserIdx == -1);

        // Existing data must be preserved
        assertEquals("DeepSeek", cursor.getString(cursor.getColumnIndex("aiProvider")));
        assertEquals(1234567890L, cursor.getLong(cursor.getColumnIndex("lastSyncTime")));

        cursor.close();
    }

    @Test
    public void migrate5To6() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 5);
        db.execSQL("INSERT INTO interview_question " +
                "(id, questionText, sourceDocumentPath, favorite, masteryLevel, highestScore, lastPracticedAt, attemptCount, updatedAt, createdAt, lastScore, nextReviewTime, sourceType, category, difficulty, archived) " +
                "VALUES (500, 'Test Question 5', 'test.md', 0, 0, 0, 0, 0, 0, 0, 0, 0, 'GUIDE', 'Java', 'Medium', 0)");
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6);

        android.database.Cursor cursor = db.query("SELECT * FROM interview_question WHERE id = 500");
        assertEquals(true, cursor.moveToFirst());

        int resumeSecIdx = cursor.getColumnIndex("resumeSection");
        assertEquals(false, resumeSecIdx == -1);
        assertEquals(true, cursor.isNull(resumeSecIdx));

        int evalPointsIdx = cursor.getColumnIndex("evaluationPoints");
        assertEquals(false, evalPointsIdx == -1);
        assertEquals(true, cursor.isNull(evalPointsIdx));

        int followUpIdx = cursor.getColumnIndex("followUpQuestions");
        assertEquals(false, followUpIdx == -1);
        assertEquals(true, cursor.isNull(followUpIdx));

        int qTypeIdx = cursor.getColumnIndex("questionType");
        assertEquals(false, qTypeIdx == -1);
        assertEquals(true, cursor.isNull(qTypeIdx));

        assertEquals("Test Question 5", cursor.getString(cursor.getColumnIndex("questionText")));

        cursor.close();
    }

    @Test
    public void migrate6To7() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 6);

        // Insert some legacy garbage questions (archived=1)
        db.execSQL("INSERT INTO interview_question " +
                "(id, questionText, sourceDocumentPath, favorite, masteryLevel, highestScore, lastPracticedAt, attemptCount, updatedAt, createdAt, lastScore, nextReviewTime, sourceType, category, difficulty, archived) " +
                "VALUES (601, 'Trash 1', 'test1.md', 0, 0, 0, 0, 0, 0, 0, 0, 0, 'GUIDE', 'Java', 'Medium', 1)");
        
        db.execSQL("INSERT INTO interview_question " +
                "(id, questionText, sourceDocumentPath, favorite, masteryLevel, highestScore, lastPracticedAt, attemptCount, updatedAt, createdAt, lastScore, nextReviewTime, sourceType, category, difficulty, archived) " +
                "VALUES (602, 'Trash 2 with attempt', 'test2.md', 0, 0, 0, 0, 0, 0, 0, 0, 0, 'GUIDE', 'Java', 'Medium', 1)");

        // Insert practice attempt for Trash 2
        db.execSQL("INSERT INTO practice_attempt (id, questionId, userAnswer, score, attemptedAt) VALUES (10, 602, 'ans', 100, 1000)");

        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7);

        // Verify garbage without attempt is deleted
        android.database.Cursor cursor = db.query("SELECT * FROM interview_question WHERE id = 601");
        assertEquals(false, cursor.moveToFirst());
        cursor.close();

        // Verify garbage with attempt is kept
        cursor = db.query("SELECT * FROM interview_question WHERE id = 602");
        assertEquals(true, cursor.moveToFirst());
        assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("archived")));
        cursor.close();
        
        // Verify we can't insert duplicates violating new index
        boolean exceptionThrown = false;
        try {
            db.execSQL("INSERT INTO interview_question (id, resumeId, generationVersion, questionText, favorite, masteryLevel, highestScore, lastPracticedAt, attemptCount, updatedAt, createdAt, lastScore, nextReviewTime, archived) " +
                    "VALUES (603, 1, 'v1', 'Duplicate?', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)");
            db.execSQL("INSERT INTO interview_question (id, resumeId, generationVersion, questionText, favorite, masteryLevel, highestScore, lastPracticedAt, attemptCount, updatedAt, createdAt, lastScore, nextReviewTime, archived) " +
                    "VALUES (604, 1, 'v1', 'Duplicate?', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)");
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            exceptionThrown = true;
        }
        assertEquals(true, exceptionThrown);
    }
}

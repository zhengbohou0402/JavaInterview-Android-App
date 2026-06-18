package com.houzhengbo.interview;

import com.google.gson.Gson;
import com.houzhengbo.interview.data.dto.BackupDto;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class BackupRestoreTest {

    @Test
    public void testBackupRestoreSerialization() {
        BackupDto backup = new BackupDto();
        backup.backupSchemaVersion = 3;
        backup.exportedAt = System.currentTimeMillis();

        // Populate Resumes
        backup.resumes = new ArrayList<>();
        BackupDto.ResumeDto rDto = new BackupDto.ResumeDto();
        rDto.originalId = 10;
        rDto.fileName = "test_resume.md";
        rDto.content = "Java developer resume content";
        rDto.importedAt = 123456789L;
        backup.resumes.add(rDto);

        // Populate Questions
        backup.questions = new ArrayList<>();
        BackupDto.QuestionDto qDto1 = new BackupDto.QuestionDto();
        qDto1.originalId = 100;
        qDto1.questionText = "What is JVM?";
        qDto1.referenceAnswer = "Java Virtual Machine";
        qDto1.keywords = "JVM, memory";
        qDto1.difficulty = "Easy";
        qDto1.sourceType = "RESUME";
        qDto1.resumeId = 10;
        backup.questions.add(qDto1);

        BackupDto.QuestionDto qDto2 = new BackupDto.QuestionDto();
        qDto2.originalId = 101;
        qDto2.questionText = "Explain HashMap lock in concurrent contexts.";
        qDto2.referenceAnswer = "Use ConcurrentHashMap";
        qDto2.keywords = "HashMap, concurrent";
        qDto2.difficulty = "Hard";
        qDto2.sourceType = "GUIDE";
        qDto2.sourceTypeUrl = "https://github.com/Snailclimb/JavaGuide/blob/main/docs/java/collection/HashMap.md";
        backup.questions.add(qDto2);

        // Populate Attempts
        backup.attempts = new ArrayList<>();
        BackupDto.AttemptDto aDto1 = new BackupDto.AttemptDto();
        aDto1.originalId = 200;
        aDto1.originalQuestionId = 100;
        aDto1.userAnswer = "My answer about JVM";
        aDto1.score = 80;
        aDto1.status = "SCORED";
        aDto1.attemptedAt = 987654321L;
        backup.attempts.add(aDto1);

        BackupDto.AttemptDto aDto2 = new BackupDto.AttemptDto();
        aDto2.originalId = 201;
        aDto2.originalQuestionId = 101;
        aDto2.userAnswer = "My answer about HashMap lock";
        aDto2.score = 45;
        aDto2.status = "FAILED";
        aDto2.attemptedAt = 987654322L;
        backup.attempts.add(aDto2);

        Gson gson = new Gson();
        String jsonStr = gson.toJson(backup);

        assertNotNull(jsonStr);
        assertTrue(jsonStr.contains("backupSchemaVersion"));
        assertTrue(jsonStr.contains("test_resume.md"));
        assertTrue(jsonStr.contains("What is JVM?"));

        // Deserialize back
        BackupDto restored = gson.fromJson(jsonStr, BackupDto.class);
        assertNotNull(restored);
        assertEquals(3, restored.backupSchemaVersion);
        assertEquals(1, restored.resumes.size());
        assertEquals(2, restored.questions.size());
        assertEquals(2, restored.attempts.size());

        assertEquals("test_resume.md", restored.resumes.get(0).fileName);
        assertEquals("Java developer resume content", restored.resumes.get(0).content);

        assertEquals("What is JVM?", restored.questions.get(0).questionText);
        assertEquals("JVM, memory", restored.questions.get(0).keywords);
        assertEquals(Integer.valueOf(10), restored.questions.get(0).resumeId);

        assertEquals("My answer about JVM", restored.attempts.get(0).userAnswer);
        assertEquals(Integer.valueOf(80), restored.attempts.get(0).score);
    }

    @Test
    public void testIdRemappingLogic() {
        // Simulate remapping process
        Map<Integer, Integer> resumeIdMap = new HashMap<>();
        Map<Integer, Integer> questionIdMap = new HashMap<>();

        // Mock source data
        int originalResumeId = 10;
        int originalQuestionId1 = 100;
        int originalQuestionId2 = 101;

        // Mock DB Insertion auto-generated keys
        int dbNewResumeId = 5;
        resumeIdMap.put(originalResumeId, dbNewResumeId);

        int dbNewQuestionId1 = 50;
        int dbNewQuestionId2 = 51;
        questionIdMap.put(originalQuestionId1, dbNewQuestionId1);
        questionIdMap.put(originalQuestionId2, dbNewQuestionId2);

        // Verify remapping of question resume references
        int qResumeRef = originalResumeId;
        Integer mappedResumeId = resumeIdMap.get(qResumeRef);
        assertNotNull(mappedResumeId);
        assertEquals(5, mappedResumeId.intValue());

        // Verify remapping of attempt references
        int originalAttemptQId = 100;
        Integer mappedQId = questionIdMap.get(originalAttemptQId);
        assertNotNull(mappedQId);
        assertEquals(50, mappedQId.intValue());
    }
}

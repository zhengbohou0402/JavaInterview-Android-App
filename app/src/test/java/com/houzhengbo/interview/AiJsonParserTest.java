package com.houzhengbo.interview;

import com.google.gson.JsonObject;
import com.houzhengbo.interview.network.AiResponseParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class AiJsonParserTest {

    @Test
    public void testValidEvaluationResponse() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"{\\\"score\\\": 85, \\\"hitPoints\\\": \\\"OOP\\\", \\\"missingPoints\\\": \\\"None\\\", \\\"improvedAnswer\\\": \\\"Better\\\", \\\"followUpQuestion\\\": \\\"Next\\\"}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        JsonObject contentObj = AiResponseParser.parseEvaluationResponse(json);
        assertEquals(85, contentObj.get("score").getAsInt());
        assertEquals("OOP", contentObj.get("hitPoints").getAsString());
    }

    @Test
    public void testInvalidEvaluationScoreBounds() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"{\\\"score\\\": 120, \\\"hitPoints\\\": \\\"OOP\\\", \\\"missingPoints\\\": \\\"None\\\", \\\"improvedAnswer\\\": \\\"Better\\\", \\\"followUpQuestion\\\": \\\"Next\\\"}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        try {
            AiResponseParser.parseEvaluationResponse(json);
            fail("Expected exception due to score out of bounds");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("分") || e.getMessage().contains("score"));
        }
    }

    @Test
    public void testValidQuestionGeneration() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"{\\\"questions\\\": [{\\\"question\\\": \\\"Explain HashMap\\\", \\\"referenceAnswer\\\": \\\"Map impl\\\", \\\"keywords\\\": \\\"hash\\\", \\\"difficulty\\\": \\\"Medium\\\", \\\"questionType\\\": \\\"principle\\\", \\\"resumeSection\\\": \\\"techStack\\\", \\\"evaluationPoints\\\": [\\\"要点1\\\"], \\\"followUpQuestions\\\": [\\\"追问1\\\"]}]}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        JsonObject contentObj = AiResponseParser.parseQuestionGenerationResponse(json);
        assertTrue(contentObj.has("questions"));
        assertEquals(1, contentObj.getAsJsonArray("questions").size());
    }

    @Test
    public void testEmptyQuestions() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"{\\\"questions\\\": []}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        try {
            AiResponseParser.parseQuestionGenerationResponse(json);
            fail("Expected exception due to empty questions list");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("空") || e.getMessage().contains("empty") || e.getMessage().contains("questions"));
        }
    }

    @Test
    public void testMissingQuestionField() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"{\\\"questions\\\": [{\\\"referenceAnswer\\\": \\\"Map impl\\\", \\\"keywords\\\": \\\"hash\\\", \\\"difficulty\\\": \\\"Medium\\\", \\\"questionType\\\": \\\"principle\\\", \\\"resumeSection\\\": \\\"techStack\\\", \\\"evaluationPoints\\\": [], \\\"followUpQuestions\\\": []}]}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        try {
            AiResponseParser.parseQuestionGenerationResponse(json);
            fail("Expected exception due to missing question field");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("question"));
        }
    }

    @Test
    public void testInvalidJsonFormat() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"invalid-json-text\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        try {
            AiResponseParser.parseQuestionGenerationResponse(json);
            fail("Expected exception due to invalid JSON format");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("解析") || e.getMessage().contains("JSON"));
        }
    }

    @Test
    public void testNewFieldsPresent() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"{\\\"questions\\\": [{\\\"question\\\": \\\"Q\\\", \\\"referenceAnswer\\\": \\\"A\\\", \\\"keywords\\\": \\\"kw\\\", \\\"difficulty\\\": \\\"Hard\\\", \\\"questionType\\\": \\\"impl\\\", \\\"resumeSection\\\": \\\"project\\\", \\\"evaluationPoints\\\": [\\\"点1\\\"], \\\"followUpQuestions\\\": []}]}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        JsonObject root = AiResponseParser.parseQuestionGenerationResponse(json);
        JsonObject qObj = root.getAsJsonArray("questions").get(0).getAsJsonObject();
        assertEquals("impl", qObj.get("questionType").getAsString());
        assertEquals("project", qObj.get("resumeSection").getAsString());
        assertEquals(1, qObj.getAsJsonArray("evaluationPoints").size());
        assertEquals(0, qObj.getAsJsonArray("followUpQuestions").size());
    }

    @Test
    public void testReferenceAnswerWithPlaceholder() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"{\\\"questions\\\": [{\\\"question\\\": \\\"Q\\\", \\\"referenceAnswer\\\": \\\"通用知识；[请填写实际QPS数据]；STAR原则\\\", \\\"keywords\\\": \\\"kw\\\", \\\"difficulty\\\": \\\"Easy\\\", \\\"questionType\\\": \\\"tradeoff\\\", \\\"resumeSection\\\": \\\"work\\\", \\\"evaluationPoints\\\": [], \\\"followUpQuestions\\\": []}]}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        JsonObject root = AiResponseParser.parseQuestionGenerationResponse(json);
        JsonObject qObj = root.getAsJsonArray("questions").get(0).getAsJsonObject();
        String ref = qObj.get("referenceAnswer").getAsString();
        assertTrue(ref.contains("[请填写实际QPS数据]"));
    }

    @Test
    public void testEvaluateAnswer_malformedContent() {
        String json = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"{\\\"score\\\": 85}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        try {
            AiResponseParser.parseEvaluationResponse(json);
            fail("Expected exception due to missing feedback fields");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("hitPoints") || e.getMessage().contains("missingPoints") || e.getMessage().contains("improvedAnswer"));
        }
    }
}

package com.houzhengbo.interview;

import com.google.gson.JsonObject;
import com.houzhengbo.interview.data.entity.AppSettings;
import com.houzhengbo.interview.network.AiClient;
import com.houzhengbo.interview.network.ApiKeyProvider;
import com.houzhengbo.interview.network.AiResponseParser;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class AiClientTest {
    private MockWebServer mockWebServer;
    private AiClient aiClient;
    private AppSettings testSettings;

    @Before
    public void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build();
        aiClient = new AiClient(httpClient, new ApiKeyProvider() {
            @Override
            public String getApiKey() {
                return "test-api-key";
            }
        });
        
        testSettings = new AppSettings();
        testSettings.aiBaseUrl = "https://api.deepseek.com/v1/";
        testSettings.aiModel = "mock-model";
        
        aiClient.setBaseUrlOverride(mockWebServer.url("/").toString());
    }

    @After
    public void tearDown() throws Exception {
        mockWebServer.shutdown();
    }
    
    @Test
    public void generateQuestions_success() throws Exception {
        String mockResponseContent = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"role\": \"assistant\",\n" +
                "        \"content\": \"{\\\"questions\\\": [{\\\"question\\\": \\\"Java集合中HashMap的原理是什么？\\\", \\\"referenceAnswer\\\": \\\"HashMap基于哈希表实现...\\\", \\\"keywords\\\": \\\"HashMap,哈希表\\\", \\\"difficulty\\\": \\\"Medium\\\", \\\"questionType\\\": \\\"principle\\\", \\\"resumeSection\\\": \\\"techStack\\\", \\\"evaluationPoints\\\": [\\\"要点1\\\"], \\\"followUpQuestions\\\": [\\\"追问1\\\"]}]}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        
        mockWebServer.enqueue(new MockResponse().setBody(mockResponseContent).setResponseCode(200));
        
        String response = aiClient.generateQuestions(testSettings, "Java工程师，精通HashMap");
        assertNotNull(response);
        
        JsonObject parsed = AiResponseParser.parseQuestionGenerationResponse(response);
        assertTrue(parsed.has("questions"));
        assertEquals(1, parsed.getAsJsonArray("questions").size());
    }
    
    @Test
    public void rateLimited_429() throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(429).setBody("Rate limit exceeded"));
        
        try {
            aiClient.generateQuestions(testSettings, "Java工程师");
            fail("Expected IOException due to 429");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("429"));
        }
    }
    
    @Test
    public void evaluateAnswer_success() throws Exception {
        String mockResponseContent = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"role\": \"assistant\",\n" +
                "        \"content\": \"{\\\"score\\\": 90, \\\"hitPoints\\\": \\\"答对HashMap原理\\\", \\\"missingPoints\\\": \\\"未提到扩容机制\\\", \\\"improvedAnswer\\\": \\\"示范回答\\\", \\\"followUpQuestion\\\": \\\"追问\\\"}\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        
        mockWebServer.enqueue(new MockResponse().setBody(mockResponseContent).setResponseCode(200));
        
        String response = aiClient.evaluateAnswer(testSettings, "问题", "参考答案", "关键词", "回答");
        assertNotNull(response);
        
        JsonObject parsed = AiResponseParser.parseEvaluationResponse(response);
        assertEquals(90, parsed.get("score").getAsInt());
        assertEquals("答对HashMap原理", parsed.get("hitPoints").getAsString());
    }

    @Test
    public void timeout() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setBody("Should timeout"));
        
        try {
            aiClient.generateQuestions(testSettings, "Java工程师");
            fail("Expected SocketTimeoutException");
        } catch (java.io.IOException e) {
            // Success: caught timeout/socket exception
        }
    }

    @Test
    public void invalidJson() throws Exception {
        String invalidResponseContent = "{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"role\": \"assistant\",\n" +
                "        \"content\": \"This is not a JSON object at all!\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";
        
        mockWebServer.enqueue(new MockResponse().setBody(invalidResponseContent).setResponseCode(200));
        
        String response = aiClient.generateQuestions(testSettings, "Java工程师");
        try {
            AiResponseParser.parseQuestionGenerationResponse(response);
            fail("Expected RuntimeException due to malformed message content");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("无法将 message content 解析为 JSON 对象"));
        }
    }

    @Test
    public void noApiKey() throws Exception {
        AiClient badClient = new AiClient(null, new ApiKeyProvider() {
            @Override
            public String getApiKey() {
                return "";
            }
        });
        badClient.setBaseUrlOverride(mockWebServer.url("/").toString());
        
        try {
            badClient.generateQuestions(testSettings, "Java工程师");
            fail("Expected IOException due to empty API Key");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("API Key not configured"));
        }
    }

    @Test
    public void networkError() throws Exception {
        mockWebServer.shutdown();
        
        try {
            aiClient.generateQuestions(testSettings, "Java工程师");
            fail("Expected IOException due to connection failure");
        } catch (IOException e) {
            // Success
        }
    }
}


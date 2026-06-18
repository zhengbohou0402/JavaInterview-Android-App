package com.houzhengbo.interview.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.houzhengbo.interview.data.entity.AppSettings;
import okhttp3.*;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class AiClient {
    private final OkHttpClient client;
    private final Gson gson;
    private final ApiKeyProvider apiKeyProvider;
    private String baseUrlOverride = null;

    public AiClient(OkHttpClient client, ApiKeyProvider apiKeyProvider) {
        this.client = client != null ? client : new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
        this.apiKeyProvider = apiKeyProvider;
    }

    public void setBaseUrlOverride(String baseUrl) {
        this.baseUrlOverride = baseUrl;
    }

    public String generateQuestions(AppSettings settings, String resumeContent) throws IOException {
        String baseUrl;
        if (baseUrlOverride != null) {
            baseUrl = baseUrlOverride;
        } else {
            baseUrl = settings.aiBaseUrl != null && !settings.aiBaseUrl.isEmpty() ? settings.aiBaseUrl : "https://api.deepseek.com/v1/";
            if (!baseUrl.startsWith("https://")) {
                throw new IOException("Base URL must use HTTPS");
            }
        }
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        if (baseUrl.endsWith("chat/completions/")) baseUrl = baseUrl.replace("chat/completions/", "");

        String model = settings.aiModel != null && !settings.aiModel.isEmpty() ? settings.aiModel : "deepseek-chat";
        String apiKey = apiKeyProvider.getApiKey();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("API Key not configured");
        }

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "你是一个专业的IT技术面试官。请分析用户提供的简历内容，从以下5个维度深度剖析：\n" +
                "1. 技术栈 (techStack)\n" +
                "2. 项目经历 (project)\n" +
                "3. 工作经历 (work)\n" +
                "4. 个人亮点 (highlight)\n" +
                "5. 可疑或表述模糊内容 (suspicious)\n\n" +
                "针对上述维度，生成 10 到 15 道具有针对性的专业面试题。题目类型应覆盖以下类型：\n" +
                "- 技术原理 (principle)\n" +
                "- 项目实现 (impl)\n" +
                "- 方案取舍 (tradeoff)\n" +
                "- 故障排查 (debug)\n" +
                "- 性能优化 (perf)\n" +
                "- 场景设计 (design)\n" +
                "- 追问 (followup)\n" +
                "- 简历真实性核验 (validity)\n\n" +
                "【特别要求】：\n" +
                "1. 参考答案必须结构化，包含三层：\n" +
                "   - 通用知识（该技术的基本原理或通用方案）\n" +
                "   - 实战数据占位符（例如：\"[请填写您在实际项目中的QPS/数据规模]\"）\n" +
                "   - 回答结构建议（引导用户如何使用STAR原则等有逻辑地作答）\n" +
                "2. 绝对禁止虚构任何具体的量化数据（如具体的QPS、TPS、用户数、并发数、金额）。必须在参考答案中使用形如 \"[请在此处填写实际数据]\" 的明确占位符。\n" +
                "3. 必须严格返回合法的JSON格式，不要包含Markdown语法包裹标记，必须符合指定的JSON Schema格式。");

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "简历内容：\n" + resumeContent + "\n\n" +
                "请以此简历内容生成 10 到 15 道面试题。请严格返回如下 JSON 格式：\n" +
                "{\n" +
                "  \"questions\": [\n" +
                "    {\n" +
                "      \"question\": \"问题文本\",\n" +
                "      \"referenceAnswer\": \"参考答案文本（含通用知识、[实战数据占位符]、回答结构建议）\",\n" +
                "      \"keywords\": \"核心技术关键词，3-5个，逗号分隔\",\n" +
                "      \"difficulty\": \"Medium\", // Easy, Medium, Hard 之一\n" +
                "      \"questionType\": \"principle\", // principle, impl, tradeoff, debug, perf, design, followup, validity 之一\n" +
                "      \"resumeSection\": \"project\", // techStack, project, work, highlight, suspicious 之一\n" +
                "      \"evaluationPoints\": [\"评分要点1\", \"评分要点2\", \"评分要点3\"],\n" +
                "      \"followUpQuestions\": [\"可能的追问1\", \"可能的追问2\"]\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        JsonArray messages = new JsonArray();
        messages.add(systemMsg);
        messages.add(userMsg);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");

        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("model", model);
        bodyObj.add("messages", messages);
        bodyObj.add("response_format", responseFormat);

        String jsonBody = gson.toJson(bodyObj);

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(baseUrl + "chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 429) throw new IOException("Rate limit exceeded (429)");
                if (response.code() == 401) throw new IOException("Unauthorized (401) - Check API Key");
                throw new IOException("Unexpected code " + response);
            }
            ResponseBody resBody = response.body();
            if (resBody == null) throw new IOException("Empty response body");
            String responseStr = resBody.string();
            return responseStr;
        }
    }

    public String evaluateAnswer(AppSettings settings, String question, String referenceAnswer, String keywords, String answer) throws IOException {
        String baseUrl;
        if (baseUrlOverride != null) {
            baseUrl = baseUrlOverride;
        } else {
            baseUrl = settings.aiBaseUrl != null && !settings.aiBaseUrl.isEmpty() ? settings.aiBaseUrl : "https://api.deepseek.com/v1/";
            if (!baseUrl.startsWith("https://")) {
                throw new IOException("Base URL must use HTTPS");
            }
        }
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        if (baseUrl.endsWith("chat/completions/")) baseUrl = baseUrl.replace("chat/completions/", "");

        String model = settings.aiModel != null && !settings.aiModel.isEmpty() ? settings.aiModel : "deepseek-chat";
        String apiKey = apiKeyProvider.getApiKey();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("API Key not configured");
        }

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "你是一个专业的技术面试官。请根据面试问题、参考答案和关键词，对候选人的回答进行专业、客观的评分与反馈。你必须严格返回一个合法的 JSON 对象。");

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "问题: " + question + "\n" +
                "参考答案: " + referenceAnswer + "\n" +
                "关键词: " + keywords + "\n" +
                "候选人回答: " + answer + "\n\n" +
                "请评估候选人的回答。必须严格以下列 JSON 格式返回：\n" +
                "{\n" +
                "  \"score\": 85, // 0 到 100 之间的评分整数\n" +
                "  \"hitPoints\": \"考生答对的要点说明\",\n" +
                "  \"missingPoints\": \"考生遗漏或答错的要点说明\",\n" +
                "  \"improvedAnswer\": \"针对候选人回答的改进版标准示范回答\",\n" +
                "  \"followUpQuestion\": \"针对候选人回答的深入追问问题（若无则为空字符串）\"\n" +
                "}");

        JsonArray messages = new JsonArray();
        messages.add(systemMsg);
        messages.add(userMsg);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");

        JsonObject bodyObj = new JsonObject();
        bodyObj.addProperty("model", model);
        bodyObj.add("messages", messages);
        bodyObj.add("response_format", responseFormat);

        String jsonBody = gson.toJson(bodyObj);

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));
        Request request = new Request.Builder()
                .url(baseUrl + "chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 429) throw new IOException("Rate limit exceeded (429)");
                if (response.code() == 401) throw new IOException("Unauthorized (401) - Check API Key");
                throw new IOException("Unexpected code " + response);
            }
            ResponseBody resBody = response.body();
            if (resBody == null) throw new IOException("Empty response body");
            return resBody.string();
        }
    }
}


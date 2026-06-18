package com.houzhengbo.interview.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class AiResponseParser {

    /**
     * Parses the AI response when generating questions from a resume.
     * Validates that a non-empty "questions" array is present and contains required fields.
     */
    public static JsonObject parseQuestionGenerationResponse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            throw new RuntimeException("AI 返回的生成响应为空");
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(jsonResponse).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("无法解析 AI 返回的 JSON 报文: " + e.getMessage() + "\n原始文本: " + jsonResponse);
        }

        if (!root.has("choices")) {
            throw new RuntimeException("AI 响应格式不正确，缺少 choices 字段");
        }

        JsonArray choices;
        try {
            choices = root.getAsJsonArray("choices");
        } catch (Exception e) {
            throw new RuntimeException("choices 字段格式错误，不是一个数组");
        }

        if (choices == null || choices.size() == 0) {
            throw new RuntimeException("AI 响应的 choices 列表为空");
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        if (!firstChoice.has("message")) {
            throw new RuntimeException("第一项 choice 缺少 message 字段");
        }

        JsonObject message = firstChoice.getAsJsonObject("message");
        if (!message.has("content")) {
            throw new RuntimeException("message 缺少 content 字段");
        }

        String content = message.get("content").getAsString();
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("message content 内容为空");
        }

        // Strip markdown blocks if any
        if (content.startsWith("```json")) {
            content = content.substring(7);
        } else if (content.startsWith("```")) {
            content = content.substring(3);
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }
        content = content.trim();

        JsonObject contentObj;
        try {
            contentObj = JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("无法将 message content 解析为 JSON 对象: " + e.getMessage() + "\n原始文本: " + content);
        }

        if (!contentObj.has("questions")) {
            throw new RuntimeException("生成的问题数据中缺少 questions 字段");
        }

        JsonArray questionsArray;
        try {
            questionsArray = contentObj.getAsJsonArray("questions");
        } catch (Exception e) {
            throw new RuntimeException("questions 字段格式错误，不是一个数组");
        }

        if (questionsArray == null || questionsArray.size() == 0) {
            throw new RuntimeException("生成的问题 questions 数组为空");
        }

        for (int i = 0; i < questionsArray.size(); i++) {
            JsonElement qElem = questionsArray.get(i);
            if (!qElem.isJsonObject()) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项不是一个 JSON 对象");
            }
            JsonObject qObj = qElem.getAsJsonObject();
            if (!qObj.has("question") || qObj.get("question").getAsString().trim().isEmpty()) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项缺少有效的 question 文本字段");
            }
            if (!qObj.has("referenceAnswer")) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项缺少 referenceAnswer 字段");
            }
            if (!qObj.has("keywords")) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项缺少 keywords 字段");
            }
            if (!qObj.has("difficulty")) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项缺少 difficulty 字段");
            }
            if (!qObj.has("questionType")) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项缺少 questionType 字段");
            }
            if (!qObj.has("resumeSection")) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项缺少 resumeSection 字段");
            }
            if (!qObj.has("evaluationPoints") || !qObj.get("evaluationPoints").isJsonArray()) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项缺少 evaluationPoints 字段或格式非数组");
            }
            if (!qObj.has("followUpQuestions") || !qObj.get("followUpQuestions").isJsonArray()) {
                throw new RuntimeException("questions 数组的第 " + (i + 1) + " 项缺少 followUpQuestions 字段或格式非数组");
            }
        }

        return contentObj;
    }

    /**
     * Parses the AI response when evaluating a candidate's answer.
     * Validates that "score", "hitPoints", "missingPoints", "improvedAnswer", and "followUpQuestion" are present,
     * and that the score is a valid integer between 0 and 100.
     */
    public static JsonObject parseEvaluationResponse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            throw new RuntimeException("AI 返回的评估响应为空");
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(jsonResponse).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("无法解析 AI 返回的 JSON 报文: " + e.getMessage() + "\n原始文本: " + jsonResponse);
        }

        if (!root.has("choices")) {
            throw new RuntimeException("AI 响应格式不正确，缺少 choices 字段");
        }

        JsonArray choices;
        try {
            choices = root.getAsJsonArray("choices");
        } catch (Exception e) {
            throw new RuntimeException("choices 字段格式错误，不是一个数组");
        }

        if (choices == null || choices.size() == 0) {
            throw new RuntimeException("AI 响应的 choices 列表为空");
        }

        JsonObject firstChoice = choices.get(0).getAsJsonObject();
        if (!firstChoice.has("message")) {
            throw new RuntimeException("第一项 choice 缺少 message 字段");
        }

        JsonObject message = firstChoice.getAsJsonObject("message");
        if (!message.has("content")) {
            throw new RuntimeException("message 缺少 content 字段");
        }

        String content = message.get("content").getAsString();
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("message content 内容为空");
        }

        // Strip markdown blocks if any
        if (content.startsWith("```json")) {
            content = content.substring(7);
        } else if (content.startsWith("```")) {
            content = content.substring(3);
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }
        content = content.trim();

        JsonObject contentObj;
        try {
            contentObj = JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("无法将 message content 解析为 JSON 对象: " + e.getMessage() + "\n原始文本: " + content);
        }

        // Validate score
        if (contentObj.has("score")) {
            try {
                int score = contentObj.get("score").getAsInt();
                if (score < 0 || score > 100) {
                    throw new RuntimeException("AI 评分分数必须在 0 到 100 之间，实际返回为: " + score);
                }
            } catch (Exception e) {
                throw new RuntimeException("AI 评分分数格式非整数或非法: " + contentObj.get("score").toString());
            }
        } else {
            throw new RuntimeException("AI 返回的评估数据中缺少 score 评分字段");
        }

        // Validate other feedback fields
        if (!contentObj.has("hitPoints")) {
            throw new RuntimeException("AI 评估数据中缺少 hitPoints 字段");
        }
        if (!contentObj.has("missingPoints")) {
            throw new RuntimeException("AI 评估数据中缺少 missingPoints 字段");
        }
        if (!contentObj.has("improvedAnswer")) {
            throw new RuntimeException("AI 评估数据中缺少 improvedAnswer 字段");
        }
        if (!contentObj.has("followUpQuestion")) {
            throw new RuntimeException("AI 评估数据中缺少 followUpQuestion 字段");
        }

        return contentObj;
    }
}

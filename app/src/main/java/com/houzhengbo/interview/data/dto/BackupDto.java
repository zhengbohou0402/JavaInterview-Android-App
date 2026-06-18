package com.houzhengbo.interview.data.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class BackupDto {
    @SerializedName("backupSchemaVersion")
    public int backupSchemaVersion;

    @SerializedName("appVersion")
    public String appVersion;

    @SerializedName("exportedAt")
    public long exportedAt;

    @SerializedName("resumes")
    public List<ResumeDto> resumes;

    @SerializedName("questions")
    public List<QuestionDto> questions;

    @SerializedName("attempts")
    public List<AttemptDto> attempts;

    @SerializedName("evaluations")
    public List<EvaluationDto> evaluations;

    @SerializedName("settings")
    public SettingsDto settings;

    public static class ResumeDto {
        @SerializedName("id")
        public int originalId;

        @SerializedName("fileName")
        public String fileName;

        @SerializedName("content")
        public String content;

        @SerializedName("importedAt")
        public long importedAt;
    }

    public static class QuestionDto {
        @SerializedName("id")
        public int originalId;

        @SerializedName("sourceType")
        public String sourceType;

        @SerializedName("category")
        public String category;

        @SerializedName("difficulty")
        public String difficulty;

        @SerializedName("questionText")
        public String questionText;

        @SerializedName("referenceAnswer")
        public String referenceAnswer;

        @SerializedName("keywords")
        public String keywords;

        @SerializedName("sourceUrl")
        public String sourceTypeUrl; // mappings

        @SerializedName("sourceDocumentPath")
        public String sourceDocumentPath;

        @SerializedName("sourceDocumentHash")
        public String sourceDocumentHash;

        @SerializedName("sourceRepository")
        public String sourceRepository;

        @SerializedName("sourceHeadingAnchor")
        public String sourceHeadingAnchor;

        @SerializedName("generationModel")
        public String generationModel;

        @SerializedName("generationVersion")
        public String generationVersion;

        @SerializedName("resumeId")
        public Integer resumeId;

        @SerializedName("favorite")
        public boolean favorite;

        @SerializedName("masteryLevel")
        public int masteryLevel;

        @SerializedName("attemptCount")
        public int attemptCount;

        @SerializedName("highestScore")
        public int highestScore;

        @SerializedName("lastScore")
        public int lastScore;

        @SerializedName("lastPracticedAt")
        public long lastPracticedAt;

        @SerializedName("nextReviewTime")
        public long nextReviewTime;

        @SerializedName("createdAt")
        public long createdAt;

        @SerializedName("updatedAt")
        public long updatedAt;
    }

    public static class AttemptDto {
        @SerializedName("id")
        public int originalId;

        @SerializedName("questionId")
        public int originalQuestionId;

        @SerializedName("userAnswer")
        public String userAnswer;

        @SerializedName("score")
        public Integer score;

        @SerializedName("status")
        public String status;

        @SerializedName("hitPoints")
        public String hitPoints;

        @SerializedName("missingPoints")
        public String missingPoints;

        @SerializedName("improvedAnswer")
        public String improvedAnswer;

        @SerializedName("followUpQuestion")
        public String followUpQuestion;

        @SerializedName("aiFeedback")
        public String aiFeedback;

        @SerializedName("attemptedAt")
        public long attemptedAt;
    }

    public static class EvaluationDto {
        @SerializedName("id")
        public int originalId;

        @SerializedName("attemptId")
        public int originalAttemptId;

        @SerializedName("missingPoints")
        public String missingPoints;

        @SerializedName("improvedAnswer")
        public String improvedAnswer;

        @SerializedName("followUpQuestion")
        public String followUpQuestion;

        @SerializedName("isPending")
        public boolean isPending;
    }

    public static class SettingsDto {
        @SerializedName("aiProvider")
        public String aiProvider;

        @SerializedName("aiBaseUrl")
        public String aiBaseUrl;

        @SerializedName("aiModel")
        public String aiModel;

        @SerializedName("reminderTime")
        public String reminderTime;

        @SerializedName("randomPopupEnabled")
        public boolean randomPopupEnabled;

        @SerializedName("wifiOnlySync")
        public boolean wifiOnlySync;
    }
}

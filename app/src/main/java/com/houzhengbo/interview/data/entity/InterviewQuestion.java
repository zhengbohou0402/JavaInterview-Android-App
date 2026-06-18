package com.houzhengbo.interview.data.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "interview_question", indices = {
    @Index(value = {"sourceRepository", "sourceDocumentPath", "sourceHeadingAnchor"}, unique = true),
    @Index(value = {"resumeId", "generationVersion", "questionText"}, unique = true)
})
public class InterviewQuestion {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String sourceType; // GUIDE, RESUME, FOLLOW_UP
    public String parentCategory; // 一级大类：Java基础, 数据库, 计算机网络, AI 等
    public String category;       // 二级小类：H2 标题，如 "HashMap源码分析"
    public String difficulty;
    public String questionText;
    public String referenceAnswer;
    public String keywords;
    public String sourceUrl;
    public String sourceDocumentPath;
    public String sourceDocumentHash;
    public String generationModel;
    public String generationVersion;
    public Integer resumeId;
    public boolean favorite;
    public int masteryLevel; // 0-100
    public int attemptCount;
    public int highestScore;
    public int lastScore;
    public long lastPracticedAt;
    public long nextReviewTime;
    public long createdAt;
    public long updatedAt;
    
    // New fields for AST parsing
    public String sourceRepository;
    public String sourceHeadingAnchor;
    public String referenceAnswerMarkdown;
    public String plainTextAnswer;
    public String sourceCommitSha;
    public String parserVersion;
    public boolean archived;

    // New fields for resume refactor
    public String resumeSection;
    public String evaluationPoints;
    public String followUpQuestions;
    public String questionType;
}

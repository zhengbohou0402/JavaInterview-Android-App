package com.houzhengbo.interview.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "practice_attempt")
public class PracticeAttempt {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public int questionId;
    public String userAnswer;
    public Integer score;
    public String status; // PENDING, SCORED, FAILED
    public String hitPoints;
    public String missingPoints;
    public String improvedAnswer;
    public String followUpQuestion;
    public String aiFeedback; // Kept for legacy/fallback
    public long attemptedAt;
}

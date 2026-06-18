package com.houzhengbo.interview.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "ai_evaluation")
public class AiEvaluation {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public int attemptId;
    public String missingPoints;
    public String improvedAnswer;
    public String followUpQuestion;
    public boolean isPending; // For offline scoring
}

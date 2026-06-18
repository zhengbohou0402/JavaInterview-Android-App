package com.houzhengbo.interview.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "resume_profile")
public class ResumeProfile {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String fileName;
    public String content;
    public long importedAt;
}

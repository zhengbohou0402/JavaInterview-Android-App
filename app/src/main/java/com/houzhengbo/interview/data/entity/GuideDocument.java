package com.houzhengbo.interview.data.entity;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "guide_document", indices = {@Index(value = {"sourceRepository", "path"}, unique = true)})
public class GuideDocument {
    @PrimaryKey(autoGenerate = true)
    public int id;
    public String title;
    public String category;
    public String content;
    public String originalUrl;
    public String path;
    public String contentHash;
    public long updatedAt;
    /** Repository name, e.g. "Snailclimb/JavaGuide" or "Snailclimb/AIGuide".
     *  Part of the unique identity (sourceRepository, path) so different repos
     *  can have documents at the same path without colliding. */
    public String sourceRepository;
}

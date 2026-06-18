package com.houzhengbo.interview.data.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_settings")
public class AppSettings {
    @PrimaryKey
    public int id = 1; // Single row
    public String aiProvider;
    public String aiBaseUrl;
    public String aiModel;
    public String reminderTime;
    public boolean randomPopupEnabled;
    public boolean wifiOnlySync;
    public long lastSyncTime;

    // Sync state machine: NOT_DOWNLOADED | DOWNLOADING | READY | PARTIAL | FAILED
    public String syncStatus = "NOT_DOWNLOADED";
    // Stats from the last sync run
    public int totalDownloaded;
    public int lastSyncSuccess;
    public int lastSyncSkip;
    public int lastSyncFail;
    // Parser version used during last sync (bump this when extraction logic changes)
    public String currentParserVersion;
}

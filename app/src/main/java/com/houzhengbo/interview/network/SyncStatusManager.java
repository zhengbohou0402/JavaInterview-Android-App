package com.houzhengbo.interview.network;

import android.content.Context;
import android.content.SharedPreferences;

import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.AppSettings;

/**
 * Centralized sync state manager.
 * Writes both to AppSettings (Room DB) and SharedPreferences so the
 * status is immediately readable without a DB query.
 */
public class SyncStatusManager {

    public static final String NOT_DOWNLOADED = "NOT_DOWNLOADED";
    public static final String DOWNLOADING    = "DOWNLOADING";
    public static final String READY          = "READY";
    public static final String PARTIAL        = "PARTIAL";
    public static final String FAILED         = "FAILED";

    public static final String PREFS_NAME       = "sync_prefs";
    public static final String KEY_STATUS       = "sync_status";
    public static final String KEY_LAST_TIME    = "last_sync_time";
    public static final String KEY_TOTAL_DL     = "total_downloaded";
    public static final String KEY_SUCCESS      = "last_success";
    public static final String KEY_SKIP         = "last_skip";
    public static final String KEY_FAIL         = "last_fail";
    public static final String KEY_PARSER_VER   = "parser_version";
    /** JSON cache of the last GitHub tree API response per repo, keyed by repo name */
    public static final String KEY_TREE_CACHE_PREFIX = "tree_cache_";
    /** ETag cache for tree API, keyed by repo name */
    public static final String KEY_ETAG_PREFIX   = "tree_etag_";
    /** Failed file paths for retry, stored as JSON array string */
    public static final String KEY_FAILED_PATHS  = "failed_paths_json";

    /** Quick read from SharedPreferences — safe to call from main thread */
    public static String getStatus(Context ctx) {
        return prefs(ctx).getString(KEY_STATUS, NOT_DOWNLOADED);
    }

    public static long getLastSyncTime(Context ctx) {
        return prefs(ctx).getLong(KEY_LAST_TIME, 0);
    }

    public static int getTotalDownloaded(Context ctx) {
        return prefs(ctx).getInt(KEY_TOTAL_DL, 0);
    }

    /** Write status and stats to both SharedPreferences and DB (must run on background thread). */
    public static void updateStatus(Context ctx, AppDatabase db,
                                    String status, int totalDownloaded,
                                    int success, int skip, int fail,
                                    String parserVersion) {
        long now = System.currentTimeMillis();

        // 1. SharedPreferences (instant, for UI)
        prefs(ctx).edit()
                .putString(KEY_STATUS, status)
                .putLong(KEY_LAST_TIME, now)
                .putInt(KEY_TOTAL_DL, totalDownloaded)
                .putInt(KEY_SUCCESS, success)
                .putInt(KEY_SKIP, skip)
                .putInt(KEY_FAIL, fail)
                .putString(KEY_PARSER_VER, parserVersion)
                .apply();

        // 2. Room DB (for backup/restore and migration awareness)
        AppSettings s = db.interviewDao().getSettings();
        if (s == null) s = new AppSettings();
        s.syncStatus          = status;
        s.lastSyncTime        = now;
        s.totalDownloaded     = totalDownloaded;
        s.lastSyncSuccess     = success;
        s.lastSyncSkip        = skip;
        s.lastSyncFail        = fail;
        s.currentParserVersion = parserVersion;
        db.interviewDao().upsertSettings(s);
    }

    /** Mark sync as started — writes DOWNLOADING state immediately */
    public static void markDownloading(Context ctx, AppDatabase db) {
        prefs(ctx).edit().putString(KEY_STATUS, DOWNLOADING).apply();
        AppSettings s = db.interviewDao().getSettings();
        if (s == null) s = new AppSettings();
        s.syncStatus = DOWNLOADING;
        db.interviewDao().upsertSettings(s);
    }

    /** Cache tree API JSON response + ETag for a given repo */
    public static void cacheTree(Context ctx, String repo, String json, String etag) {
        prefs(ctx).edit()
                .putString(KEY_TREE_CACHE_PREFIX + repo, json)
                .putString(KEY_ETAG_PREFIX + repo, etag != null ? etag : "")
                .apply();
    }

    public static String getCachedTree(Context ctx, String repo) {
        return prefs(ctx).getString(KEY_TREE_CACHE_PREFIX + repo, null);
    }

    public static String getCachedEtag(Context ctx, String repo) {
        return prefs(ctx).getString(KEY_ETAG_PREFIX + repo, null);
    }

    /** Persist failed file paths for retry */
    public static void saveFailedPaths(Context ctx, String jsonArrayString) {
        prefs(ctx).edit().putString(KEY_FAILED_PATHS, jsonArrayString).apply();
    }

    public static String getFailedPaths(Context ctx) {
        return prefs(ctx).getString(KEY_FAILED_PATHS, "[]");
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}

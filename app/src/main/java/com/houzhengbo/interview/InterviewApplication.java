package com.houzhengbo.interview;

import android.app.Application;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.CustomQuestionBootstrap;
import com.houzhengbo.interview.network.GithubRepoSyncWorker;
import com.houzhengbo.interview.network.SyncStatusManager;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class InterviewApplication extends Application {
    private static InterviewApplication instance;
    private static final Executor appExecutor = Executors.newFixedThreadPool(2);
    private AppDatabase database;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // 最早应用主题（night mode），避免 Activity 重建时闪屏
        com.houzhengbo.interview.utils.ThemeManager.apply(this);
        database = AppDatabase.getDatabase(this);

        // 首次启动时从 assets 导入简历定制面试题（后台线程）
        appExecutor.execute(() -> CustomQuestionBootstrap.bootstrapIfNeeded(this, database));

        // Only register the periodic 7-day background check if the user has
        // already performed at least one manual download.  The very first sync
        // must be triggered explicitly by the user (see HomeFragment onboarding).
        schedulePeriodicSyncIfReady();
    }

    /**
     * Registers the 7-day periodic WorkManager job, but only when the library
     * has been downloaded before.  This prevents the background job from running
     * before the user agrees to the first download.
     *
     * Call this again from the UI after a successful first-time sync.
     */
    public void schedulePeriodicSyncIfReady() {
        String status = SyncStatusManager.getStatus(this);
        if (SyncStatusManager.NOT_DOWNLOADED.equals(status)) {
            // Not yet downloaded – do not register the periodic job.
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest syncRequest = new PeriodicWorkRequest.Builder(
                GithubRepoSyncWorker.class, 7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .addTag(GithubRepoSyncWorker.TAG_SYNC)
                .build();

        // KEEP: do not reset the 7-day clock if a job is already scheduled.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "GithubSync7Day",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest);
    }

    public static InterviewApplication getInstance() {
        return instance;
    }

    public AppDatabase getDatabase() {
        return database;
    }

    public Executor getAppExecutor() {
        return appExecutor;
    }
}

package com.houzhengbo.interview.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Enqueue ShowReminderWorker to query DB and show notification
        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(ShowReminderWorker.class).build();
        WorkManager.getInstance(context.getApplicationContext()).enqueue(workRequest);
    }
}

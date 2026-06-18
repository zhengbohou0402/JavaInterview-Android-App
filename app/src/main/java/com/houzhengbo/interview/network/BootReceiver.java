package com.houzhengbo.interview.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.AppSettings;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            final Context appContext = context.getApplicationContext();
            new Thread(() -> {
                try {
                    AppDatabase db = AppDatabase.getDatabase(appContext);
                    AppSettings settings = db.interviewDao().getSettings();
                    if (settings != null && settings.randomPopupEnabled) {
                        String timeStr = settings.reminderTime;
                        if (timeStr != null && timeStr.contains(":")) {
                            String[] parts = timeStr.split(":");
                            int hour = Integer.parseInt(parts[0].trim());
                            int minute = Integer.parseInt(parts[1].trim());
                            scheduleAlarm(appContext, hour, minute);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private void scheduleAlarm(Context context, int hour, int minute) {
        ReminderScheduler.scheduleAlarm(context, hour, minute);
    }
}

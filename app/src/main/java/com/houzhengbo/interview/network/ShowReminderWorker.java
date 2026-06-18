package com.houzhengbo.interview.network;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.AppSettings;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.ui.practice.PracticeActivity;

public class ShowReminderWorker extends Worker {

    private static final String CHANNEL_ID = "daily_reminder_channel";

    public ShowReminderWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            AppDatabase db = InterviewApplication.getInstance().getDatabase();
            long now = System.currentTimeMillis();
            
            AppSettings settings = db.interviewDao().getSettings();
            if (settings != null && settings.randomPopupEnabled) {
                // Reschedule the next alarm for tomorrow
                String timeStr = settings.reminderTime;
                if (timeStr != null && timeStr.contains(":")) {
                    try {
                        String[] parts = timeStr.split(":");
                        int hour = Integer.parseInt(parts[0].trim());
                        int minute = Integer.parseInt(parts[1].trim());
                        rescheduleAlarm(getApplicationContext(), hour, minute);
                    } catch (Exception parseEx) {
                        parseEx.printStackTrace();
                    }
                }
                
                // Fetch next review question first
                InterviewQuestion target = db.interviewDao().getNextReviewQuestion(now);
                
                // Fallback to a random question if no review question is available
                if (target == null) {
                    target = db.interviewDao().getRandomQuestion();
                }

                if (target != null) {
                    showNotification(getApplicationContext(), target);
                }
            }

            return Result.success();
        } catch (Exception e) {
            e.printStackTrace();
            return Result.failure();
        }
    }

    private void rescheduleAlarm(Context context, int hour, int minute) {
        ReminderScheduler.scheduleAlarm(context, hour, minute);
    }

    private void showNotification(Context context, InterviewQuestion question) {
        android.app.NotificationManager notificationManager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(CHANNEL_ID, "每日学习提醒", android.app.NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel(channel);
        }

        Intent practiceIntent = new Intent(context, PracticeActivity.class);
        practiceIntent.putExtra("QUESTION_ID", question.id);
        practiceIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                context,
                question.id,
                practiceIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
        );

        androidx.core.app.NotificationCompat.Builder builder = new androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("每日面试推荐题")
                .setContentText(question.questionText)
                .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(question.questionText))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        notificationManager.notify(question.id, builder.build());
    }
}

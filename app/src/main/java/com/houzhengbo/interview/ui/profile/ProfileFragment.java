package com.houzhengbo.interview.ui.profile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.AppSettings;
import com.houzhengbo.interview.data.entity.ResumeProfile;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.data.entity.PracticeAttempt;
import com.houzhengbo.interview.data.dto.BackupDto;
import com.houzhengbo.interview.network.GithubRepoSyncWorker;
import com.houzhengbo.interview.utils.DbExecutor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class ProfileFragment extends Fragment {

    private TextInputEditText etBaseUrl, etModel, etApiKey;
    private MaterialSwitch switchWifiOnly;
    private android.widget.RadioGroup rgTheme;
    private Button btnSaveAiSettings, btnSyncJavaGuide, btnImportResume, btnDeleteResume, btnExportData, btnImportData, btnOpenLicenses;
    private TextView tvSyncStatus, tvResumeStatus;

    // ── Library management UI (new) ──────────────────────────────────────────
    private TextView tvSyncStatusValue, tvDownloadedCount, tvLastSyncTime, tvLastSyncResult, tvParserVersion;
    private View layoutSyncProgress;
    private android.widget.ProgressBar progressBarSync;
    private TextView tvSyncPhase;
    private Button btnCancelSync, btnRetryFailed, btnClearLibrary;

    // Daily Reminder Views
    private MaterialSwitch switchDailyReminder;
    private LinearLayout layoutReminderTime;
    private TextView tvReminderTime;

    private AppDatabase db;
    private AppSettings currentSettings;

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        importResume(uri);
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        exportData(uri);
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> importDataLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        importData(uri);
                    }
                }
            }
    );

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    saveReminderSettings(true, tvReminderTime.getText().toString());
                } else {
                    Toast.makeText(requireContext(), "需要通知权限以发送每日弹题提醒", Toast.LENGTH_SHORT).show();
                    // Disable switch without listener trigger
                    switchDailyReminder.setOnCheckedChangeListener(null);
                    switchDailyReminder.setChecked(false);
                    switchDailyReminder.setOnCheckedChangeListener(this::onReminderCheckedChanged);
                    saveReminderSettings(false, tvReminderTime.getText().toString());
                }
            }
    );

    private void onReminderCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
            // Check notification permission for Android 13+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                    return;
                }
            }
            saveReminderSettings(true, tvReminderTime.getText().toString());
        } else {
            saveReminderSettings(false, tvReminderTime.getText().toString());
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_profile, container, false);
        
        db = InterviewApplication.getInstance().getDatabase();

        etBaseUrl = view.findViewById(R.id.et_ai_base_url);
        etModel = view.findViewById(R.id.et_ai_model);
        etApiKey = view.findViewById(R.id.et_ai_api_key);
        switchWifiOnly = view.findViewById(R.id.switch_wifi_only);

        // 主题切换 RadioGroup：读取当前模式选中对应项，切换时持久化 + 立即应用 + 重建 Activity
        rgTheme = view.findViewById(R.id.rg_theme);
        String currentTheme = com.houzhengbo.interview.utils.ThemeManager.getMode(requireContext());
        int checkedId;
        switch (currentTheme) {
            case com.houzhengbo.interview.utils.ThemeManager.MODE_LIGHT:
                checkedId = R.id.rb_theme_light; break;
            case com.houzhengbo.interview.utils.ThemeManager.MODE_DARK:
                checkedId = R.id.rb_theme_dark; break;
            case com.houzhengbo.interview.utils.ThemeManager.MODE_ORANGE:
                checkedId = R.id.rb_theme_orange; break;
            default:
                checkedId = R.id.rb_theme_system; break;
        }
        rgTheme.check(checkedId);
        rgTheme.setOnCheckedChangeListener((group, checkedId1) -> {
            String mode;
            if (checkedId1 == R.id.rb_theme_light) {
                mode = com.houzhengbo.interview.utils.ThemeManager.MODE_LIGHT;
            } else if (checkedId1 == R.id.rb_theme_dark) {
                mode = com.houzhengbo.interview.utils.ThemeManager.MODE_DARK;
            } else if (checkedId1 == R.id.rb_theme_orange) {
                mode = com.houzhengbo.interview.utils.ThemeManager.MODE_ORANGE;
            } else {
                mode = com.houzhengbo.interview.utils.ThemeManager.MODE_SYSTEM;
            }
            String previousMode = com.houzhengbo.interview.utils.ThemeManager.getMode(requireContext());
            if (mode.equals(previousMode)) {
                return;
            }
            com.houzhengbo.interview.utils.ThemeManager.setMode(requireContext(), mode);
            com.houzhengbo.interview.utils.ThemeManager.apply(requireContext());
            // Recreate after the preference is committed so MainActivity reads the new style.
            if (isAdded() && getActivity() != null) {
                getActivity().getWindow().getDecorView().post(() -> {
                    if (isAdded() && getActivity() != null) {
                        getActivity().recreate();
                    }
                });
            }
        });
        
        btnSaveAiSettings = view.findViewById(R.id.btn_save_ai_settings);
        btnSyncJavaGuide = view.findViewById(R.id.btn_sync_javaguide);
        btnImportResume = view.findViewById(R.id.btn_import_resume);
        btnDeleteResume = view.findViewById(R.id.btn_delete_resume);
        btnExportData = view.findViewById(R.id.btn_export_data);
        btnImportData = view.findViewById(R.id.btn_import_data);
        btnOpenLicenses = view.findViewById(R.id.btn_open_licenses);

        // Legacy (hidden) – kept for any downstream refs
        tvSyncStatus = view.findViewById(R.id.tv_sync_status);
        tvResumeStatus = view.findViewById(R.id.tv_resume_status);

        // New library management widgets
        tvSyncStatusValue  = view.findViewById(R.id.tv_sync_status_value);
        tvDownloadedCount  = view.findViewById(R.id.tv_downloaded_count);
        tvLastSyncTime     = view.findViewById(R.id.tv_last_sync_time);
        tvLastSyncResult   = view.findViewById(R.id.tv_last_sync_result);
        tvParserVersion    = view.findViewById(R.id.tv_parser_version);
        layoutSyncProgress = view.findViewById(R.id.layout_sync_progress);
        progressBarSync    = view.findViewById(R.id.progress_bar_sync);
        tvSyncPhase        = view.findViewById(R.id.tv_sync_phase);
        btnCancelSync      = view.findViewById(R.id.btn_cancel_sync);
        btnRetryFailed     = view.findViewById(R.id.btn_retry_failed);
        btnClearLibrary    = view.findViewById(R.id.btn_clear_library);

        // Daily Reminder bindings
        switchDailyReminder = view.findViewById(R.id.switch_daily_reminder);
        layoutReminderTime = view.findViewById(R.id.layout_reminder_time);
        tvReminderTime = view.findViewById(R.id.tv_reminder_time);

        loadSettings();
        observeSyncWork();

        btnSaveAiSettings.setOnClickListener(v -> saveSettings());

        // ── Sync button handlers ─────────────────────────────────────────────
        btnSyncJavaGuide.setOnClickListener(v -> {
            DbExecutor.runOnDbThenUi(ProfileFragment.this,
                    () -> {
                        AppSettings settings = db.interviewDao().getSettings();
                        boolean wifiOnly = settings != null && settings.wifiOnlySync;
                        return wifiOnly && !isWifiConnected(requireContext().getApplicationContext());
                    },
                    new DbExecutor.UiCallback<Boolean>() {
                        @Override public void onResult(Boolean wifiBlock) {
                            if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                            if (Boolean.TRUE.equals(wifiBlock)) {
                                Toast.makeText(requireContext(),
                                        "当前非 Wi-Fi 环境，且已开启仅在 Wi-Fi 下同步",
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(GithubRepoSyncWorker.class)
                                    .addTag(GithubRepoSyncWorker.TAG_SYNC)
                                    .build();
                            WorkManager.getInstance(requireContext()).enqueue(req);
                            Toast.makeText(requireContext(), "同步任务已加入队列", Toast.LENGTH_SHORT).show();
                            setSyncingUi(true);
                        }
                    });
        });

        btnCancelSync.setOnClickListener(v -> {
            WorkManager.getInstance(requireContext()).cancelAllWorkByTag(GithubRepoSyncWorker.TAG_SYNC);
            Toast.makeText(requireContext(), "已发送取消请求", Toast.LENGTH_SHORT).show();
            setSyncingUi(false);
        });

        btnRetryFailed.setOnClickListener(v -> {
            String failedJson = com.houzhengbo.interview.network.SyncStatusManager
                    .getFailedPaths(requireContext());
            if ("[]".equals(failedJson)) {
                Toast.makeText(requireContext(), "没有失败的文件需要重试", Toast.LENGTH_SHORT).show();
                return;
            }
            androidx.work.Data inputData = new androidx.work.Data.Builder()
                    .putString(GithubRepoSyncWorker.INPUT_RETRY_PATHS, failedJson)
                    .build();
            OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(GithubRepoSyncWorker.class)
                    .setInputData(inputData)
                    .addTag(GithubRepoSyncWorker.TAG_SYNC)
                    .build();
            WorkManager.getInstance(requireContext()).enqueue(req);
            Toast.makeText(requireContext(), "重试任务已加入队列", Toast.LENGTH_SHORT).show();
            setSyncingUi(true);
        });

        btnClearLibrary.setOnClickListener(v -> showClearLibraryDialog());

        btnImportResume.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });

        btnDeleteResume.setOnClickListener(v -> {
            DbExecutor.runOnDbThenUi(ProfileFragment.this,
                    () -> db.interviewDao().getLatestResume(),
                    latestResume -> {
                        if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                        if (latestResume == null) {
                            Toast.makeText(requireContext(), "没有可删除的简历", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new android.app.AlertDialog.Builder(requireContext())
                            .setTitle("删除简历")
                            .setMessage("确定要删除当前简历吗？是否同时删除该简历关联的面试题？\n(注意：删除题目不会影响已有的历史作答记录)")
                            .setPositiveButton("删除简历及关联题目", (dialog, which) -> {
                                DbExecutor.runOnDbThenUi(ProfileFragment.this,
                                        () -> {
                                            db.runInTransaction(() -> {
                                                db.interviewDao().deleteResumeById(latestResume.id);
                                                db.interviewDao().deleteQuestionsByResumeId(latestResume.id);
                                            });
                                            return null;
                                        },
                                        nil -> loadSettings());
                            })
                            .setNeutralButton("仅删除简历", (dialog, which) -> {
                                DbExecutor.runOnDbThenUi(ProfileFragment.this,
                                        () -> {
                                            db.interviewDao().deleteResumeById(latestResume.id);
                                            return null;
                                        },
                                        nil -> loadSettings());
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    });
        });

        btnExportData.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "interview_backup.json");
            exportLauncher.launch(intent);
        });

        btnImportData.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            importDataLauncher.launch(intent);
        });

        btnOpenLicenses.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), com.houzhengbo.interview.ui.profile.LicensesActivity.class);
            startActivity(intent);
        });

        // Set layout click listener for time picker
        layoutReminderTime.setOnClickListener(v -> {
            String timeStr = tvReminderTime.getText().toString();
            int hour = 9;
            int minute = 0;
            if (timeStr.contains(":")) {
                String[] parts = timeStr.split(":");
                try {
                    hour = Integer.parseInt(parts[0].trim());
                    minute = Integer.parseInt(parts[1].trim());
                } catch (Exception e) {}
            }

            new android.app.TimePickerDialog(requireContext(), (timePickerView, hourOfDay, minuteOfHour) -> {
                String newTime = String.format(java.util.Locale.getDefault(), "%02d:%02d", hourOfDay, minuteOfHour);
                tvReminderTime.setText(newTime);
                saveReminderSettings(switchDailyReminder.isChecked(), newTime);
            }, hour, minute, true).show();
        });

        switchDailyReminder.setOnCheckedChangeListener(this::onReminderCheckedChanged);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        loadSettings();
    }

    private void loadSettings() {
        final android.content.Context appCtx = getContext() != null ? requireContext().getApplicationContext() : null;
        if (appCtx == null) return;
        DbExecutor.runOnDbThenUi(ProfileFragment.this,
                () -> {
                    currentSettings = db.interviewDao().getSettings();
                    if (currentSettings == null) {
                        currentSettings = new AppSettings();
                    }
                    ResumeProfile latestResume = db.interviewDao().getLatestResume();
                    String plainKey = com.houzhengbo.interview.utils.KeystoreHelper.getApiKey(appCtx);
                    String syncStatus    = com.houzhengbo.interview.network.SyncStatusManager.getStatus(appCtx);
                    int totalDownloaded  = com.houzhengbo.interview.network.SyncStatusManager.getTotalDownloaded(appCtx);
                    long lastSyncTime    = com.houzhengbo.interview.network.SyncStatusManager.getLastSyncTime(appCtx);
                    int lastSuccess      = appCtx.getSharedPreferences(
                            com.houzhengbo.interview.network.SyncStatusManager.PREFS_NAME,
                            android.content.Context.MODE_PRIVATE)
                            .getInt(com.houzhengbo.interview.network.SyncStatusManager.KEY_SUCCESS, 0);
                    int lastSkip = appCtx.getSharedPreferences(
                            com.houzhengbo.interview.network.SyncStatusManager.PREFS_NAME,
                            android.content.Context.MODE_PRIVATE)
                            .getInt(com.houzhengbo.interview.network.SyncStatusManager.KEY_SKIP, 0);
                    int lastFail = appCtx.getSharedPreferences(
                            com.houzhengbo.interview.network.SyncStatusManager.PREFS_NAME,
                            android.content.Context.MODE_PRIVATE)
                            .getInt(com.houzhengbo.interview.network.SyncStatusManager.KEY_FAIL, 0);
                    String parserVer = appCtx.getSharedPreferences(
                            com.houzhengbo.interview.network.SyncStatusManager.PREFS_NAME,
                            android.content.Context.MODE_PRIVATE)
                            .getString(com.houzhengbo.interview.network.SyncStatusManager.KEY_PARSER_VER, "—");
                    String failedPaths = com.houzhengbo.interview.network.SyncStatusManager.getFailedPaths(appCtx);
                    boolean hasFailedPaths = failedPaths != null && !"[]".equals(failedPaths);

                    String lastSyncStr = lastSyncTime > 0
                            ? new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                    .format(new java.util.Date(lastSyncTime))
                            : "从未";
                    String syncStatusLabel = translateStatus(syncStatus);
                    String lastResultStr = lastSyncTime > 0
                            ? "成功 " + lastSuccess + "  跳过 " + lastSkip + "  失败 " + lastFail
                            : "—";

                    return new Object[]{
                            currentSettings, latestResume, plainKey != null ? plainKey : "",
                            syncStatusLabel, totalDownloaded, lastSyncStr,
                            lastResultStr, parserVer, hasFailedPaths
                    };
                },
                arr -> {
                    if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                    if (!isAdded() || getView() == null) return;
                    if (!(arr instanceof Object[])) return;
                    Object[] data = (Object[]) arr;
                    AppSettings s = (AppSettings) data[0];
                    ResumeProfile latestResume = (ResumeProfile) data[1];
                    String plainKey = (String) data[2];
                    String syncStatusLabel = (String) data[3];
                    int totalDownloaded = (Integer) data[4];
                    String lastSyncStr = (String) data[5];
                    String lastResultStr = (String) data[6];
                    String parserVer = (String) data[7];
                    boolean hasFailedPaths = (Boolean) data[8];

                    etBaseUrl.setText(s.aiBaseUrl);
                    etModel.setText(s.aiModel);
                    etApiKey.setText(plainKey);
                    switchWifiOnly.setChecked(s.wifiOnlySync);

                    tvSyncStatusValue.setText(syncStatusLabel);
                    tvDownloadedCount.setText(totalDownloaded + " 道");
                    tvLastSyncTime.setText(lastSyncStr);
                    tvLastSyncResult.setText(lastResultStr);
                    tvParserVersion.setText(parserVer);
                    btnRetryFailed.setEnabled(hasFailedPaths);

                    if (latestResume != null) {
                        tvResumeStatus.setText("当前已导入简历: " + latestResume.fileName);
                    } else {
                        tvResumeStatus.setText("当前未导入简历");
                    }

                    switchDailyReminder.setOnCheckedChangeListener(null);
                    switchDailyReminder.setChecked(s.randomPopupEnabled);
                    switchDailyReminder.setOnCheckedChangeListener(this::onReminderCheckedChanged);
                    tvReminderTime.setText(s.reminderTime != null ? s.reminderTime : "09:00");
                });
    }

    private String translateStatus(String status) {
        if (status == null) return "未知";
        switch (status) {
            case "NOT_DOWNLOADED": return "尚未下载";
            case "DOWNLOADING":    return "同步中…";
            case "READY":          return "已就绪✔️";
            case "PARTIAL":        return "部分完成";
            case "FAILED":         return "同步失败";
            default:               return status;
        }
    }

    /** Observe WorkManager to update the progress bar in real-time. */
    private void observeSyncWork() {
        WorkManager.getInstance(requireContext())
                .getWorkInfosByTagLiveData(GithubRepoSyncWorker.TAG_SYNC)
                .observe(getViewLifecycleOwner(), workInfoList -> {
                    if (workInfoList == null || workInfoList.isEmpty()) return;
                    for (androidx.work.WorkInfo info : workInfoList) {
                        if (info.getState() == androidx.work.WorkInfo.State.RUNNING) {
                            int cur   = info.getProgress().getInt(GithubRepoSyncWorker.PROGRESS_CURRENT, 0);
                            int total = info.getProgress().getInt(GithubRepoSyncWorker.PROGRESS_TOTAL, 0);
                            String phase = info.getProgress().getString(GithubRepoSyncWorker.PROGRESS_PHASE);
                            layoutSyncProgress.setVisibility(View.VISIBLE);
                            tvSyncPhase.setText(phase != null ? phase : "同步中…");
                            if (total > 0) {
                                progressBarSync.setIndeterminate(false);
                                progressBarSync.setMax(total);
                                progressBarSync.setProgress(cur);
                            } else {
                                progressBarSync.setIndeterminate(true);
                            }
                            setSyncingUi(true);
                            return;
                        } else if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED
                                || info.getState() == androidx.work.WorkInfo.State.FAILED
                                || info.getState() == androidx.work.WorkInfo.State.CANCELLED) {
                            layoutSyncProgress.setVisibility(View.GONE);
                            setSyncingUi(false);
                            loadSettings(); // Refresh stats
                            if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED) {
                                InterviewApplication.getInstance().schedulePeriodicSyncIfReady();
                            }
                            return;
                        }
                    }
                });
    }

    private void setSyncingUi(boolean syncing) {
        if (!isAdded()) return;
        btnSyncJavaGuide.setEnabled(!syncing);
        btnCancelSync.setEnabled(syncing);
        btnRetryFailed.setEnabled(!syncing);
        if (!syncing) layoutSyncProgress.setVisibility(View.GONE);
    }

    private void showClearLibraryDialog() {
        new android.app.AlertDialog.Builder(requireContext())
                .setTitle("清理题库")
                .setMessage("将删除所有从 JavaGuide/AIGuide 同步的题目和文档缓存。\n\n" +
                        "✅ 保留：简历题目、全部练习历史\n" +
                        "❌ 删除： JavaGuide/AIGuide 题目和文档缓存\n\n" +
                        "确定要清理吗？")
                .setPositiveButton("确认清理", (d, w) -> {
                    final android.content.Context appCtx = requireContext().getApplicationContext();
                    DbExecutor.runOnDbThenUi(ProfileFragment.this,
                            () -> {
                                db.runInTransaction(() -> {
                                    db.interviewDao().deleteGuideQuestions();
                                    db.interviewDao().deleteAllGuideDocuments();
                                });
                                com.houzhengbo.interview.network.SyncStatusManager.updateStatus(
                                        appCtx, db,
                                        com.houzhengbo.interview.network.SyncStatusManager.NOT_DOWNLOADED,
                                        0, 0, 0, 0, null);
                                com.houzhengbo.interview.network.SyncStatusManager.saveFailedPaths(appCtx, "[]");
                                return null;
                            },
                            nil -> {
                                if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                                Toast.makeText(requireContext(), "题库已清理，练习历史完整保留",
                                        Toast.LENGTH_SHORT).show();
                                loadSettings();
                            });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveSettings() {
        if (currentSettings == null) currentSettings = new AppSettings();
        currentSettings.aiBaseUrl = etBaseUrl.getText() != null ? etBaseUrl.getText().toString() : "";
        currentSettings.aiModel = etModel.getText() != null ? etModel.getText().toString() : "";
        currentSettings.wifiOnlySync = switchWifiOnly.isChecked();

        String inputKey = etApiKey.getText() != null ? etApiKey.getText().toString() : "";
        try {
            com.houzhengbo.interview.utils.KeystoreHelper.saveApiKey(requireContext(), inputKey);
        } catch (Exception e) {
            e.printStackTrace();
        }

        DbExecutor.runOnDbThenUi(ProfileFragment.this,
                () -> {
                    AppSettings existing = db.interviewDao().getSettings();
                    if (existing == null) {
                        db.interviewDao().insertSettings(currentSettings);
                    } else {
                        db.interviewDao().updateSettings(currentSettings);
                    }
                    return null;
                },
                nil -> {
                    if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                    if (!isAdded() || getView() == null) return;
                    Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
                });
    }

    private void saveReminderSettings(boolean enabled, String timeStr) {
        final android.content.Context appContext = requireContext().getApplicationContext();
        DbExecutor.runOnDbThenUi(ProfileFragment.this,
                () -> {
                    boolean success = true;
                    if (enabled) {
                        if (timeStr != null && timeStr.contains(":")) {
                            try {
                                String[] parts = timeStr.split(":");
                                int hour = Integer.parseInt(parts[0].trim());
                                int minute = Integer.parseInt(parts[1].trim());
                                com.houzhengbo.interview.network.ReminderScheduler.scheduleAlarm(appContext, hour, minute);
                            } catch (Exception ex) {
                                ex.printStackTrace();
                                success = false;
                            }
                        } else {
                            success = false;
                        }
                    } else {
                        try {
                            com.houzhengbo.interview.network.ReminderScheduler.cancelAlarm(appContext);
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }

                    if (success) {
                        AppSettings settings = db.interviewDao().getSettings();
                        if (settings == null) {
                            settings = new AppSettings();
                        }
                        settings.randomPopupEnabled = enabled;
                        settings.reminderTime = timeStr;

                        AppSettings existing = db.interviewDao().getSettings();
                        if (existing == null) {
                            db.interviewDao().insertSettings(settings);
                        } else {
                            db.interviewDao().updateSettings(settings);
                        }
                    }
                    return success;
                },
                finalSuccess -> {
                    if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                    if (!isAdded() || getView() == null) return;
                    Boolean ok = (Boolean) finalSuccess;
                    if (Boolean.TRUE.equals(ok)) {
                        Toast.makeText(requireContext(), enabled ? "每日提醒已开启" : "每日提醒已关闭", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), "设置提醒失败", Toast.LENGTH_SHORT).show();
                        switchDailyReminder.setOnCheckedChangeListener(null);
                        switchDailyReminder.setChecked(!enabled);
                        switchDailyReminder.setOnCheckedChangeListener(this::onReminderCheckedChanged);
                    }
                });
    }

    private void importResume(Uri uri) {
        new android.app.AlertDialog.Builder(requireContext())
            .setTitle("个人隐私提示")
            .setMessage("导入简历会将您的简历内容发送给配置的第三方 AI，用于为您生成专属面试题目。发送前请确认简历中不包含敏感个人隐私信息（如电话、邮箱、家庭地址），您是否同意上传？")
            .setPositiveButton("同意并上传", (dialog, which) -> {
                if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                tvResumeStatus.setText("正在通过 AI 生成简历面试题，请稍候...");
                DbExecutor.runOnDbThenUi(ProfileFragment.this,
                        () -> {
                            performImportResume(uri);
                            return null;
                        },
                        nil -> {
                            if (!isAdded() || getView() == null) return;
                            /* performImportResume handles its own UI updates */
                        });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void performImportResume(Uri uri) {
        final android.content.Context appCtx = requireContext().getApplicationContext();
        try {
            // Validation: Size
            android.database.Cursor cursor = appCtx.getContentResolver().query(uri, null, null, null, null);
            long size = 0;
            String displayName = "resume.md";
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex);
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) displayName = cursor.getString(nameIndex);
                cursor.close();
            }

            final String finalDisplayName = displayName;
            if (size > 10 * 1024 * 1024) { // 10MB limit
                if (DbExecutor.isFragmentSafe(ProfileFragment.this)) {
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded() || getView() == null) return;
                        Toast.makeText(appCtx, "文件过大，请选择小于 10MB 的文件", Toast.LENGTH_SHORT).show();
                    });
                }
                return;
            }
            if (!finalDisplayName.toLowerCase().endsWith(".txt") && !finalDisplayName.toLowerCase().endsWith(".md")) {
                if (DbExecutor.isFragmentSafe(ProfileFragment.this)) {
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded() || getView() == null) return;
                        Toast.makeText(appCtx, "仅支持 .txt 或 .md 格式", Toast.LENGTH_SHORT).show();
                    });
                }
                return;
            }

            InputStream inputStream = appCtx.getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
            inputStream.close();

            String content = stringBuilder.toString().trim();
            if (content.isEmpty()) {
                if (DbExecutor.isFragmentSafe(ProfileFragment.this)) {
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded() || getView() == null) return;
                        Toast.makeText(appCtx, "文件内容为空", Toast.LENGTH_SHORT).show();
                    });
                }
                return;
            }

            ResumeProfile profile = new ResumeProfile();
            profile.fileName = finalDisplayName;
            profile.content = content;
            profile.importedAt = System.currentTimeMillis();

            // Generate questions
            AppSettings settings = db.interviewDao().getSettings();
            okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
            com.houzhengbo.interview.network.AiClient aiClient = new com.houzhengbo.interview.network.AiClient(httpClient, new com.houzhengbo.interview.network.ApiKeyProvider() {
                @Override
                public String getApiKey() {
                    return com.houzhengbo.interview.utils.KeystoreHelper.getApiKey(appCtx);
                }
            });
            String jsonResponse = aiClient.generateQuestions(settings, profile.content);

            // STRICT split parser call!
            com.google.gson.JsonObject jsonObject = com.houzhengbo.interview.network.AiResponseParser.parseQuestionGenerationResponse(jsonResponse);

            com.google.gson.JsonArray questions = jsonObject.has("questions") ? jsonObject.getAsJsonArray("questions") : new com.google.gson.JsonArray();

            int validQuestionCount = 0;
            for (com.google.gson.JsonElement qElem : questions) {
                if (qElem.isJsonObject()) {
                    com.google.gson.JsonObject qObj = qElem.getAsJsonObject();
                    String qText = qObj.has("question") ? qObj.get("question").getAsString() : "";
                    if (!qText.trim().isEmpty()) {
                        validQuestionCount++;
                    }
                }
            }

            if (validQuestionCount == 0) {
                throw new Exception("AI 未能成功生成至少一道有效的面试题");
            }

            db.runInTransaction(() -> {
                // Clear previous resumes and questions to keep only one
                ResumeProfile existingResume = db.interviewDao().getLatestResume();
                if (existingResume != null) {
                    db.interviewDao().deleteResumeById(existingResume.id);
                    db.interviewDao().deleteQuestionsByResumeId(existingResume.id);
                }

                long resumeId = db.interviewDao().insertResume(profile);
                for (com.google.gson.JsonElement qElem : questions) {
                    com.google.gson.JsonObject qObj = qElem.getAsJsonObject();
                    com.houzhengbo.interview.data.entity.InterviewQuestion q = new com.houzhengbo.interview.data.entity.InterviewQuestion();
                    q.sourceType = "RESUME";
                    q.category = "简历专项";
                    q.difficulty = qObj.has("difficulty") ? qObj.get("difficulty").getAsString() : "Medium";
                    q.questionText = qObj.has("question") ? qObj.get("question").getAsString() : "";
                    q.referenceAnswer = qObj.has("referenceAnswer") ? qObj.get("referenceAnswer").getAsString() : "";
                    q.keywords = qObj.has("keywords") ? qObj.get("keywords").getAsString() : "";
                    q.resumeId = (int) resumeId;
                    q.generationModel = settings.aiModel != null && !settings.aiModel.isEmpty() ? settings.aiModel : "deepseek-chat";
                    q.generationVersion = "resume-v2";
                    q.resumeSection = qObj.has("resumeSection") ? qObj.get("resumeSection").getAsString() : "";
                    q.questionType = qObj.has("questionType") ? qObj.get("questionType").getAsString() : "";
                    q.evaluationPoints = qObj.has("evaluationPoints") ? qObj.get("evaluationPoints").toString() : "[]";
                    q.followUpQuestions = qObj.has("followUpQuestions") ? qObj.get("followUpQuestions").toString() : "[]";
                    q.createdAt = System.currentTimeMillis();
                    q.nextReviewTime = System.currentTimeMillis();

                    if (!q.questionText.trim().isEmpty()) {
                        db.interviewDao().insertQuestion(q);
                    }
                }
            });

            final int finalValidQuestionCount = validQuestionCount;
            if (DbExecutor.isFragmentSafe(ProfileFragment.this)) {
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || getView() == null) return;
                    tvResumeStatus.setText("当前已导入简历: " + profile.fileName + "\n(已成功生成 " + finalValidQuestionCount + " 并入库专属面试题)");
                    Toast.makeText(appCtx, "简历出题成功", Toast.LENGTH_SHORT).show();
                });
            }
        } catch (Exception aiEx) {
            aiEx.printStackTrace();
            if (DbExecutor.isFragmentSafe(ProfileFragment.this)) {
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || getView() == null) return;
                    loadSettings();
                    Toast.makeText(appCtx, "简历出题失败: " + aiEx.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }
    }

    private void exportData(Uri uri) {
        final android.content.Context appCtx = requireContext().getApplicationContext();
        DbExecutor.runOnDbThenUi(ProfileFragment.this,
                () -> {
                    try {
                        List<InterviewQuestion> questions = db.interviewDao().getAllQuestions();
                        List<PracticeAttempt> attempts = db.interviewDao().getAllAttempts();
                        List<ResumeProfile> resumes = db.interviewDao().getAllResumes();
                        List<com.houzhengbo.interview.data.entity.AiEvaluation> evaluations = db.interviewDao().getAllEvaluations();
                        AppSettings settings = db.interviewDao().getSettings();

                        BackupDto backup = new BackupDto();
                        backup.backupSchemaVersion = 4;
                        backup.exportedAt = System.currentTimeMillis();
                        try {
                            android.content.pm.PackageInfo pInfo = appCtx.getPackageManager().getPackageInfo(appCtx.getPackageName(), 0);
                            backup.appVersion = pInfo.versionName;
                        } catch (Exception e) {
                            backup.appVersion = "1.0";
                        }

                        java.util.List<BackupDto.ResumeDto> rDtos = new java.util.ArrayList<>();
                        if (resumes != null) {
                            for (ResumeProfile r : resumes) {
                                BackupDto.ResumeDto dto = new BackupDto.ResumeDto();
                                dto.originalId = r.id;
                                dto.fileName = r.fileName;
                                dto.content = r.content;
                                dto.importedAt = r.importedAt;
                                rDtos.add(dto);
                            }
                        }
                        backup.resumes = rDtos;

                        java.util.List<BackupDto.QuestionDto> qDtos = new java.util.ArrayList<>();
                        if (questions != null) {
                            for (InterviewQuestion q : questions) {
                                BackupDto.QuestionDto dto = new BackupDto.QuestionDto();
                                dto.originalId = q.id;
                                dto.sourceType = q.sourceType;
                                dto.category = q.category;
                                dto.difficulty = q.difficulty;
                                dto.questionText = q.questionText;
                                dto.referenceAnswer = q.referenceAnswer;
                                dto.keywords = q.keywords;
                                dto.sourceTypeUrl = q.sourceUrl;
                                dto.sourceDocumentPath = q.sourceDocumentPath;
                                dto.sourceDocumentHash = q.sourceDocumentHash;
                                dto.sourceRepository = q.sourceRepository;
                                dto.sourceHeadingAnchor = q.sourceHeadingAnchor;
                                dto.generationModel = q.generationModel;
                                dto.generationVersion = q.generationVersion;
                                dto.resumeId = q.resumeId;
                                dto.favorite = q.favorite;
                                dto.masteryLevel = q.masteryLevel;
                                dto.attemptCount = q.attemptCount;
                                dto.highestScore = q.highestScore;
                                dto.lastScore = q.lastScore;
                                dto.lastPracticedAt = q.lastPracticedAt;
                                dto.nextReviewTime = q.nextReviewTime;
                                dto.createdAt = q.createdAt;
                                dto.updatedAt = q.updatedAt;
                                qDtos.add(dto);
                            }
                        }
                        backup.questions = qDtos;

                        java.util.List<BackupDto.AttemptDto> aDtos = new java.util.ArrayList<>();
                        if (attempts != null) {
                            for (PracticeAttempt a : attempts) {
                                BackupDto.AttemptDto dto = new BackupDto.AttemptDto();
                                dto.originalId = a.id;
                                dto.originalQuestionId = a.questionId;
                                dto.userAnswer = a.userAnswer;
                                dto.score = a.score;
                                dto.status = a.status;
                                dto.hitPoints = a.hitPoints;
                                dto.missingPoints = a.missingPoints;
                                dto.improvedAnswer = a.improvedAnswer;
                                dto.followUpQuestion = a.followUpQuestion;
                                dto.aiFeedback = a.aiFeedback;
                                dto.attemptedAt = a.attemptedAt;
                                aDtos.add(dto);
                            }
                        }
                        backup.attempts = aDtos;

                        java.util.List<BackupDto.EvaluationDto> eDtos = new java.util.ArrayList<>();
                        if (evaluations != null) {
                            for (com.houzhengbo.interview.data.entity.AiEvaluation e : evaluations) {
                                BackupDto.EvaluationDto dto = new BackupDto.EvaluationDto();
                                dto.originalId = e.id;
                                dto.originalAttemptId = e.attemptId;
                                dto.missingPoints = e.missingPoints;
                                dto.improvedAnswer = e.improvedAnswer;
                                dto.followUpQuestion = e.followUpQuestion;
                                dto.isPending = e.isPending;
                                eDtos.add(dto);
                            }
                        }
                        backup.evaluations = eDtos;

                        if (settings != null) {
                            BackupDto.SettingsDto sDto = new BackupDto.SettingsDto();
                            sDto.aiProvider = settings.aiProvider;
                            sDto.aiBaseUrl = settings.aiBaseUrl;
                            sDto.aiModel = settings.aiModel;
                            sDto.reminderTime = settings.reminderTime;
                            sDto.randomPopupEnabled = settings.randomPopupEnabled;
                            sDto.wifiOnlySync = settings.wifiOnlySync;
                            backup.settings = sDto;
                        }

                        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                        String json = gson.toJson(backup);

                        java.io.OutputStream os = appCtx.getContentResolver().openOutputStream(uri);
                        if (os != null) {
                            os.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            os.close();
                        }
                        return "OK";
                    } catch (Exception e) {
                        e.printStackTrace();
                        return "导出失败: " + e.getMessage();
                    }
                },
                result -> {
                    if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                    if (!isAdded() || getView() == null) return;
                    String msg = (String) result;
                    if ("OK".equals(msg)) {
                        Toast.makeText(appCtx, "数据导出成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void importData(Uri uri) {
        final android.content.Context appCtx = requireContext().getApplicationContext();
        DbExecutor.runOnDbThenUi(ProfileFragment.this,
                () -> {
                    try {
                        android.database.Cursor cursor = appCtx.getContentResolver().query(uri, null, null, null, null);
                        long fileSize = 0;
                        if (cursor != null && cursor.moveToFirst()) {
                            int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                            if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex);
                            cursor.close();
                        }
                        if (fileSize > 5 * 1024 * 1024) {
                            throw new Exception("备份文件大小超过 5MB 限制");
                        }

                        InputStream is = appCtx.getContentResolver().openInputStream(uri);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                        StringBuilder stringBuilder = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            stringBuilder.append(line).append("\n");
                        }
                        is.close();

                        String json = stringBuilder.toString().trim();
                        if (json.isEmpty()) {
                            throw new Exception("备份文件内容为空");
                        }

                        com.google.gson.Gson gson = new com.google.gson.Gson();
                        BackupDto backup = gson.fromJson(json, BackupDto.class);
                        if (backup == null) {
                            throw new Exception("解析备份文件失败");
                        }

                        int schemaVersion = backup.backupSchemaVersion > 0 ? backup.backupSchemaVersion : 0;
                        if (schemaVersion == 0 && backup.questions != null) {
                            schemaVersion = 3;
                        }
                        if (schemaVersion <= 0 || schemaVersion > 4) {
                            throw new Exception("不支持的备份版本: " + schemaVersion);
                        }
                        if (backup.questions == null) {
                            throw new Exception("备份数据缺少题目列表");
                        }
                        for (BackupDto.QuestionDto qDto : backup.questions) {
                            if (qDto.questionText == null || qDto.questionText.trim().isEmpty()) {
                                throw new Exception("题目数据格式错误：questionText 不能为空");
                            }
                        }
                        if (backup.attempts != null) {
                            for (BackupDto.AttemptDto aDto : backup.attempts) {
                                if (aDto.originalQuestionId <= 0) {
                                    throw new Exception("作答记录格式错误：questionId 必须大于 0");
                                }
                            }
                        }
                        return backup;
                    } catch (Exception e) {
                        e.printStackTrace();
                        return e;
                    }
                },
                result -> {
                    if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                    if (result instanceof Exception) {
                        Exception e = (Exception) result;
                        Toast.makeText(appCtx, "解析失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!(result instanceof BackupDto)) return;
                    final BackupDto backup = (BackupDto) result;
                    new android.app.AlertDialog.Builder(requireContext())
                        .setTitle("数据导入模式选择")
                        .setMessage("请选择恢复数据的模式：\n\n1. 覆盖当前数据：清空现有题目、作答历史和简历，然后恢复。\n2. 合并且跳过重复题：保留现有数据，跳过内容完全重复的题目，仅导入新增的题目及对应历史。")
                        .setPositiveButton("覆盖当前数据", (dialog, which) -> performRestore(backup, true))
                        .setNegativeButton("合并且跳过重复", (dialog, which) -> performRestore(backup, false))
                        .setNeutralButton("取消", null)
                        .show();
                });
    }

    private void performRestore(BackupDto backup, boolean overwrite) {
        final android.content.Context appCtx = requireContext().getApplicationContext();
        DbExecutor.runOnDbThenUi(ProfileFragment.this,
                () -> {
                    try {
                        db.runInTransaction(() -> {
                            if (overwrite) {
                                db.interviewDao().deleteAllEvaluations();
                                db.interviewDao().deleteAllAttempts();
                                db.interviewDao().deleteAllQuestions();
                                db.interviewDao().deleteAllResumes();
                            }

                            java.util.Map<Integer, Integer> resumeIdMap = new java.util.HashMap<>();
                            if (backup.resumes != null) {
                                for (BackupDto.ResumeDto rDto : backup.resumes) {
                                    ResumeProfile existingResume = null;
                                    if (!overwrite) {
                                        List<ResumeProfile> resumes = db.interviewDao().getAllResumes();
                                        if (resumes != null) {
                                            for (ResumeProfile r : resumes) {
                                                if (r.content != null && r.content.equals(rDto.content)) {
                                                    existingResume = r;
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (existingResume != null) {
                                        resumeIdMap.put(rDto.originalId, existingResume.id);
                                    } else {
                                        ResumeProfile r = new ResumeProfile();
                                        r.fileName = rDto.fileName;
                                        r.content = rDto.content;
                                        r.importedAt = rDto.importedAt;
                                        long newResumeId = db.interviewDao().insertResume(r);
                                        resumeIdMap.put(rDto.originalId, (int) newResumeId);
                                    }
                                }
                            }

                            java.util.Map<Integer, Integer> questionIdMap = new java.util.HashMap<>();
                            if (backup.questions != null) {
                                for (BackupDto.QuestionDto qDto : backup.questions) {
                                    InterviewQuestion existingQuestion = null;
                                    if (!overwrite) {
                                        // 用复合身份键匹配，绝不靠纯 questionText 文本匹配，
                                        // 否则会把 GUIDE 题与 RESUME 题、不同简历的同名题误判为同一题。
                                        if ("RESUME".equals(qDto.sourceType) || qDto.resumeId != null) {
                                            existingQuestion = db.interviewDao().getResumeQuestionByIdentity(
                                                    qDto.resumeId, qDto.generationVersion, qDto.questionText);
                                        } else if (qDto.sourceRepository != null && qDto.sourceDocumentPath != null
                                                && qDto.sourceHeadingAnchor != null) {
                                            existingQuestion = db.interviewDao().getQuestionByCompositeKey(
                                                    qDto.sourceRepository, qDto.sourceDocumentPath, qDto.sourceHeadingAnchor);
                                        }
                                    }
                                    int targetQId;
                                    if (existingQuestion != null) {
                                        targetQId = existingQuestion.id;
                                    } else {
                                        InterviewQuestion q = new InterviewQuestion();
                                        q.sourceType = qDto.sourceType;
                                        q.category = qDto.category;
                                        q.difficulty = qDto.difficulty;
                                        q.questionText = qDto.questionText;
                                        q.referenceAnswer = qDto.referenceAnswer;
                                        q.keywords = qDto.keywords;
                                        q.sourceUrl = qDto.sourceTypeUrl;
                                        q.sourceDocumentPath = qDto.sourceDocumentPath;
                                        q.sourceDocumentHash = qDto.sourceDocumentHash;
                                        q.sourceRepository = qDto.sourceRepository;
                                        q.sourceHeadingAnchor = qDto.sourceHeadingAnchor;
                                        q.generationModel = qDto.generationModel;
                                        q.generationVersion = qDto.generationVersion;
                                        if (qDto.resumeId != null) {
                                            Integer newResumeId = resumeIdMap.get(qDto.resumeId);
                                            if (newResumeId == null) {
                                                // 备份损坏：引用了不存在的 resumeId，必须报错并回滚整个导入。
                                                throw new IllegalStateException(
                                                        "备份损坏：题目引用了不存在的简历 originalResumeId=" + qDto.resumeId
                                                                + "（questionText=" + qDto.questionText + "）");
                                            }
                                            q.resumeId = newResumeId;
                                        }
                                        q.favorite = qDto.favorite;
                                        q.masteryLevel = qDto.masteryLevel;
                                        q.attemptCount = qDto.attemptCount;
                                        q.highestScore = qDto.highestScore;
                                        q.lastScore = qDto.lastScore;
                                        q.lastPracticedAt = qDto.lastPracticedAt;
                                        q.nextReviewTime = qDto.nextReviewTime;
                                        q.createdAt = qDto.createdAt;
                                        q.updatedAt = qDto.updatedAt;
                                        long newQId = db.interviewDao().insertQuestion(q);
                                        targetQId = (int) newQId;
                                    }
                                    questionIdMap.put(qDto.originalId, targetQId);
                                }
                            }

                            java.util.Map<Integer, Integer> attemptIdMap = new java.util.HashMap<>();
                            if (backup.attempts != null) {
                                for (BackupDto.AttemptDto aDto : backup.attempts) {
                                    Integer newQId = questionIdMap.get(aDto.originalQuestionId);
                                    if (newQId == null) {
                                        // 备份损坏：attempt 引用了不存在的 questionId，必须报错并回滚。
                                        throw new IllegalStateException(
                                                "备份损坏：答题记录引用了不存在的题目 originalQuestionId=" + aDto.originalQuestionId);
                                    }
                                    // attempt 幂等：(questionId, userAnswer, attemptedAt) 已存在则跳过，
                                    // 避免重复导入同一备份导致回答历史翻倍。
                                    if (db.interviewDao().countAttemptByFingerprint(
                                            newQId, aDto.userAnswer, aDto.attemptedAt) > 0) {
                                        continue;
                                    }
                                    PracticeAttempt a = new PracticeAttempt();
                                    a.questionId = newQId;
                                    a.userAnswer = aDto.userAnswer;
                                    a.score = aDto.score;
                                    a.status = aDto.status;
                                    a.hitPoints = aDto.hitPoints;
                                    a.missingPoints = aDto.missingPoints;
                                    a.improvedAnswer = aDto.improvedAnswer;
                                    a.followUpQuestion = aDto.followUpQuestion;
                                    a.aiFeedback = aDto.aiFeedback;
                                    a.attemptedAt = aDto.attemptedAt;
                                    long newAId = db.interviewDao().insertAttempt(a);
                                    attemptIdMap.put(aDto.originalId, (int) newAId);
                                }
                            }

                            if (backup.evaluations != null) {
                                for (BackupDto.EvaluationDto eDto : backup.evaluations) {
                                    Integer newAId = attemptIdMap.get(eDto.originalAttemptId);
                                    if (newAId != null) {
                                        com.houzhengbo.interview.data.entity.AiEvaluation e = new com.houzhengbo.interview.data.entity.AiEvaluation();
                                        e.attemptId = newAId;
                                        e.missingPoints = eDto.missingPoints;
                                        e.improvedAnswer = eDto.improvedAnswer;
                                        e.followUpQuestion = eDto.followUpQuestion;
                                        e.isPending = eDto.isPending;
                                        db.interviewDao().insertEvaluation(e);
                                    }
                                }
                            }

                            if (backup.settings != null) {
                                AppSettings currentSettings = db.interviewDao().getSettings();
                                if (currentSettings == null) {
                                    currentSettings = new AppSettings();
                                }
                                currentSettings.aiProvider = backup.settings.aiProvider;
                                currentSettings.aiBaseUrl = backup.settings.aiBaseUrl;
                                currentSettings.aiModel = backup.settings.aiModel;
                                currentSettings.reminderTime = backup.settings.reminderTime;
                                currentSettings.randomPopupEnabled = backup.settings.randomPopupEnabled;
                                currentSettings.wifiOnlySync = backup.settings.wifiOnlySync;
                                if (db.interviewDao().getSettings() == null) {
                                    db.interviewDao().insertSettings(currentSettings);
                                } else {
                                    db.interviewDao().updateSettings(currentSettings);
                                }
                            }
                        });
                        return "OK";
                    } catch (Exception e) {
                        e.printStackTrace();
                        return "恢复数据写入失败: " + e.getMessage();
                    }
                },
                result -> {
                    if (!DbExecutor.isFragmentSafe(ProfileFragment.this)) return;
                    String msg = (String) result;
                    if ("OK".equals(msg)) {
                        Toast.makeText(appCtx, "数据恢复成功！", Toast.LENGTH_SHORT).show();
                        loadSettings();
                    } else {
                        Toast.makeText(appCtx, msg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private boolean isWifiConnected(android.content.Context context) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.net.Network network = cm.getActiveNetwork();
            if (network == null) return false;
            android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected() && info.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        }
    }
}

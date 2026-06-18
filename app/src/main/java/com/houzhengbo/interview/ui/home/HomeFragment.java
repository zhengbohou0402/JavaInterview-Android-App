package com.houzhengbo.interview.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.google.android.material.card.MaterialCardView;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.network.GithubRepoSyncWorker;
import com.houzhengbo.interview.network.SyncStatusManager;
import com.houzhengbo.interview.ui.dashboard.LearningDashboardActivity;
import com.houzhengbo.interview.ui.jd.JdMatchActivity;
import com.houzhengbo.interview.ui.mock.MockInterviewActivity;
import com.houzhengbo.interview.ui.practice.PracticeActivity;
import com.houzhengbo.interview.ui.review.ReviewQueueActivity;
import com.houzhengbo.interview.ui.training.TrainingActivity;
import com.houzhengbo.interview.utils.DbExecutor;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HomeFragment extends Fragment {

    // ── Onboarding card (shown when NOT_DOWNLOADED) ──────────────────────────
    private View cardOnboarding;
    private TextView tvOnboardingMessage;
    private Button btnDownloadLibrary;

    // ── Download progress card (shown when DOWNLOADING) ─────────────────────
    private View cardDownloadProgress;
    private ProgressBar progressBarDownload;
    private TextView tvDownloadPhase;
    private TextView tvDownloadStats;
    private Button btnCancelSync;

    // ── Normal content (shown when READY / PARTIAL) ──────────────────────────
    private TextView tvHomeQuestion;
    private Button btnHomePractice;
    private MaterialCardView cardRandomQuestion;
    private TextView tvStats;
    // 首页重排新增：统计数字 + 掌握度进度条
    private TextView tvStatMastered;
    private TextView tvStatReview;
    private TextView tvStatTotal;
    private TextView tvStatProgressPct;
    private com.google.android.material.progressindicator.LinearProgressIndicator progressMastery;

    // ── Failure banner ────────────────────────────────────────────────────────
    private View cardSyncFailed;
    private Button btnRetrySync;

    // ── Specialized Training card ─────────────────────────────────────────────
    private MaterialCardView cardTraining;
    private MaterialCardView cardMockInterview;
    private MaterialCardView cardReviewQueue;
    private MaterialCardView cardJdMatch;
    private MaterialCardView cardLearningDashboard;

    private AppDatabase db;
    private InterviewQuestion currentRandomQuestion;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        db = InterviewApplication.getInstance().getDatabase();

        // Bind views
        cardOnboarding      = view.findViewById(R.id.card_onboarding);
        tvOnboardingMessage = view.findViewById(R.id.tv_onboarding_message);
        btnDownloadLibrary  = view.findViewById(R.id.btn_download_library);

        cardDownloadProgress = view.findViewById(R.id.card_download_progress);
        progressBarDownload  = view.findViewById(R.id.progress_bar_download);
        tvDownloadPhase      = view.findViewById(R.id.tv_download_phase);
        tvDownloadStats      = view.findViewById(R.id.tv_download_stats);
        btnCancelSync        = view.findViewById(R.id.btn_cancel_sync_home);

        cardRandomQuestion  = view.findViewById(R.id.card_random_question);
        tvHomeQuestion      = view.findViewById(R.id.tv_home_question);
        btnHomePractice     = view.findViewById(R.id.btn_home_practice);
        tvStats             = view.findViewById(R.id.tv_stats);
        // 首页重排新增视图
        tvStatMastered      = view.findViewById(R.id.tv_stat_mastered);
        tvStatReview        = view.findViewById(R.id.tv_stat_review);
        tvStatTotal         = view.findViewById(R.id.tv_stat_total);
        tvStatProgressPct   = view.findViewById(R.id.tv_stat_progress_pct);
        progressMastery     = view.findViewById(R.id.progress_mastery);

        cardSyncFailed = view.findViewById(R.id.card_sync_failed);
        btnRetrySync   = view.findViewById(R.id.btn_retry_sync_home);

        // ── Training card ─────────────────────────────────────────────────
        cardTraining = view.findViewById(R.id.card_training);
        cardTraining.setOnClickListener(v -> showCategoryPickerDialog());
        cardMockInterview = view.findViewById(R.id.card_mock_interview);
        cardReviewQueue = view.findViewById(R.id.card_review_queue);
        cardJdMatch = view.findViewById(R.id.card_jd_match);
        cardLearningDashboard = view.findViewById(R.id.card_learning_dashboard);
        cardMockInterview.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), MockInterviewActivity.class)));
        cardReviewQueue.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ReviewQueueActivity.class)));
        cardJdMatch.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), JdMatchActivity.class)));
        cardLearningDashboard.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), LearningDashboardActivity.class)));

        // Button actions
        btnDownloadLibrary.setOnClickListener(v -> startFirstDownload());
        btnCancelSync.setOnClickListener(v -> cancelSync());
        btnRetrySync.setOnClickListener(v -> startFirstDownload());
        cardRandomQuestion.setOnClickListener(v -> loadRandomQuestion());
        btnHomePractice.setOnClickListener(v -> {
            if (currentRandomQuestion != null) {
                Intent intent = new Intent(requireContext(), PracticeActivity.class);
                intent.putExtra("QUESTION_ID", currentRandomQuestion.id);
                startActivity(intent);
            }
        });

        // Observe WorkManager for any running sync job
        observeSyncWork();

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUiState();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sync actions
    // ─────────────────────────────────────────────────────────────────────────

    private void startFirstDownload() {
        if (!isAdded()) return;
        // Check Wi-Fi preference
        DbExecutor.runOnDbThenUi(HomeFragment.this,
                () -> {
                    com.houzhengbo.interview.data.entity.AppSettings settings =
                            db.interviewDao().getSettings();
                    boolean wifiOnly = settings != null && settings.wifiOnlySync;
                    if (wifiOnly && !isWifiConnected()) {
                        return new Object[]{"WIFI_BLOCKED"};
                    }
                    return new Object[]{"OK"};
                },
                result -> {
                    if (!isAdded()) return;
                    if (!(result instanceof Object[])) return;
                    Object[] arr = (Object[]) result;
                    if ("WIFI_BLOCKED".equals(arr[0])) {
                        Toast.makeText(requireContext(),
                                "已开启仅 Wi-Fi 同步，当前非 Wi-Fi 网络", Toast.LENGTH_LONG).show();
                        return;
                    }
                    OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(GithubRepoSyncWorker.class)
                            .addTag(GithubRepoSyncWorker.TAG_SYNC)
                            .build();
                    WorkManager.getInstance(requireContext()).enqueue(req);
                    showDownloadingState(0, 0, "正在获取文档目录…");
                });
    }

    private void cancelSync() {
        WorkManager.getInstance(requireContext()).cancelAllWorkByTag(GithubRepoSyncWorker.TAG_SYNC);
        Toast.makeText(requireContext(), "已发送取消请求", Toast.LENGTH_SHORT).show();
        refreshUiState();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WorkManager observation
    // ─────────────────────────────────────────────────────────────────────────

    private void observeSyncWork() {
        WorkManager.getInstance(requireContext())
                .getWorkInfosByTagLiveData(GithubRepoSyncWorker.TAG_SYNC)
                .observe(getViewLifecycleOwner(), workInfoList -> {
                    if (workInfoList == null || workInfoList.isEmpty()) return;
                    for (WorkInfo info : workInfoList) {
                        WorkInfo.State state = info.getState();
                        if (state == WorkInfo.State.RUNNING) {
                            int cur     = info.getProgress().getInt(GithubRepoSyncWorker.PROGRESS_CURRENT, 0);
                            int total   = info.getProgress().getInt(GithubRepoSyncWorker.PROGRESS_TOTAL, 0);
                            String phase = info.getProgress().getString(GithubRepoSyncWorker.PROGRESS_PHASE);
                            int success = info.getProgress().getInt(GithubRepoSyncWorker.PROGRESS_SUCCESS, 0);
                            int skip    = info.getProgress().getInt(GithubRepoSyncWorker.PROGRESS_SKIP, 0);
                            int fail    = info.getProgress().getInt(GithubRepoSyncWorker.PROGRESS_FAIL, 0);
                            showDownloadingState(cur, total, phase != null ? phase : "同步中…");
                            tvDownloadStats.setText("成功 " + success + "  跳过 " + skip + "  失败 " + fail);
                            return;
                        } else if (state == WorkInfo.State.SUCCEEDED || state == WorkInfo.State.FAILED
                                || state == WorkInfo.State.CANCELLED) {
                            // After first successful sync, register periodic job
                            if (state == WorkInfo.State.SUCCEEDED) {
                                InterviewApplication.getInstance().schedulePeriodicSyncIfReady();
                            }
                            refreshUiState();
                            return;
                        }
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI state
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshUiState() {
        String status = SyncStatusManager.getStatus(requireContext());
        switch (status) {
            case SyncStatusManager.NOT_DOWNLOADED:
                showOnboardingState();
                break;
            case SyncStatusManager.DOWNLOADING:
                showDownloadingState(0, 0, "同步中…");
                break;
            case SyncStatusManager.FAILED:
                showFailedState();
                break;
            case SyncStatusManager.READY:
            case SyncStatusManager.PARTIAL:
            default:
                showReadyState();
                break;
        }
    }

    private void showOnboardingState() {
        cardOnboarding.setVisibility(View.VISIBLE);
        cardDownloadProgress.setVisibility(View.GONE);
        cardSyncFailed.setVisibility(View.GONE);
        cardRandomQuestion.setVisibility(View.GONE);
        setStatsAreaVisible(false);
        tvOnboardingMessage.setText("题库尚未下载，建议连接 Wi-Fi 后下载。");
    }

    private void showDownloadingState(int cur, int total, String phase) {
        cardOnboarding.setVisibility(View.GONE);
        cardDownloadProgress.setVisibility(View.VISIBLE);
        cardSyncFailed.setVisibility(View.GONE);
        cardRandomQuestion.setVisibility(View.GONE);
        setStatsAreaVisible(false);

        tvDownloadPhase.setText(phase);
        if (total > 0) {
            progressBarDownload.setMax(total);
            progressBarDownload.setProgress(cur);
            progressBarDownload.setIndeterminate(false);
        } else {
            progressBarDownload.setIndeterminate(true);
        }
    }

    private void showFailedState() {
        cardOnboarding.setVisibility(View.GONE);
        cardDownloadProgress.setVisibility(View.GONE);
        cardSyncFailed.setVisibility(View.VISIBLE);

        // Show content if we have any questions from before
        DbExecutor.runOnDbThenUi(HomeFragment.this,
                () -> db.interviewDao().getTotalActiveQuestionsCount(),
                count -> {
                    if (!isAdded()) return;
                    if (count > 0) {
                        cardRandomQuestion.setVisibility(View.VISIBLE);
                        setStatsAreaVisible(true);
                        loadRandomQuestion();
                        loadStats();
                    }
                });
    }

    private void showReadyState() {
        cardOnboarding.setVisibility(View.GONE);
        cardDownloadProgress.setVisibility(View.GONE);
        cardSyncFailed.setVisibility(View.GONE);
        cardRandomQuestion.setVisibility(View.VISIBLE);
        setStatsAreaVisible(true);
        loadRandomQuestion();
        loadStats();
    }

    /**
     * 统一控制首页"学习进度"统计区的可见性（三宫格数字 + 掌握度进度条 + 同步时间）。
     * onboarding / downloading 状态隐藏；ready / partial / failed(有题) 状态显示。
     */
    private void setStatsAreaVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        tvStats.setVisibility(v);
        if (tvStatMastered != null) {
            tvStatMastered.setVisibility(v);
            tvStatReview.setVisibility(v);
            tvStatTotal.setVisibility(v);
            tvStatProgressPct.setVisibility(v);
            progressMastery.setVisibility(v);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data loading
    // ─────────────────────────────────────────────────────────────────────────

    private void loadStats() {
        DbExecutor.runOnDbThenUi(HomeFragment.this,
                () -> new Object[]{
                        db.interviewDao().getTotalActiveQuestionsCount(),
                        db.interviewDao().getMasteredQuestionsCount(),
                        db.interviewDao().getReviewQuestionsCount(System.currentTimeMillis()),
                        SyncStatusManager.getLastSyncTime(requireContext().getApplicationContext())
                },
                result -> {
                    if (!isAdded()) return;
                    if (!(result instanceof Object[])) return;
                    Object[] arr = (Object[]) result;
                    int total = (Integer) arr[0];
                    int mastered = (Integer) arr[1];
                    int toReview = (Integer) arr[2];
                    long lastSync = (Long) arr[3];
                    String syncStr = lastSync > 0
                            ? new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(lastSync))
                            : "从未";

                    // 三宫格数字
                    tvStatMastered.setText(String.valueOf(mastered));
                    tvStatReview.setText(String.valueOf(toReview));
                    tvStatTotal.setText(String.valueOf(total));

                    // 掌握度百分比 + 进度条（总题为 0 时显示 0%，避免除零）
                    int pct = total > 0 ? (int) (mastered * 100L / total) : 0;
                    tvStatProgressPct.setText(pct + "%");
                    progressMastery.setMax(100);
                    progressMastery.setProgress(pct);

                    // tv_stats 降级为只显示同步时间（数字已移到网格）
                    if (total == 0) {
                        tvStats.setText("题库为空，快去同步题库或导入简历吧！");
                    } else {
                        tvStats.setText("上次同步：" + syncStr);
                    }
                });
    }

    private void loadRandomQuestion() {
        DbExecutor.runOnDbThenUi(HomeFragment.this,
                () -> db.interviewDao().getRandomActiveQuestion(),
                question -> {
                    if (!isAdded()) return;
                    currentRandomQuestion = question;
                    if (currentRandomQuestion != null) {
                        tvHomeQuestion.setText(currentRandomQuestion.questionText);
                        btnHomePractice.setVisibility(View.VISIBLE);
                    } else {
                        tvHomeQuestion.setText("题库为空\n请前往「我的」页面同步题库或导入简历");
                        btnHomePractice.setVisibility(View.GONE);
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Training category picker
    // ─────────────────────────────────────────────────────────────────────────

    private void showCategoryPickerDialog() {
        DbExecutor.runOnDbThenUi(HomeFragment.this,
                () -> db.interviewDao().getAllParentCategories(),
                categories -> {
                    if (!isAdded()) return;
                    if (categories == null || categories.isEmpty()) {
                        Toast.makeText(requireContext(), "暂无分类数据", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] items = categories.toArray(new String[0]);
                    new AlertDialog.Builder(requireContext())
                            .setTitle("选择训练分类")
                            .setItems(items, (dialog, which) -> {
                                String selected = items[which];
                                Intent intent = new Intent(requireContext(), TrainingActivity.class);
                                intent.putExtra(TrainingActivity.EXTRA_PARENT_CATEGORY, selected);
                                startActivity(intent);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isWifiConnected() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && caps.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected() &&
                   info.getType() == android.net.ConnectivityManager.TYPE_WIFI;
        }
    }
}

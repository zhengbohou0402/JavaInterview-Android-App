package com.houzhengbo.interview.ui.dashboard;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.data.entity.PracticeAttempt;
import com.houzhengbo.interview.utils.DbExecutor;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class LearningDashboardActivity extends AppCompatActivity {

    private TextView tvSummary;
    private TextView tvWeak;
    private TextView tvRecent;
    private AppDatabase db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_learning_dashboard);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_dashboard);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvSummary = findViewById(R.id.tv_dashboard_summary);
        tvWeak = findViewById(R.id.tv_dashboard_weak);
        tvRecent = findViewById(R.id.tv_dashboard_recent);
        db = InterviewApplication.getInstance().getDatabase();

        loadDashboard();
    }

    private void loadDashboard() {
        DbExecutor.runOnDbThenUi(this,
                () -> {
                    List<InterviewQuestion> questions = db.interviewDao().getAllQuestions();
                    List<PracticeAttempt> attempts = db.interviewDao().getAllAttempts();
                    int dueReview = db.interviewDao().getReviewQuestionsCount(System.currentTimeMillis());
                    return buildDashboard(questions, attempts, dueReview);
                },
                data -> {
                    tvSummary.setText(data.summary);
                    tvWeak.setText(data.weakCategories);
                    tvRecent.setText(data.recentAttempts);
                });
    }

    private DashboardData buildDashboard(List<InterviewQuestion> questions,
                                         List<PracticeAttempt> attempts,
                                         int dueReview) {
        Map<Integer, InterviewQuestion> questionById = new HashMap<>();
        for (InterviewQuestion question : questions) {
            questionById.put(question.id, question);
        }

        long now = System.currentTimeMillis();
        long weekAgo = now - 7L * 24 * 60 * 60 * 1000;
        int attemptsLast7Days = 0;
        int scoredLast7Days = 0;
        int scoreSumLast7Days = 0;
        Set<Integer> practicedQuestionIds = new HashSet<>();

        for (PracticeAttempt attempt : attempts) {
            practicedQuestionIds.add(attempt.questionId);
            if (attempt.attemptedAt >= weekAgo) {
                attemptsLast7Days++;
                if (attempt.score != null) {
                    scoredLast7Days++;
                    scoreSumLast7Days += attempt.score;
                }
            }
        }

        int averageScore = scoredLast7Days > 0 ? scoreSumLast7Days / scoredLast7Days : 0;
        int activeQuestions = 0;
        int mastered = 0;
        for (InterviewQuestion question : questions) {
            if (question.archived) continue;
            activeQuestions++;
            if (question.masteryLevel >= 80) mastered++;
        }

        String summary = "总题数：" + activeQuestions + "\n"
                + "已练习题目：" + practicedQuestionIds.size() + "\n"
                + "已掌握：" + mastered + "\n"
                + "待复习：" + dueReview + "\n"
                + "近 7 天答题：" + attemptsLast7Days + " 次\n"
                + "近 7 天均分：" + (scoredLast7Days > 0 ? String.valueOf(averageScore) : "暂无评分");

        String weak = buildWeakCategories(questions);
        String recent = buildRecentAttempts(attempts, questionById);
        return new DashboardData(summary, weak, recent);
    }

    private String buildWeakCategories(List<InterviewQuestion> questions) {
        Map<String, CategoryStat> stats = new HashMap<>();
        for (InterviewQuestion question : questions) {
            if (question.archived || question.attemptCount <= 0) continue;
            if (question.masteryLevel >= 60 && question.lastScore >= 60) continue;

            String category = firstNonEmpty(question.parentCategory, question.category, "未分类");
            CategoryStat stat = stats.get(category);
            if (stat == null) {
                stat = new CategoryStat(category);
                stats.put(category, stat);
            }
            stat.count++;
            stat.scoreSum += Math.max(0, question.lastScore);
        }

        if (stats.isEmpty()) {
            return "暂时没有明显弱项。继续答几题后，这里会按低分题自动汇总。";
        }

        List<CategoryStat> items = new ArrayList<>(stats.values());
        Collections.sort(items, (a, b) -> {
            int avgCompare = Integer.compare(a.averageScore(), b.averageScore());
            if (avgCompare != 0) return avgCompare;
            return Integer.compare(b.count, a.count);
        });

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(6, items.size());
        for (int i = 0; i < limit; i++) {
            CategoryStat stat = items.get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(stat.name)
                    .append("：")
                    .append(stat.count)
                    .append(" 题，均分 ")
                    .append(stat.averageScore())
                    .append("\n");
        }
        return sb.toString().trim();
    }

    private String buildRecentAttempts(List<PracticeAttempt> attempts,
                                       Map<Integer, InterviewQuestion> questionById) {
        if (attempts.isEmpty()) {
            return "还没有答题记录。先去题库或模拟面试练几题，这里会自动沉淀复盘记录。";
        }

        List<PracticeAttempt> sorted = new ArrayList<>(attempts);
        Collections.sort(sorted, Comparator.comparingLong((PracticeAttempt a) -> a.attemptedAt).reversed());
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(8, sorted.size());
        for (int i = 0; i < limit; i++) {
            PracticeAttempt attempt = sorted.get(i);
            InterviewQuestion question = questionById.get(attempt.questionId);
            String title = question != null ? question.questionText : "题目 #" + attempt.questionId;
            if (title.length() > 42) {
                title = title.substring(0, 42) + "...";
            }
            String scoreText = attempt.score != null ? attempt.score + " 分" : safeStatus(attempt.status);
            sb.append(format.format(new Date(attempt.attemptedAt)))
                    .append(" · ")
                    .append(scoreText)
                    .append("\n")
                    .append(title)
                    .append("\n\n");
        }
        return sb.toString().trim();
    }

    private String safeStatus(String status) {
        if (status == null || status.trim().isEmpty()) return "待评分";
        if ("MOCK_INTERVIEW".equals(status)) return "模拟面试";
        if ("PENDING".equals(status)) return "待评分";
        if ("FAILED".equals(status)) return "评分失败";
        if ("SCORED".equals(status)) return "已评分";
        return status;
    }

    private String firstNonEmpty(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) return first;
        if (second != null && !second.trim().isEmpty()) return second;
        return fallback;
    }

    private static class CategoryStat {
        final String name;
        int count;
        int scoreSum;

        CategoryStat(String name) {
            this.name = name;
        }

        int averageScore() {
            return count > 0 ? scoreSum / count : 0;
        }
    }

    private static class DashboardData {
        final String summary;
        final String weakCategories;
        final String recentAttempts;

        DashboardData(String summary, String weakCategories, String recentAttempts) {
            this.summary = summary;
            this.weakCategories = weakCategories;
            this.recentAttempts = recentAttempts;
        }
    }
}

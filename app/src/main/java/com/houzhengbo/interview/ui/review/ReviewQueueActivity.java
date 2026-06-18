package com.houzhengbo.interview.ui.review;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.ui.practice.PracticeActivity;
import com.houzhengbo.interview.utils.DbExecutor;

import java.util.ArrayList;
import java.util.List;

public class ReviewQueueActivity extends AppCompatActivity {

    private TextView tvSummary;
    private TextView tvEmpty;
    private Button btnStart;
    private LinearLayout listContainer;
    private AppDatabase db;
    private List<InterviewQuestion> questions = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_review_queue);

        db = InterviewApplication.getInstance().getDatabase();
        MaterialToolbar toolbar = findViewById(R.id.toolbar_review);
        toolbar.setNavigationOnClickListener(v -> finish());
        tvSummary = findViewById(R.id.tv_review_summary);
        tvEmpty = findViewById(R.id.tv_review_empty);
        btnStart = findViewById(R.id.btn_review_start);
        listContainer = findViewById(R.id.list_review_questions);

        btnStart.setOnClickListener(v -> {
            if (!questions.isEmpty()) openQuestion(questions.get(0));
        });
        loadQueue();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (db != null) loadQueue();
    }

    private void loadQueue() {
        long now = System.currentTimeMillis();
        DbExecutor.runOnDbThenUi(this,
                () -> db.interviewDao().getReviewQueue(now, 80),
                result -> {
                    questions = result == null ? new ArrayList<>() : result;
                    render();
                });
    }

    private void render() {
        listContainer.removeAllViews();
        int due = 0;
        int weak = 0;
        int masterySum = 0;
        long now = System.currentTimeMillis();
        for (InterviewQuestion q : questions) {
            if (q.nextReviewTime > 0 && q.nextReviewTime <= now) due++;
            if (q.masteryLevel < 60) weak++;
            masterySum += q.masteryLevel;
        }
        int average = questions.isEmpty() ? 0 : masterySum / questions.size();
        tvSummary.setText("共 " + questions.size() + " 题，已到期 " + due
                + " 题，低掌握 " + weak + " 题，平均掌握 " + average);

        boolean empty = questions.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        btnStart.setEnabled(!empty);
        btnStart.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;

        for (int i = 0; i < questions.size(); i++) {
            listContainer.addView(createQuestionCard(i, questions.get(i)));
        }
    }

    private View createQuestionCard(int index, InterviewQuestion question) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardParams);
        card.setRadius(dp(16));
        card.setCardElevation(0f);
        card.setStrokeWidth(dp(1));
        card.setStrokeColor(resolveColor(com.google.android.material.R.attr.colorOutline));
        card.setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openQuestion(question));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.addView(content);

        TextView meta = new TextView(this);
        meta.setText("#" + (index + 1) + "  " + dueText(question)
                + "  ·  掌握 " + question.masteryLevel
                + "  ·  上次 " + question.lastScore + " 分");
        meta.setTextColor(resolveColor(com.google.android.material.R.attr.colorPrimary));
        meta.setTextSize(12);
        content.addView(meta);

        TextView title = new TextView(this);
        title.setText(summarize(question.questionText, 120));
        title.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface));
        title.setTextSize(15);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(8), 0, 0);
        title.setLayoutParams(titleParams);
        content.addView(title);

        TextView category = new TextView(this);
        category.setText(categoryText(question));
        category.setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        category.setTextSize(12);
        LinearLayout.LayoutParams catParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        catParams.setMargins(0, dp(8), 0, 0);
        category.setLayoutParams(catParams);
        content.addView(category);
        return card;
    }

    private String dueText(InterviewQuestion question) {
        if (question.nextReviewTime <= 0) return "未安排";
        long diff = question.nextReviewTime - System.currentTimeMillis();
        if (diff <= 0) return "已到期";
        long days = (diff + 24L * 60 * 60 * 1000 - 1) / (24L * 60 * 60 * 1000);
        return days + " 天后";
    }

    private String categoryText(InterviewQuestion question) {
        String parent = question.parentCategory == null || question.parentCategory.isEmpty()
                ? "综合" : question.parentCategory;
        if (question.category == null || question.category.isEmpty()
                || question.category.equals(parent)) {
            return parent;
        }
        return parent + " · " + question.category;
    }

    private String summarize(String text, int limit) {
        if (text == null) return "未命名题目";
        String plain = text.replaceAll("\\s+", " ").trim();
        return plain.length() > limit ? plain.substring(0, limit) + "..." : plain;
    }

    private void openQuestion(InterviewQuestion question) {
        Intent intent = new Intent(this, PracticeActivity.class);
        intent.putExtra("QUESTION_ID", question.id);
        startActivity(intent);
    }

    private int resolveColor(int attr) {
        return com.google.android.material.color.MaterialColors.getColor(
                findViewById(android.R.id.content), attr);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}

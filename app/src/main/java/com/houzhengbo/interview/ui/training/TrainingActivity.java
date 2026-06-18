package com.houzhengbo.interview.ui.training;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.utils.DbExecutor;

import java.util.Collections;
import java.util.List;

import io.noties.markwon.Markwon;

public class TrainingActivity extends AppCompatActivity {

    public static final String EXTRA_PARENT_CATEGORY = "parent_category";

    private TextView tvTitle, tvProgress, tvParentCategory, tvCategory, tvQuestionText, tvReferenceAnswer;
    private MaterialButton btnBack, btnFavorite, btnShowAnswer, btnNext;
    private MaterialCardView cardAnswer;

    private AppDatabase db;
    private Markwon markwon;

    private String parentCategory;
    private List<InterviewQuestion> questions;
    private int currentIndex = 0;
    private InterviewQuestion currentQuestion;
    private boolean answerVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_training);

        parentCategory = getIntent().getStringExtra(EXTRA_PARENT_CATEGORY);
        if (parentCategory == null || parentCategory.isEmpty()) {
            Toast.makeText(this, "未选择分类", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        db = InterviewApplication.getInstance().getDatabase();
        markwon = Markwon.create(this);

        initViews();
        loadQuestions();
    }

    private void initViews() {
        tvTitle = findViewById(R.id.tv_title);
        tvProgress = findViewById(R.id.tv_progress);
        tvParentCategory = findViewById(R.id.tv_parent_category);
        tvCategory = findViewById(R.id.tv_category);
        tvQuestionText = findViewById(R.id.tv_question_text);
        tvReferenceAnswer = findViewById(R.id.tv_reference_answer);
        btnBack = findViewById(R.id.btn_back);
        btnFavorite = findViewById(R.id.btn_favorite);
        btnShowAnswer = findViewById(R.id.btn_show_answer);
        btnNext = findViewById(R.id.btn_next);
        cardAnswer = findViewById(R.id.card_answer);

        tvTitle.setText(parentCategory);
        tvParentCategory.setText(parentCategory);

        btnBack.setOnClickListener(v -> finish());

        btnFavorite.setOnClickListener(v -> toggleFavorite());
        btnShowAnswer.setOnClickListener(v -> toggleAnswer());
        btnNext.setOnClickListener(v -> showNextQuestion());
    }

    private void loadQuestions() {
        DbExecutor.runOnDbThenUi(this,
                () -> {
                    List<InterviewQuestion> result = db.interviewDao().getQuestionsByParentCategory(parentCategory);
                    if (result == null) result = Collections.emptyList();
                    Collections.shuffle(result);
                    return result;
                },
                new DbExecutor.UiCallback<List<InterviewQuestion>>() {
                    @Override
                    public void onResult(List<InterviewQuestion> result) {
                        questions = result;
                        if (questions.isEmpty()) {
                            Toast.makeText(TrainingActivity.this, "该分类暂无题目", Toast.LENGTH_SHORT).show();
                            finish();
                            return;
                        }
                        currentIndex = 0;
                        showCurrentQuestion();
                    }
                }
        );
    }

    private void showCurrentQuestion() {
        if (questions == null || questions.isEmpty()) return;
        if (currentIndex >= questions.size()) {
            Toast.makeText(this, "已全部答完！", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        currentQuestion = questions.get(currentIndex);
        answerVisible = false;

        // 更新进度
        tvProgress.setText((currentIndex + 1) + "/" + questions.size());

        // 更新分类标签
        if (currentQuestion.category != null && !currentQuestion.category.isEmpty()) {
            tvCategory.setText(currentQuestion.category);
            tvCategory.setVisibility(View.VISIBLE);
        } else {
            tvCategory.setVisibility(View.GONE);
        }

        // 更新题目
        tvQuestionText.setText(currentQuestion.questionText);

        // 隐藏答案
        cardAnswer.setVisibility(View.GONE);
        btnShowAnswer.setText("查看答案");

        // 更新收藏按钮状态
        updateFavoriteButton();

        // 重置滚动位置
        tvQuestionText.scrollTo(0, 0);
    }

    private void toggleFavorite() {
        if (currentQuestion == null) return;

        final boolean newFavorite = !currentQuestion.favorite;
        final long now = System.currentTimeMillis();

        DbExecutor.runOnDbThenUi(this,
                () -> {
                    db.interviewDao().updateFavorite(currentQuestion.id, newFavorite, now);
                    currentQuestion.favorite = newFavorite;
                },
                () -> updateFavoriteButton()
        );
    }

    private void updateFavoriteButton() {
        if (currentQuestion == null) return;
        if (currentQuestion.favorite) {
            btnFavorite.setIconResource(R.drawable.ic_star_filled);
            android.util.TypedValue typedValue = new android.util.TypedValue();
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true);
            btnFavorite.setIconTintResource(typedValue.resourceId);
        } else {
            btnFavorite.setIconResource(R.drawable.ic_star_outline);
            // Resolve theme attribute to get the actual color
            android.util.TypedValue typedValue = new android.util.TypedValue();
            getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, typedValue, true);
            btnFavorite.setIconTintResource(typedValue.resourceId);
        }
    }

    private void toggleAnswer() {
        if (currentQuestion == null) return;

        answerVisible = !answerVisible;
        if (answerVisible) {
            String answer = getAnswerText(currentQuestion);
            markwon.setMarkdown(tvReferenceAnswer, answer);
            cardAnswer.setVisibility(View.VISIBLE);
            btnShowAnswer.setText("收起答案");
        } else {
            cardAnswer.setVisibility(View.GONE);
            btnShowAnswer.setText("查看答案");
        }
    }

    private String getAnswerText(InterviewQuestion q) {
        if (q.referenceAnswer != null && !q.referenceAnswer.isEmpty()) {
            return q.referenceAnswer;
        }
        if (q.referenceAnswerMarkdown != null && !q.referenceAnswerMarkdown.isEmpty()) {
            return q.referenceAnswerMarkdown;
        }
        if (q.plainTextAnswer != null && !q.plainTextAnswer.isEmpty()) {
            return q.plainTextAnswer;
        }
        return "暂无参考答案";
    }

    private void showNextQuestion() {
        currentIndex++;
        showCurrentQuestion();
    }
}

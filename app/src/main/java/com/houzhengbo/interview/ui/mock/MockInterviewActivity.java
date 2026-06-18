package com.houzhengbo.interview.ui.mock;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.data.entity.PracticeAttempt;
import com.houzhengbo.interview.utils.DbExecutor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.noties.markwon.Markwon;

public class MockInterviewActivity extends AppCompatActivity {

    private View layoutSetup;
    private View layoutRunning;
    private View layoutFinished;
    private RadioGroup rgCount;
    private TextView tvProgress;
    private TextView tvMeta;
    private TextView tvQuestion;
    private TextView tvAnswer;
    private TextView tvReport;
    private EditText etAnswer;
    private MaterialCardView cardAnswer;
    private Button btnStart;
    private Button btnToggleAnswer;
    private Button btnRestart;
    private AppDatabase db;
    private Markwon markwon;
    private List<InterviewQuestion> questions = new ArrayList<>();
    private final List<Integer> scores = new ArrayList<>();
    private int currentIndex;
    private long startedAt;
    private long questionStartedAt;
    private boolean answerVisible;
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mock_interview);

        db = InterviewApplication.getInstance().getDatabase();
        markwon = Markwon.create(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar_mock);
        toolbar.setNavigationOnClickListener(v -> finish());

        layoutSetup = findViewById(R.id.layout_mock_setup);
        layoutRunning = findViewById(R.id.layout_mock_running);
        layoutFinished = findViewById(R.id.layout_mock_finished);
        rgCount = findViewById(R.id.rg_mock_count);
        tvProgress = findViewById(R.id.tv_mock_progress);
        tvMeta = findViewById(R.id.tv_mock_meta);
        tvQuestion = findViewById(R.id.tv_mock_question);
        tvAnswer = findViewById(R.id.tv_mock_answer);
        tvReport = findViewById(R.id.tv_mock_report);
        etAnswer = findViewById(R.id.et_mock_answer);
        cardAnswer = findViewById(R.id.card_mock_answer);
        btnStart = findViewById(R.id.btn_mock_start);
        btnToggleAnswer = findViewById(R.id.btn_mock_toggle_answer);
        btnRestart = findViewById(R.id.btn_mock_restart);

        btnStart.setOnClickListener(v -> startMockInterview());
        btnToggleAnswer.setOnClickListener(v -> toggleAnswer());
        btnRestart.setOnClickListener(v -> showSetup());
        findViewById(R.id.btn_score_0).setOnClickListener(v -> submitScore(0));
        findViewById(R.id.btn_score_40).setOnClickListener(v -> submitScore(40));
        findViewById(R.id.btn_score_60).setOnClickListener(v -> submitScore(60));
        findViewById(R.id.btn_score_80).setOnClickListener(v -> submitScore(80));
        findViewById(R.id.btn_score_100).setOnClickListener(v -> submitScore(100));
    }

    private void showSetup() {
        layoutSetup.setVisibility(View.VISIBLE);
        layoutRunning.setVisibility(View.GONE);
        layoutFinished.setVisibility(View.GONE);
        questions.clear();
        scores.clear();
        currentIndex = 0;
        saving = false;
    }

    private void startMockInterview() {
        final int count = selectedCount();
        btnStart.setEnabled(false);
        long now = System.currentTimeMillis();
        DbExecutor.runOnDbThenUi(this,
                () -> buildQuestionSet(now, count),
                result -> {
                    btnStart.setEnabled(true);
                    if (result == null || result.isEmpty()) {
                        Toast.makeText(this, "题库为空，请先同步或添加题目", Toast.LENGTH_LONG).show();
                        return;
                    }
                    questions = result;
                    scores.clear();
                    currentIndex = 0;
                    startedAt = System.currentTimeMillis();
                    layoutSetup.setVisibility(View.GONE);
                    layoutFinished.setVisibility(View.GONE);
                    layoutRunning.setVisibility(View.VISIBLE);
                    showCurrentQuestion();
                });
    }

    private List<InterviewQuestion> buildQuestionSet(long now, int count) {
        List<InterviewQuestion> result = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        int dueTarget = Math.max(2, count / 3);
        int projectTarget = Math.max(1, count / 4);

        appendUnique(result, ids, db.interviewDao().getMockDueQuestions(now, dueTarget), count);
        appendUnique(result, ids, db.interviewDao().getMockProjectQuestions(count * 3), dueTarget + projectTarget);
        appendUnique(result, ids, db.interviewDao().getMockKnowledgeQuestions(count * 3), count);
        appendUnique(result, ids, db.interviewDao().getRandomActiveQuestions(count * 3), count);
        return result;
    }

    private void appendUnique(List<InterviewQuestion> target, Set<Integer> ids,
                              List<InterviewQuestion> source, int targetSize) {
        if (source == null) return;
        for (InterviewQuestion question : source) {
            if (target.size() >= targetSize) return;
            if (ids.add(question.id)) target.add(question);
        }
    }

    private int selectedCount() {
        int id = rgCount.getCheckedRadioButtonId();
        if (id == R.id.rb_count_8) return 8;
        if (id == R.id.rb_count_16) return 16;
        return 12;
    }

    private void showCurrentQuestion() {
        if (currentIndex >= questions.size()) {
            showReport();
            return;
        }
        InterviewQuestion question = currentQuestion();
        questionStartedAt = System.currentTimeMillis();
        answerVisible = false;
        saving = false;
        tvProgress.setText("第 " + (currentIndex + 1) + " / " + questions.size() + " 题");
        tvMeta.setText(categoryText(question));
        tvQuestion.setText(question.questionText);
        etAnswer.setText("");
        cardAnswer.setVisibility(View.GONE);
        btnToggleAnswer.setText("查看参考答案");
    }

    private InterviewQuestion currentQuestion() {
        return questions.get(currentIndex);
    }

    private void toggleAnswer() {
        if (questions.isEmpty()) return;
        answerVisible = !answerVisible;
        if (answerVisible) {
            String answer = answerOf(currentQuestion());
            markwon.setMarkdown(tvAnswer, answer.isEmpty() ? "暂无参考答案" : answer);
            cardAnswer.setVisibility(View.VISIBLE);
            btnToggleAnswer.setText("收起参考答案");
        } else {
            cardAnswer.setVisibility(View.GONE);
            btnToggleAnswer.setText("查看参考答案");
        }
    }

    private void submitScore(int score) {
        if (saving || questions.isEmpty()) return;
        saving = true;
        InterviewQuestion question = currentQuestion();
        String userAnswer = etAnswer.getText() == null
                ? "" : etAnswer.getText().toString().trim();
        if (userAnswer.isEmpty()) userAnswer = "未作答";
        long attemptedAt = System.currentTimeMillis();
        long durationMs = attemptedAt - questionStartedAt;

        String finalUserAnswer = userAnswer;
        DbExecutor.runOnDbThenUi(this,
                () -> {
                    PracticeAttempt attempt = new PracticeAttempt();
                    attempt.questionId = question.id;
                    attempt.userAnswer = finalUserAnswer;
                    attempt.score = score;
                    attempt.status = "MOCK_INTERVIEW";
                    attempt.aiFeedback = "模拟面试自评，用时 " + Math.max(0, durationMs / 1000) + " 秒";
                    attempt.attemptedAt = attemptedAt;
                    db.runInTransaction(() -> {
                        db.interviewDao().insertAttempt(attempt);
                        updateQuestionStats(question, score, attemptedAt);
                        db.interviewDao().updateQuestion(question);
                    });
                    return true;
                },
                ignored -> {
                    scores.add(score);
                    currentIndex++;
                    showCurrentQuestion();
                });
    }

    private void updateQuestionStats(InterviewQuestion question, int score, long attemptedAt) {
        question.attemptCount += 1;
        question.lastScore = score;
        if (score > question.highestScore) question.highestScore = score;
        question.lastPracticedAt = attemptedAt;
        question.masteryLevel = (question.lastScore + question.highestScore) / 2;
        long oneDay = 24L * 60 * 60 * 1000;
        if (score < 60) {
            question.nextReviewTime = attemptedAt + oneDay;
        } else if (score < 80) {
            question.nextReviewTime = attemptedAt + 3 * oneDay;
        } else {
            question.nextReviewTime = attemptedAt + 7 * oneDay;
        }
        question.updatedAt = attemptedAt;
    }

    private void showReport() {
        layoutRunning.setVisibility(View.GONE);
        layoutFinished.setVisibility(View.VISIBLE);
        int total = scores.size();
        int sum = 0;
        int low = 0;
        for (int score : scores) {
            sum += score;
            if (score < 60) low++;
        }
        int average = total == 0 ? 0 : sum / total;
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
        tvReport.setText("完成 " + total + " 题\n平均分 " + average
                + "\n低分题 " + low + " 题\n用时 " + (seconds / 60) + "分" + (seconds % 60) + "秒\n\n"
                + "低于 60 分的题会在 1 天后复习，60-79 分会在 3 天后复习，80 分以上会在 7 天后复习。");
    }

    private String answerOf(InterviewQuestion question) {
        if (question.referenceAnswerMarkdown != null && !question.referenceAnswerMarkdown.trim().isEmpty()) {
            return question.referenceAnswerMarkdown;
        }
        if (question.plainTextAnswer != null && !question.plainTextAnswer.trim().isEmpty()) {
            return question.plainTextAnswer;
        }
        if (question.referenceAnswer != null && !question.referenceAnswer.trim().isEmpty()) {
            return question.referenceAnswer;
        }
        return "";
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
}

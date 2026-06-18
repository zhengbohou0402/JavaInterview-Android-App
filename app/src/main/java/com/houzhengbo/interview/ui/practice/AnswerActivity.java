package com.houzhengbo.interview.ui.practice;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.utils.DbExecutor;

import io.noties.markwon.Markwon;

public class AnswerActivity extends AppCompatActivity {

    public static final String EXTRA_QUESTION_ID = "QUESTION_ID";

    private TextView tvQuestion;
    private TextView tvCategory;
    private TextView tvAnswer;
    private TextView tvEmpty;
    private TextView tvCustomHint;
    private Button btnEditAnswer;
    private Markwon markwon;
    private AppDatabase db;
    private InterviewQuestion currentQuestion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_answer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_answer);
        toolbar.setNavigationOnClickListener(v -> finish());
        tvQuestion = findViewById(R.id.tv_answer_question);
        tvCategory = findViewById(R.id.tv_answer_category);
        tvAnswer = findViewById(R.id.tv_answer_content);
        tvEmpty = findViewById(R.id.tv_answer_empty);
        tvCustomHint = findViewById(R.id.tv_answer_custom_hint);
        btnEditAnswer = findViewById(R.id.btn_edit_answer);
        markwon = Markwon.create(this);
        db = InterviewApplication.getInstance().getDatabase();
        btnEditAnswer.setOnClickListener(v -> showEditAnswerDialog());

        int questionId = getIntent().getIntExtra(EXTRA_QUESTION_ID, -1);
        if (questionId < 0) {
            Toast.makeText(this, "无效的题目", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        DbExecutor.runOnDbThenUi(this,
                () -> db.interviewDao().getQuestionById(questionId),
                this::showQuestion);
    }

    private void showQuestion(InterviewQuestion question) {
        if (question == null) {
            Toast.makeText(this, "未找到该题目", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        currentQuestion = question;

        tvQuestion.setText(question.questionText);
        tvCategory.setText(question.category == null || question.category.isEmpty()
                ? "参考答案" : question.category);

        String answer = cleanHtmlArtifacts(getDisplayedAnswer(question));
        tvCustomHint.setVisibility(hasLocalAnswer(question) ? View.VISIBLE : View.GONE);

        if (answer.isEmpty()) {
            tvAnswer.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            tvAnswer.setVisibility(View.VISIBLE);
            markwon.setMarkdown(tvAnswer, answer);
        }
    }

    private void showEditAnswerDialog() {
        if (currentQuestion == null) return;

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_edit_answer, null);
        TextInputEditText input = content.findViewById(R.id.et_edit_answer);
        String currentAnswer = getDisplayedAnswer(currentQuestion);
        input.setText(currentAnswer);
        input.setSelection(currentAnswer.length());

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("编辑参考答案")
                .setMessage("答案仅保存在本机，支持 Markdown。同步题库时不会覆盖本地修改。")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null);

        boolean hasLocalAnswer = hasLocalAnswer(currentQuestion);
        if (hasLocalAnswer) {
            builder.setNeutralButton("恢复内置答案", null);
        }

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String edited = input.getText() == null ? "" : input.getText().toString().trim();
                if (edited.isEmpty()) {
                    input.setError("答案不能为空；如需恢复原答案，请点击“恢复内置答案”");
                    return;
                }
                saveLocalAnswer(edited, dialog);
            });
            if (hasLocalAnswer) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener(v -> restoreBuiltInAnswer(dialog));
            }
        });
        dialog.show();
    }

    private void saveLocalAnswer(String answer, AlertDialog dialog) {
        currentQuestion.referenceAnswer = answer;
        currentQuestion.updatedAt = System.currentTimeMillis();
        DbExecutor.runOnDbThenUi(this,
                () -> db.interviewDao().updateQuestion(currentQuestion),
                () -> {
                    dialog.dismiss();
                    showQuestion(currentQuestion);
                    Toast.makeText(this, "答案已保存到本机", Toast.LENGTH_SHORT).show();
                });
    }

    private void restoreBuiltInAnswer(AlertDialog dialog) {
        currentQuestion.referenceAnswer = null;
        currentQuestion.updatedAt = System.currentTimeMillis();
        DbExecutor.runOnDbThenUi(this,
                () -> db.interviewDao().updateQuestion(currentQuestion),
                () -> {
                    dialog.dismiss();
                    showQuestion(currentQuestion);
                    Toast.makeText(this, "已恢复内置答案", Toast.LENGTH_SHORT).show();
                });
    }

    private boolean hasLocalAnswer(InterviewQuestion question) {
        return question.referenceAnswer != null && !question.referenceAnswer.trim().isEmpty();
    }

    private String getDisplayedAnswer(InterviewQuestion question) {
        String answer = question.referenceAnswer;
        if (answer == null || answer.trim().isEmpty()) answer = question.referenceAnswerMarkdown;
        if (answer == null || answer.trim().isEmpty()) answer = question.plainTextAnswer;
        return answer == null ? "" : answer;
    }

    private String cleanHtmlArtifacts(String text) {
        if (text == null) return "";
        text = text.replaceAll("<img[^>]*/?>", "");
        text = text.replaceAll("<[^>]+>", "");
        StringBuilder cleaned = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("公众号") || trimmed.contains("打赏")
                    || trimmed.contains("关注我") || trimmed.contains("扫码")) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        return cleaned.toString().trim();
    }
}

package com.houzhengbo.interview.ui.practice;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.houzhengbo.interview.InterviewApplication;

import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.AppSettings;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.network.AiClient;
import com.houzhengbo.interview.utils.DbExecutor;

public class PracticeActivity extends AppCompatActivity {

    private TextView tvQuestionText, tvAiFeedback, tvSourceLink;
    private EditText etAnswerInput;
    private Button btnSubmit, btnSkip, btnShowAnswer;
    private ProgressBar progressAi;
    private View llFeedback;
    private io.noties.markwon.Markwon markwon;

    private AppDatabase db;
    private AiClient aiClient;
    private InterviewQuestion currentQuestion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_practice);

        tvQuestionText = findViewById(R.id.tv_question_text);
        tvSourceLink = findViewById(R.id.tv_source_link);
        tvAiFeedback = findViewById(R.id.tv_ai_feedback);
        etAnswerInput = findViewById(R.id.et_answer_input);
        btnSubmit = findViewById(R.id.btn_submit);
        btnSkip = findViewById(R.id.btn_skip);
        progressAi = findViewById(R.id.progress_ai);
        llFeedback = findViewById(R.id.ll_feedback);

        // 初始化 Markwon：核心 + 表格插件（GFM 表格 | col | col | 渲染成真表格）。
        // build.gradle 已显式提供 org.commonmark:commonmark-ext-gfm-tables:0.21.0，
        // TablePlugin 内部依赖的 TablesExtension 运行时可用，不会再 NoClassDefFoundError。
        markwon = io.noties.markwon.Markwon.builder(this)
                .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(this))
                .build();

        // 参考答案在独立页面展示，返回后当前答题内容和评分结果会保留。
        btnShowAnswer = findViewById(R.id.btn_show_answer);
        btnShowAnswer.setOnClickListener(v -> openAnswerPage());

        db = InterviewApplication.getInstance().getDatabase();
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        aiClient = new com.houzhengbo.interview.network.AiClient(client, new com.houzhengbo.interview.network.ApiKeyProvider() {
            @Override
            public String getApiKey() {
                return com.houzhengbo.interview.utils.KeystoreHelper.getApiKey(PracticeActivity.this);
            }
        });

        btnSubmit.setEnabled(false); // Disable initially

        int questionId = getIntent().getIntExtra("QUESTION_ID", -1);
        if (questionId != -1) {
            loadQuestion(questionId);
        } else {
            Toast.makeText(this, "无效的题目", Toast.LENGTH_SHORT).show();
            finish();
        }

        btnSubmit.setOnClickListener(v -> submitAnswer());
        btnSkip.setOnClickListener(v -> finish());
    }

    private void loadQuestion(int id) {
        btnSubmit.setEnabled(false);
        DbExecutor.runOnDbThenUi(PracticeActivity.this,
                () -> db.interviewDao().getQuestionById(id),
                loaded -> {
                    currentQuestion = loaded;
                    if (currentQuestion != null) {
                        tvQuestionText.setText(currentQuestion.questionText);
                        bindSourceLink(currentQuestion);
                        btnSubmit.setEnabled(true);
                    } else {
                        Toast.makeText(PracticeActivity.this, "未找到该题目", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
    }

    private void submitAnswer() {
        String answer = etAnswerInput.getText().toString().trim();
        if (answer.isEmpty()) {
            Toast.makeText(this, "请输入答案", Toast.LENGTH_SHORT).show();
            return;
        }

        // Dismiss soft keyboard after submission
        hideKeyboard();

        btnSubmit.setEnabled(false);
        progressAi.setVisibility(View.VISIBLE);

        DbExecutor.runOnDbThenUi(PracticeActivity.this,
                () -> {
                    com.houzhengbo.interview.data.entity.PracticeAttempt attempt = new com.houzhengbo.interview.data.entity.PracticeAttempt();
                    attempt.questionId = currentQuestion.id;
                    attempt.userAnswer = answer;
                    attempt.score = null;
                    attempt.status = "PENDING";
                    attempt.attemptedAt = System.currentTimeMillis();

                    final long attemptId = db.interviewDao().insertAttempt(attempt);
                    return new Object[]{attempt, attemptId};
                },
                attemptAndId -> {
                    if (isFinishing() || isDestroyed()) return;
                    btnSubmit.setEnabled(true);
                    progressAi.setVisibility(View.GONE);
                    Object[] arr = (Object[]) attemptAndId;
                    com.houzhengbo.interview.data.entity.PracticeAttempt attempt = (com.houzhengbo.interview.data.entity.PracticeAttempt) arr[0];
                    long attemptId = (Long) arr[1];

                    // Continue AI evaluation on a background task; the result
                    // handlers below still use DbExecutor.runOnDbThenUi so they
                    // cannot run on a destroyed Activity.
                    evaluateAnswerAsync(attempt, attemptId, answer);
                });
    }

    private void evaluateAnswerAsync(com.houzhengbo.interview.data.entity.PracticeAttempt attempt,
                                     long attemptId,
                                     String answer) {
        DbExecutor.runOnDbThenUi(PracticeActivity.this,
                () -> {
                    String feedbackPrefix = "[NETWORK_ERROR]";
                    try {
                        AppSettings settings = db.interviewDao().getSettings();
                        String plainKey = com.houzhengbo.interview.utils.KeystoreHelper.getApiKey(getApplicationContext());
                        if (plainKey == null || plainKey.isEmpty()) {
                            feedbackPrefix = "[NO_API_KEY]";
                            throw new Exception("请先在我的页面配置 AI 密钥");
                        }

                        String refAnswer = currentQuestion.referenceAnswer != null ? currentQuestion.referenceAnswer : "";
                        if (refAnswer.isEmpty() && currentQuestion.referenceAnswerMarkdown != null) {
                            refAnswer = currentQuestion.referenceAnswerMarkdown;
                        }
                        if (refAnswer.isEmpty() && currentQuestion.plainTextAnswer != null) {
                            refAnswer = currentQuestion.plainTextAnswer;
                        }
                        String keywords = currentQuestion.keywords != null ? currentQuestion.keywords : "";

                        String feedbackStr;
                        try {
                            feedbackStr = aiClient.evaluateAnswer(settings, currentQuestion.questionText, refAnswer, keywords, answer);
                        } catch (java.net.SocketTimeoutException e) {
                            feedbackPrefix = "[TIMEOUT]";
                            throw e;
                        } catch (java.io.IOException e) {
                            if (e.getMessage() != null && e.getMessage().contains("429")) {
                                feedbackPrefix = "[RATE_LIMIT]";
                            } else if (e.getMessage() != null && e.getMessage().contains("401")) {
                                feedbackPrefix = "[NO_API_KEY]";
                            } else {
                                feedbackPrefix = "[NETWORK_ERROR]";
                            }
                            throw e;
                        }

                        com.google.gson.JsonObject contentObj;
                        try {
                            contentObj = com.houzhengbo.interview.network.AiResponseParser.parseEvaluationResponse(feedbackStr);
                        } catch (Exception e) {
                            feedbackPrefix = "[INVALID_JSON]";
                            throw e;
                        }

                        String scoreStr = contentObj.has("score") ? contentObj.get("score").getAsString() : "0";
                        String hitPoints = contentObj.has("hitPoints") ? contentObj.get("hitPoints").getAsString() : "";
                        String missingPoints = contentObj.has("missingPoints") ? contentObj.get("missingPoints").getAsString() : "";
                        String improvedAnswer = contentObj.has("improvedAnswer") ? contentObj.get("improvedAnswer").getAsString() : "";
                        String followUpQuestion = contentObj.has("followUpQuestion") ? contentObj.get("followUpQuestion").getAsString() : "";

                        String formattedFeedback = "得分: " + scoreStr + "\n\n" +
                                "命中要点: \n" + hitPoints + "\n\n" +
                                "遗漏点: \n" + missingPoints + "\n\n" +
                                "改进版答案: \n" + improvedAnswer + "\n\n" +
                                "追问: \n" + followUpQuestion;

                        attempt.id = (int) attemptId;
                        attempt.score = Integer.parseInt(scoreStr);
                        attempt.status = "SCORED";
                        attempt.hitPoints = hitPoints;
                        attempt.missingPoints = missingPoints;
                        attempt.improvedAnswer = improvedAnswer;
                        attempt.followUpQuestion = followUpQuestion;
                        attempt.aiFeedback = formattedFeedback;

                        db.runInTransaction(() -> {
                            db.interviewDao().updateAttempt(attempt);
                            currentQuestion.attemptCount += 1;
                            currentQuestion.lastScore = attempt.score;
                            if (attempt.score > currentQuestion.highestScore) {
                                currentQuestion.highestScore = attempt.score;
                            }
                            currentQuestion.lastPracticedAt = attempt.attemptedAt;
                            currentQuestion.masteryLevel = (currentQuestion.lastScore + currentQuestion.highestScore) / 2;
                            long oneDay = 24L * 60 * 60 * 1000;
                            if (attempt.score < 60) {
                                currentQuestion.nextReviewTime = attempt.attemptedAt + oneDay;
                            } else if (attempt.score < 80) {
                                currentQuestion.nextReviewTime = attempt.attemptedAt + 3 * oneDay;
                            } else {
                                currentQuestion.nextReviewTime = attempt.attemptedAt + 7 * oneDay;
                            }
                            db.interviewDao().updateQuestion(currentQuestion);
                        });
                        return new Object[]{"OK", formattedFeedback, (Integer) attempt.score};
                    } catch (Exception e) {
                        e.printStackTrace();
                        attempt.id = (int) attemptId;
                        attempt.status = "PENDING";
                        attempt.aiFeedback = feedbackPrefix + " 评分失败: " + e.getMessage();
                        db.interviewDao().updateAttempt(attempt);
                        return new Object[]{"FAIL", feedbackPrefix + " 评分失败: " + e.getMessage() + "\n已自动保存为后台待评分状态。"};
                    }
                },
                result -> {
                    if (isFinishing() || isDestroyed()) return;
                    Object[] arr = (Object[]) result;
                    String status = (String) arr[0];
                    progressAi.setVisibility(View.GONE);
                    llFeedback.setVisibility(View.VISIBLE);

                    if ("OK".equals(status)) {
                        String formattedFeedback = (String) arr[1];
                        Integer score = (Integer) arr[2];
                        renderFeedback(formattedFeedback);
                        btnSubmit.setText("完成并退出");
                        btnSubmit.setEnabled(true);
                        btnSubmit.setOnClickListener(v -> finish());
                    } else {
                        String msg = (String) arr[1];
                        renderFeedback(msg);
                        btnSubmit.setText("完成并退出");
                        btnSubmit.setEnabled(true);
                        btnSubmit.setOnClickListener(v -> finish());
                        Toast.makeText(PracticeActivity.this, "评分失败，已保存为待评分状态", Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * 绑定题目来源链接。仅 GUIDE 题（有 sourceUrl）显示"来源: JavaGuide"并点击打开浏览器；
     * 简历题/无来源的题隐藏。版权与来源透明：每道题都能溯源到原文。
     */
    private void bindSourceLink(InterviewQuestion q) {
        String url = q.sourceUrl;
        if (url == null || url.isEmpty()) {
            tvSourceLink.setVisibility(View.GONE);
            tvSourceLink.setOnClickListener(null);
            return;
        }
        String label = q.sourceRepository != null && q.sourceRepository.contains("JavaGuide")
                ? "来源: JavaGuide" : "查看来源";
        tvSourceLink.setText(label);
        tvSourceLink.setVisibility(View.VISIBLE);
        tvSourceLink.setOnClickListener(v -> {
            try {
                android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开来源链接", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openAnswerPage() {
        if (currentQuestion == null) return;
        android.content.Intent intent = new android.content.Intent(this, AnswerActivity.class);
        intent.putExtra(AnswerActivity.EXTRA_QUESTION_ID, currentQuestion.id);
        startActivity(intent);
    }

    /**
     * Render AI feedback with Markwon for proper formatting (bold, lists, etc.).
     */
    private void renderFeedback(String feedback) {
        if (feedback == null) feedback = "";
        feedback = cleanHtmlArtifacts(feedback);
        markwon.setMarkdown(tvAiFeedback, feedback);
    }

    /**
     * Strip HTML img tags, ad lines, and navigation noise from markdown text.
     */
    private String cleanHtmlArtifacts(String text) {
        if (text == null) return "";
        // Remove <img ...> tags
        text = text.replaceAll("<img[^>]*/?>", "");
        // Remove other HTML tags but keep content
        text = text.replaceAll("<[^>]+>", "");
        // Remove lines that are pure navigation/ad content (公众号, 打赏, etc.)
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.contains("公众号") || trimmed.contains("打赏") ||
                trimmed.contains("关注我") || trimmed.contains("扫码")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Dismiss the soft keyboard.
     */
    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            android.view.View currentFocus = getCurrentFocus();
            if (currentFocus != null) {
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        }
    }
}

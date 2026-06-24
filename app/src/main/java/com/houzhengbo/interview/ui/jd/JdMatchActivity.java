package com.houzhengbo.interview.ui.jd;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.AppSettings;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.data.entity.ResumeProfile;
import com.houzhengbo.interview.network.AiClient;
import com.houzhengbo.interview.network.AiResponseParser;
import com.houzhengbo.interview.ui.practice.PracticeActivity;
import com.houzhengbo.interview.utils.DbExecutor;
import com.houzhengbo.interview.utils.KeystoreHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class JdMatchActivity extends AppCompatActivity {

    private static final String PREFS = "jd_match";
    private static final String KEY_LAST_JD = "last_jd";

    private static final String[] SKILL_KEYWORDS = {
            "Java", "Spring Boot", "Spring Cloud", "MyBatis", "若依", "RuoYi",
            "Redis", "Caffeine", "ClickHouse", "MySQL", "多数据源", "动态数据源",
            "WebFlux", "RAG", "LangChain4j", "Qdrant", "BM25", "RRF", "重排序",
            "消息队列", "RabbitMQ", "RocketMQ", "Kafka", "Lua", "秒杀",
            "ThreadLocal", "JWT", "Token", "缓存穿透", "缓存击穿", "缓存雪崩",
            "ZSet", "BitMap", "Set", "CAS", "乐观锁", "大屏", "轮询", "WebSocket"
    };

    private EditText etJdContent;
    private Button btnAnalyze;
    private ProgressBar progress;
    private MaterialCardView cardReport;
    private TextView tvReport;
    private TextView tvSuggestionsTitle;
    private LinearLayout layoutSuggestions;

    private AppDatabase db;
    private AiClient aiClient;
    private io.noties.markwon.Markwon markwon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jd_match);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_jd_match);
        toolbar.setNavigationOnClickListener(v -> finish());

        etJdContent = findViewById(R.id.et_jd_content);
        btnAnalyze = findViewById(R.id.btn_analyze_jd);
        progress = findViewById(R.id.progress_jd);
        cardReport = findViewById(R.id.card_jd_report);
        tvReport = findViewById(R.id.tv_jd_report);
        tvSuggestionsTitle = findViewById(R.id.tv_jd_suggestions_title);
        layoutSuggestions = findViewById(R.id.layout_jd_suggestions);

        db = InterviewApplication.getInstance().getDatabase();
        aiClient = new AiClient(null, () -> KeystoreHelper.getApiKey(JdMatchActivity.this));
        markwon = io.noties.markwon.Markwon.builder(this).build();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        etJdContent.setText(prefs.getString(KEY_LAST_JD, ""));
        btnAnalyze.setOnClickListener(v -> analyzeJd());
    }

    private void analyzeJd() {
        String jd = etJdContent.getText().toString().trim();
        if (jd.isEmpty()) {
            Toast.makeText(this, "先粘贴岗位 JD", Toast.LENGTH_SHORT).show();
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_JD, jd)
                .apply();

        btnAnalyze.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        cardReport.setVisibility(View.GONE);
        tvSuggestionsTitle.setVisibility(View.GONE);
        layoutSuggestions.setVisibility(View.GONE);
        layoutSuggestions.removeAllViews();

        DbExecutor.runOnDbThenUi(this,
                () -> {
                    ResumeProfile resume = db.interviewDao().getLatestResume();
                    AppSettings settings = db.interviewDao().getSettings();
                    if (settings == null) settings = new AppSettings();

                    List<InterviewQuestion> projectQuestions = db.interviewDao().getMockProjectQuestions(80);
                    List<ScoredQuestion> suggestions = rankQuestions(jd, projectQuestions);
                    String localReport = buildLocalReport(jd, resume, suggestions);

                    String apiKey = KeystoreHelper.getApiKey(getApplicationContext());
                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        return new JdResult(localReport + "\n\n> 未配置 AI Key，本次使用本地关键词匹配。", suggestions);
                    }
                    if (resume == null || resume.content == null || resume.content.trim().isEmpty()) {
                        return new JdResult(localReport + "\n\n> 未导入简历，AI 匹配需要先在「我的」页导入简历。", suggestions);
                    }

                    try {
                        String raw = aiClient.analyzeJdMatch(settings, resume.content, jd);
                        String aiReport = AiResponseParser.extractMessageContent(raw);
                        return new JdResult(aiReport + "\n\n---\n\n" + localReport, suggestions);
                    } catch (Exception e) {
                        return new JdResult(localReport + "\n\n> AI 分析失败，已回退到本地匹配："
                                + safeMessage(e), suggestions);
                    }
                },
                result -> {
                    btnAnalyze.setEnabled(true);
                    progress.setVisibility(View.GONE);
                    cardReport.setVisibility(View.VISIBLE);
                    markwon.setMarkdown(tvReport, result.report);
                    bindSuggestions(result.suggestions);
                });
    }

    private String buildLocalReport(String jd, ResumeProfile resume, List<ScoredQuestion> suggestions) {
        List<String> jdSkills = findSkills(jd);
        List<String> resumeSkills = findSkills(resume != null ? resume.content : "");
        Set<String> resumeSet = new HashSet<>(resumeSkills);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String skill : jdSkills) {
            if (resumeSet.contains(skill)) matched.add(skill);
            else missing.add(skill);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 本地匹配概览\n\n");
        sb.append("- JD 命中关键词：").append(joinOrNone(jdSkills)).append("\n");
        sb.append("- 简历已覆盖：").append(joinOrNone(matched)).append("\n");
        sb.append("- 需要重点准备：").append(joinOrNone(missing)).append("\n\n");

        if (resume == null || resume.content == null || resume.content.trim().isEmpty()) {
            sb.append("提示：当前没有读取到已导入简历，所以只能基于 JD 和题库做初步匹配。\n\n");
        }

        if (!missing.isEmpty()) {
            sb.append("### 准备建议\n");
            for (String skill : missing) {
                sb.append("- 补一段和 ").append(skill).append(" 相关的项目表达：背景、为什么用、怎么落地、踩过什么坑、如何验证效果。\n");
            }
            sb.append("\n");
        }

        if (!suggestions.isEmpty()) {
            sb.append("### 推荐练习方向\n");
            int limit = Math.min(5, suggestions.size());
            for (int i = 0; i < limit; i++) {
                InterviewQuestion q = suggestions.get(i).question;
                sb.append(i + 1)
                        .append(". ")
                        .append(trim(q.questionText, 46))
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    private List<String> findSkills(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : SKILL_KEYWORDS) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                result.add(keyword);
            }
        }
        return result;
    }

    private List<ScoredQuestion> rankQuestions(String jd, List<InterviewQuestion> questions) {
        List<String> jdSkills = findSkills(jd);
        List<ScoredQuestion> scored = new ArrayList<>();
        String jdLower = jd.toLowerCase(Locale.ROOT);

        for (InterviewQuestion question : questions) {
            String haystack = (
                    safe(question.questionText) + " "
                            + safe(question.parentCategory) + " "
                            + safe(question.category) + " "
                            + safe(question.keywords) + " "
                            + safe(question.referenceAnswer) + " "
                            + safe(question.referenceAnswerMarkdown)
            ).toLowerCase(Locale.ROOT);

            int score = 0;
            for (String skill : jdSkills) {
                if (haystack.contains(skill.toLowerCase(Locale.ROOT))) score += 3;
            }
            for (String keyword : SKILL_KEYWORDS) {
                String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
                if (jdLower.contains(lowerKeyword) && haystack.contains(lowerKeyword)) score += 1;
            }
            if (score > 0) {
                scored.add(new ScoredQuestion(question, score));
            }
        }

        if (scored.isEmpty()) {
            int limit = Math.min(6, questions.size());
            for (int i = 0; i < limit; i++) {
                scored.add(new ScoredQuestion(questions.get(i), 0));
            }
        }

        Collections.sort(scored, Comparator.comparingInt((ScoredQuestion item) -> item.score).reversed());
        if (scored.size() > 6) {
            return new ArrayList<>(scored.subList(0, 6));
        }
        return scored;
    }

    private void bindSuggestions(List<ScoredQuestion> suggestions) {
        layoutSuggestions.removeAllViews();
        if (suggestions == null || suggestions.isEmpty()) {
            tvSuggestionsTitle.setVisibility(View.GONE);
            layoutSuggestions.setVisibility(View.GONE);
            return;
        }

        tvSuggestionsTitle.setVisibility(View.VISIBLE);
        layoutSuggestions.setVisibility(View.VISIBLE);
        for (ScoredQuestion scored : suggestions) {
            layoutSuggestions.addView(createQuestionCard(scored.question));
        }
    }

    private View createQuestionCard(InterviewQuestion question) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardParams);
        card.setClickable(true);
        card.setFocusable(true);
        card.setCardElevation(0);
        card.setRadius(dp(16));
        card.setStrokeWidth(dp(1));
        card.setCardBackgroundColor(MaterialColors.getColor(layoutSuggestions,
                com.google.android.material.R.attr.colorSurface));
        card.setStrokeColor(MaterialColors.getColor(layoutSuggestions,
                com.google.android.material.R.attr.colorOutline));
        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, PracticeActivity.class);
            intent.putExtra("QUESTION_ID", question.id);
            startActivity(intent);
        });

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView title = new TextView(this);
        title.setText(question.questionText);
        title.setTextSize(16);
        title.setTextColor(MaterialColors.getColor(layoutSuggestions,
                com.google.android.material.R.attr.colorOnSurface));
        title.setMaxLines(3);

        TextView meta = new TextView(this);
        meta.setText(firstNonEmpty(question.parentCategory, question.category, "项目面试"));
        meta.setTextSize(13);
        meta.setTextColor(MaterialColors.getColor(layoutSuggestions,
                com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        metaParams.setMargins(0, dp(6), 0, 0);
        meta.setLayoutParams(metaParams);

        content.addView(title);
        content.addView(meta);
        card.addView(content);
        return card;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String joinOrNone(List<String> values) {
        if (values == null || values.isEmpty()) return "暂无";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private String firstNonEmpty(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) return first;
        if (second != null && !second.trim().isEmpty()) return second;
        return fallback;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message;
    }

    private String trim(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max) + "...";
    }

    private static class ScoredQuestion {
        final InterviewQuestion question;
        final int score;

        ScoredQuestion(InterviewQuestion question, int score) {
            this.question = question;
            this.score = score;
        }
    }

    private static class JdResult {
        final String report;
        final List<ScoredQuestion> suggestions;

        JdResult(String report, List<ScoredQuestion> suggestions) {
            this.report = report;
            this.suggestions = suggestions;
        }
    }
}

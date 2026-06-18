package com.houzhengbo.interview.ui.bank;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.houzhengbo.interview.InterviewApplication;
import com.houzhengbo.interview.R;
import com.houzhengbo.interview.data.AppDatabase;
import com.houzhengbo.interview.data.entity.InterviewQuestion;
import com.houzhengbo.interview.ui.practice.PracticeActivity;
import com.houzhengbo.interview.utils.DbExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class QuestionBankFragment extends Fragment {

    private static final String SECTION_PROJECT = "PROJECT";
    private static final String SECTION_KNOWLEDGE = "KNOWLEDGE";
    private static final String ALL_PARENT_CATEGORIES = "全部大类";
    private static final String ALL_SUB_CATEGORIES = "全部小类";

    private final List<String> parentCategories = new ArrayList<>();
    private final List<String> subCategories = new ArrayList<>();

    private RecyclerView recyclerView;
    private QuestionAdapter adapter;
    private AppDatabase db;
    private TextView etSearchKeyword;
    private View btnClearFilters;
    private View btnAddQuestion;
    private ChipGroup toggleSection;
    private TextInputEditText dropdownParentCategory;
    private TextInputEditText dropdownCategory;
    private Chip chipFavoritesOnly;
    private View layoutEmptyState;
    private TextView tvEmptyTitle;
    private TextView tvEmptySubtitle;
    private TextView tvQuestionCount;
    private boolean suppressFilterCallbacks;
    private int hierarchyRequestVersion;
    private int searchRequestVersion;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_bank, container, false);
        recyclerView = view.findViewById(R.id.rv_questions);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        etSearchKeyword = view.findViewById(R.id.et_search_keyword);
        btnClearFilters = view.findViewById(R.id.btn_clear_filters);
        btnAddQuestion = view.findViewById(R.id.btn_add_question);
        toggleSection = view.findViewById(R.id.toggle_section);
        dropdownParentCategory = view.findViewById(R.id.dropdown_parent_category);
        dropdownCategory = view.findViewById(R.id.dropdown_category);
        chipFavoritesOnly = view.findViewById(R.id.chip_favorites_only);
        layoutEmptyState = view.findViewById(R.id.layout_empty_state);
        tvEmptyTitle = view.findViewById(R.id.tv_empty_title);
        tvEmptySubtitle = view.findViewById(R.id.tv_empty_subtitle);
        tvQuestionCount = view.findViewById(R.id.tv_question_count);
        adapter = new QuestionAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);
        db = InterviewApplication.getInstance().getDatabase();
        setupFilters();
        refreshCategoryHierarchy();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (toggleSection != null) performSearch();
    }

    private void setupFilters() {
        toggleSection.check(R.id.chip_section_all);
        setParentOptions(new ArrayList<>());
        setSubCategoryOptions(new ArrayList<>());

        etSearchKeyword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch();
                return true;
            }
            return false;
        });
        etSearchKeyword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                updateClearFiltersVisibility();
            }
        });
        chipFavoritesOnly.setOnCheckedChangeListener((buttonView, checked) -> {
            if (!suppressFilterCallbacks) {
                updateClearFiltersVisibility();
                performSearch();
            }
        });
        toggleSection.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty() && !suppressFilterCallbacks) {
                updateClearFiltersVisibility();
                refreshCategoryHierarchy();
            }
        });
        dropdownParentCategory.setOnClickListener(v -> showParentCategoryDialog());
        dropdownCategory.setOnClickListener(v -> showSubCategoryDialog());
        btnClearFilters.setOnClickListener(v -> clearAllFilters());
        btnAddQuestion.setOnClickListener(v -> showAddQuestionDialog());
    }

    private void showAddQuestionDialog() {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_add_question, null, false);
        TextInputLayout questionLayout = content.findViewById(R.id.til_manual_question);
        TextInputEditText questionInput = content.findViewById(R.id.et_manual_question);
        TextInputEditText answerInput = content.findViewById(R.id.et_manual_answer);
        TextInputEditText parentInput = content.findViewById(R.id.et_manual_parent_category);
        TextInputEditText categoryInput = content.findViewById(R.id.et_manual_category);
        RadioGroup sectionGroup = content.findViewById(R.id.rg_manual_section);

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle("添加题目")
                .setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String questionText = textOf(questionInput).trim();
            if (questionText.isEmpty()) {
                questionLayout.setError("请输入题目");
                return;
            }
            questionLayout.setError(null);
            boolean project = sectionGroup.getCheckedRadioButtonId() == R.id.rb_manual_project;
            saveManualQuestion(
                    questionText,
                    textOf(answerInput).trim(),
                    textOf(parentInput).trim(),
                    textOf(categoryInput).trim(),
                    project,
                    dialog);
        }));
        dialog.show();
    }

    private void saveManualQuestion(String questionText, String answer, String parentCategory,
                                    String category, boolean project,
                                    androidx.appcompat.app.AlertDialog dialog) {
        long now = System.currentTimeMillis();
        String identity = UUID.randomUUID().toString();
        InterviewQuestion question = new InterviewQuestion();
        question.sourceType = project ? "CUSTOM" : "MANUAL";
        question.parentCategory = parentCategory.isEmpty()
                ? (project ? "我的项目" : "自建题目") : parentCategory;
        question.category = category.isEmpty() ? "未分类" : category;
        question.questionText = questionText;
        question.referenceAnswer = answer;
        question.referenceAnswerMarkdown = answer;
        question.plainTextAnswer = answer;
        question.questionType = project ? SECTION_PROJECT : SECTION_KNOWLEDGE;
        question.sourceRepository = project ? "manual/project" : "manual/knowledge";
        question.sourceDocumentPath = "manual/" + identity;
        question.sourceHeadingAnchor = identity;
        question.sourceUrl = "local://manual-question/" + identity;
        question.createdAt = now;
        question.updatedAt = now;
        question.nextReviewTime = now;
        question.archived = false;

        DbExecutor.runOnDbThenUi(this,
                () -> db.interviewDao().insertQuestion(question),
                id -> {
                    if (!isAdded()) return;
                    dialog.dismiss();
                    Toast.makeText(requireContext(), "题目已添加", Toast.LENGTH_SHORT).show();
                    refreshCategoryHierarchy();
                });
    }

    private void showParentCategoryDialog() {
        int selected = Math.max(0, parentCategories.indexOf(textOf(dropdownParentCategory)));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择大类")
                .setSingleChoiceItems(parentCategories.toArray(new String[0]), selected,
                        (dialog, which) -> {
                            dropdownParentCategory.setText(parentCategories.get(which));
                            dialog.dismiss();
                            updateClearFiltersVisibility();
                            refreshSubCategories();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSubCategoryDialog() {
        int selected = Math.max(0, subCategories.indexOf(textOf(dropdownCategory)));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择小类")
                .setSingleChoiceItems(subCategories.toArray(new String[0]), selected,
                        (dialog, which) -> {
                            dropdownCategory.setText(subCategories.get(which));
                            dialog.dismiss();
                            updateClearFiltersVisibility();
                            performSearch();
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshCategoryHierarchy() {
        final int version = ++hierarchyRequestVersion;
        final String section = getSectionGroup();
        DbExecutor.runOnDbThenUi(this,
                () -> db.interviewDao().getParentCategoriesByFilters(section),
                values -> {
                    if (!isCurrentHierarchyRequest(version, section)) return;
                    setParentOptions(values);
                    loadSubCategories(version, section, null);
                });
    }

    private void refreshSubCategories() {
        int version = ++hierarchyRequestVersion;
        String section = getSectionGroup();
        loadSubCategories(version, section, getSelectedParentCategory());
    }

    private void loadSubCategories(int version, String section, String parentCategory) {
        DbExecutor.runOnDbThenUi(this,
                () -> db.interviewDao().getCategoriesByFilters(section, parentCategory),
                values -> {
                    if (!isCurrentHierarchyRequest(version, section)
                            || !same(parentCategory, getSelectedParentCategory())) return;
                    setSubCategoryOptions(values);
                    performSearch();
                });
    }

    private boolean isCurrentHierarchyRequest(int version, String section) {
        return version == hierarchyRequestVersion && isAdded() && getView() != null
                && same(section, getSectionGroup());
    }

    private void setParentOptions(List<String> values) {
        suppressFilterCallbacks = true;
        parentCategories.clear();
        parentCategories.add(ALL_PARENT_CATEGORIES);
        if (values != null) parentCategories.addAll(values);
        dropdownParentCategory.setText(ALL_PARENT_CATEGORIES);
        suppressFilterCallbacks = false;
    }

    private void setSubCategoryOptions(List<String> values) {
        suppressFilterCallbacks = true;
        subCategories.clear();
        subCategories.add(ALL_SUB_CATEGORIES);
        if (values != null) subCategories.addAll(values);
        dropdownCategory.setText(ALL_SUB_CATEGORIES);
        suppressFilterCallbacks = false;
    }

    private void performSearch() {
        if (!isAdded() || toggleSection == null) return;
        final int version = ++searchRequestVersion;
        String rawKeyword = textOf(etSearchKeyword).trim();
        final String keyword = rawKeyword.isEmpty() ? null : rawKeyword;
        final String section = getSectionGroup();
        final String parent = getSelectedParentCategory();
        final String category = getSelectedSubCategory();
        final boolean favoritesOnly = chipFavoritesOnly.isChecked();

        DbExecutor.runOnDbThenUi(this,
                () -> db.interviewDao().searchQuestionsWithParent(
                        keyword, parent, category, section, favoritesOnly),
                questions -> {
                    if (version != searchRequestVersion || !isAdded() || getView() == null) return;
                    if (!matchesCurrentFilters(keyword, section, parent, category, favoritesOnly)) return;
                    adapter.setQuestions(questions == null ? new ArrayList<>() : questions);
                    int count = questions == null ? 0 : questions.size();
                    tvQuestionCount.setText(count + " 道题目");
                    updateEmptyState(count, keyword, section, parent, category, favoritesOnly);
                });
    }

    private boolean matchesCurrentFilters(String keyword, String section, String parent,
                                          String category, boolean favoritesOnly) {
        String currentKeyword = textOf(etSearchKeyword).trim();
        if (currentKeyword.isEmpty()) currentKeyword = null;
        return same(keyword, currentKeyword)
                && same(section, getSectionGroup())
                && same(parent, getSelectedParentCategory())
                && same(category, getSelectedSubCategory())
                && favoritesOnly == chipFavoritesOnly.isChecked();
    }

    private String getSectionGroup() {
        int id = toggleSection.getCheckedChipId();
        if (id == R.id.chip_section_project) return SECTION_PROJECT;
        if (id == R.id.chip_section_knowledge) return SECTION_KNOWLEDGE;
        return null;
    }

    private String getSelectedParentCategory() {
        String value = textOf(dropdownParentCategory);
        return value.isEmpty() || ALL_PARENT_CATEGORIES.equals(value) ? null : value;
    }

    private String getSelectedSubCategory() {
        String value = textOf(dropdownCategory);
        return value.isEmpty() || ALL_SUB_CATEGORIES.equals(value) ? null : value;
    }

    private String textOf(TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    private boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private void clearAllFilters() {
        suppressFilterCallbacks = true;
        toggleSection.check(R.id.chip_section_all);
        etSearchKeyword.setText("");
        chipFavoritesOnly.setChecked(false);
        dropdownParentCategory.setText(ALL_PARENT_CATEGORIES);
        dropdownCategory.setText(ALL_SUB_CATEGORIES);
        suppressFilterCallbacks = false;
        updateClearFiltersVisibility();
        refreshCategoryHierarchy();
    }

    private void updateClearFiltersVisibility() {
        btnClearFilters.setVisibility(hasActiveFilter() ? View.VISIBLE : View.GONE);
    }

    private boolean hasActiveFilter() {
        return !textOf(etSearchKeyword).trim().isEmpty()
                || getSectionGroup() != null
                || getSelectedParentCategory() != null
                || getSelectedSubCategory() != null
                || chipFavoritesOnly.isChecked();
    }

    private void updateEmptyState(int count, String keyword, String section, String parent,
                                  String category, boolean favoritesOnly) {
        if (count > 0) {
            layoutEmptyState.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            return;
        }
        boolean filtered = keyword != null || section != null || parent != null
                || category != null || favoritesOnly;
        tvEmptyTitle.setText(filtered ? "没有匹配的题目" : "题库还是空的");
        tvEmptySubtitle.setText(filtered
                ? "试试调整搜索关键字或筛选条件"
                : "请先在“我的”页面同步题库或导入简历");
        layoutEmptyState.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
    }

    class QuestionAdapter extends RecyclerView.Adapter<QuestionAdapter.ViewHolder> {
        private List<InterviewQuestion> questions;

        QuestionAdapter(List<InterviewQuestion> questions) {
            this.questions = questions;
        }

        void setQuestions(List<InterviewQuestion> questions) {
            this.questions = questions;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_question, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            InterviewQuestion question = questions.get(position);
            holder.tvQuestionText.setText(question.questionText);
            String displayCategory = question.parentCategory != null ? question.parentCategory : "综合";
            if (question.category != null && !question.category.equals(displayCategory)) {
                displayCategory += " · " + question.category;
            }
            holder.tvCategory.setText(displayCategory);
            applyFavoriteStyle(holder, question.favorite);

            if (question.sourceRepository != null
                    && question.sourceRepository.startsWith("manual/")) {
                holder.tvSource.setText("自建");
            } else if ("GUIDE".equals(question.sourceType)) {
                holder.tvSource.setText(question.sourceDocumentPath != null
                        && question.sourceDocumentPath.toLowerCase().contains("docs/ai/")
                        ? "AI" : "JavaGuide");
            } else if ("CUSTOM".equals(question.sourceType)
                    || "custom/resume".equals(question.sourceRepository)) {
                holder.tvSource.setText("项目专项");
            } else if ("RESUME".equals(question.sourceType)) {
                holder.tvSource.setText("个人简历");
            } else {
                holder.tvSource.setText("其他");
            }

            holder.btnFavorite.setOnClickListener(v -> {
                question.favorite = !question.favorite;
                applyFavoriteStyle(holder, question.favorite);
                boolean favorite = question.favorite;
                DbExecutor.runOnDbThenUi(QuestionBankFragment.this,
                        () -> db.interviewDao().updateFavorite(
                                question.id, favorite, System.currentTimeMillis()),
                        chipFavoritesOnly.isChecked() ? QuestionBankFragment.this::performSearch : null);
            });
            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), PracticeActivity.class);
                intent.putExtra("QUESTION_ID", question.id);
                startActivity(intent);
            });
        }

        private void applyFavoriteStyle(ViewHolder holder, boolean favorite) {
            holder.btnFavorite.setImageResource(
                    favorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
            int attr = favorite ? com.google.android.material.R.attr.colorPrimary
                    : com.google.android.material.R.attr.colorOutline;
            holder.btnFavorite.setColorFilter(MaterialColors.getColor(holder.btnFavorite, attr));
            holder.btnFavorite.setContentDescription(favorite ? "取消收藏" : "收藏题目");
        }

        @Override
        public int getItemCount() {
            return questions.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView tvCategory;
            final TextView tvSource;
            final TextView tvQuestionText;
            final android.widget.ImageButton btnFavorite;

            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvCategory = itemView.findViewById(R.id.tv_category);
                tvSource = itemView.findViewById(R.id.tv_source);
                tvQuestionText = itemView.findViewById(R.id.tv_question_text);
                btnFavorite = itemView.findViewById(R.id.btn_favorite);
            }
        }
    }
}

package com.houzhengbo.interview.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * 主题管理器。仿照 {@code SyncStatusManager} 的 SharedPreferences 模式。
 *
 * 支持四种模式：
 * - SYSTEM（默认）：跟随系统深色设置
 * - LIGHT：强制浅色（现有青蓝主题）
 * - DARK：强制深色（values-night 资源）
 * - ORANGE：暖橙主题（独立 style，强制浅色底）
 *
 * 主题选择存 SharedPreferences（而非 Room DB），因为 Activity 必须在 super.onCreate
 * 之前同步拿到主题值，DB 异步查询会闪屏。
 */
public class ThemeManager {

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT  = "light";
    public static final String MODE_DARK   = "dark";
    public static final String MODE_ORANGE = "orange";

    private static final String PREFS_NAME   = "theme_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读取当前主题模式，默认 SYSTEM。 */
    public static String getMode(Context ctx) {
        return prefs(ctx).getString(KEY_THEME_MODE, MODE_SYSTEM);
    }

    /**
     * Persist the theme before recreating an Activity. This must be synchronous:
     * the recreated Activity reads this value before super.onCreate().
     */
    public static void setMode(Context ctx, String mode) {
        prefs(ctx).edit().putString(KEY_THEME_MODE, mode).commit();
    }

    /**
     * 应用主题。在 {@link android.app.Application#onCreate()} 最早调用一次，
     * 设置全局 night mode；Activity 侧再根据 {@link #getMode} 决定 setTheme 用哪个 style。
     *
     * night mode 处理：
     * - SYSTEM → MODE_NIGHT_FOLLOW_SYSTEM
     * - LIGHT / ORANGE → MODE_NIGHT_NO（橙色主题本身是浅色底）
     * - DARK → MODE_NIGHT_YES
     *
     * ORANGE 主题的颜色切换由 Activity 的 setTheme(R.style.Theme_InterviewTraining_Orange)
     * 完成，这里只负责把 night mode 设为 NO（避免 values-night 覆盖橙色）。
     */
    public static void apply(Context ctx) {
        String mode = getMode(ctx);
        switch (mode) {
            case MODE_LIGHT:
            case MODE_ORANGE:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case MODE_SYSTEM:
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    /** 当前是否为暖橙主题（Activity 据此决定 setTheme 用哪个 style）。 */
    public static boolean isOrangeTheme(Context ctx) {
        return MODE_ORANGE.equals(getMode(ctx));
    }
}

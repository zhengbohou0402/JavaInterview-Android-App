package com.houzhengbo.interview;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 启动时先显示 splash 主题（见 AndroidManifest），这里根据用户选择切回正常主题。
        // 必须在 super.onCreate 之前调用；暖橙主题用独立 style，其余走默认 + night mode。
        if (com.houzhengbo.interview.utils.ThemeManager.isOrangeTheme(this)) {
            setTheme(R.style.Theme_InterviewTraining_Orange);
        } else {
            setTheme(R.style.Theme_InterviewTraining);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView navView = findViewById(R.id.bottom_navigation);
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            NavigationUI.setupWithNavController(navView, navController);
        }
    }
}

import os

base_dir = r"D:\求职APP\interview-app\app\src\main\java\com\houzhengbo\interview"
res_dir = r"D:\求职APP\interview-app\app\src\main\res\layout"

def create_file(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content.strip() + "\n")

# Application
create_file(os.path.join(base_dir, "InterviewApplication.java"), """
package com.houzhengbo.interview;

import android.app.Application;
import com.houzhengbo.interview.data.AppDatabase;

public class InterviewApplication extends Application {
    private static InterviewApplication instance;
    private AppDatabase database;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        database = AppDatabase.getDatabase(this);
    }

    public static InterviewApplication getInstance() {
        return instance;
    }

    public AppDatabase getDatabase() {
        return database;
    }
}
""")

# MainActivity
create_file(os.path.join(base_dir, "MainActivity.java"), """
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
""")

# Database
create_file(os.path.join(base_dir, "data", "AppDatabase.java"), """
package com.houzhengbo.interview.data;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import com.houzhengbo.interview.data.entity.*;
import com.houzhengbo.interview.data.dao.*;

@Database(entities = {GuideDocument.class, InterviewQuestion.class, ResumeProfile.class, PracticeAttempt.class, AiEvaluation.class, AppSettings.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract InterviewDao interviewDao();

    public static AppDatabase getDatabase(final Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "interview_database")
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
""")

print("Base files generated successfully.")

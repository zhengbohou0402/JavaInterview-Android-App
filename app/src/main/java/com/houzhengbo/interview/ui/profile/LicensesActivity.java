package com.houzhengbo.interview.ui.profile;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.houzhengbo.interview.R;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

public class LicensesActivity extends AppCompatActivity {

    private TextView tvAttribution;
    private Button btnViewLicense;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_licenses);

        tvAttribution = findViewById(R.id.tv_attribution);
        btnViewLicense = findViewById(R.id.btn_view_license);

        String attribution = getString(R.string.javaguide_attribution);
        tvAttribution.setText(attribution);

        btnViewLicense.setOnClickListener(v -> showFullLicense());
    }

    private void showFullLicense() {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open("apache-2.0-license.txt");
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法读取许可证文件", Toast.LENGTH_SHORT).show();
            return;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Apache License 2.0")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)
                .setNeutralButton("在浏览器中查看", (dialog, which) -> {
                    Intent intent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.apache.org/licenses/LICENSE-2.0.txt"));
                    startActivity(intent);
                })
                .show();
    }
}

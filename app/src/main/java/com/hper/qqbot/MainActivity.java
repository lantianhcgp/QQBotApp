package com.hper.qqbot;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.text.method.ScrollingMovementMethod;
import android.view.*;
import android.widget.*;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    private TextView logView;
    private Button btnStart, btnStop;
    private boolean isRunning = false;
    private StringBuilder logBuffer = new StringBuilder();

    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String log = intent.getStringExtra(BotService.EXTRA_LOG);
            if (log != null) {
                runOnUiThread(() -> {
                    logBuffer.setLength(0);
                    logBuffer.append(log);
                    logView.setText(logBuffer.toString());
                    ScrollView sv = (ScrollView) logView.getParent();
                    sv.fullScroll(View.FOCUS_DOWN);
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        logView = findViewById(R.id.logView);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        logView.setMovementMethod(new ScrollingMovementMethod());

        btnStart.setOnClickListener(v -> startBot());
        btnStop.setOnClickListener(v -> stopBot());

        btnStop.setEnabled(false);
        requestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(logReceiver, new IntentFilter("com.hper.qqbot.LOG_UPDATE"), RECEIVER_NOT_EXPORTED);
        refreshLog();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(logReceiver);
    }

    private void startBot() {
        Intent intent = new Intent(this, BotService.class);
        intent.setAction(BotService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);

        isRunning = true;
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
    }

    private void stopBot() {
        Intent intent = new Intent(this, BotService.class);
        intent.setAction(BotService.ACTION_STOP);
        startService(intent);

        isRunning = false;
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    private void refreshLog() {
        Intent intent = new Intent(this, BotService.class);
        intent.setAction(BotService.ACTION_GET_LOG);
        startService(intent);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }
}

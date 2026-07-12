package com.hper.qqbot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.util.concurrent.*;

public class BotService extends Service {
    private static final String TAG = "BotService";
    private static final String CHANNEL_ID = "bot_channel";
    private static final int NOTIFICATION_ID = 1;

    private Process lagrangeProcess;
    private Process aiBotProcess;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_LINES = 500;
    private boolean isRunning = false;

    private String appDataDir;
    private String homeDir;

    public static final String ACTION_START = "com.hper.qqbot.START";
    public static final String ACTION_STOP = "com.hper.qqbot.STOP";
    public static final String ACTION_GET_LOG = "com.hper.qqbot.GET_LOG";
    public static final String EXTRA_LOG = "log";

    @Override
    public void onCreate() {
        super.onCreate();
        appDataDir = getFilesDir().getAbsolutePath();
        homeDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/QQBotData";
        new File(homeDir).mkdirs();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopBot();
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_GET_LOG.equals(action)) {
                Intent logIntent = new Intent("com.hper.qqbot.LOG_UPDATE");
                logIntent.putExtra(EXTRA_LOG, logBuffer.toString());
                sendBroadcast(logIntent);
                return START_NOT_STICKY;
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification("Bot 正在运行..."));
        startBot();
        return START_STICKY;
    }

    private void startBot() {
        if (isRunning) return;
        isRunning = true;

        executor.execute(() -> {
            try {
                appendLog("🚀 正在启动服务...");
                startLagrange();
                Thread.sleep(6000);
                startAiBot();
                appendLog("✅ 所有服务已启动");
                updateNotification("Bot 运行中");
            } catch (Exception e) {
                appendLog("❌ 启动失败: " + e.getMessage());
                isRunning = false;
            }
        });
    }

    private void startLagrange() throws IOException {
        String lagrangeDir = homeDir + "/lagrange";
        appendLog("📦 启动 Lagrange.Milky...");

        String cmd = "cd " + lagrangeDir + " && " +
            "export DOTNET_ROOT=" + appDataDir + "/dotnet && " +
            appDataDir + "/dotnet/dotnet Lagrange.Milky.dll 2>&1";

        lagrangeProcess = ShellExecutor.exec(cmd, lagrangeDir, new ShellExecutor.OutputCallback() {
            @Override
            public void onOutput(String line) {
                appendLog("[Lagrange] " + line);
            }
            @Override
            public void onDone(int exitCode) {
                appendLog("[Lagrange] 进程退出 code=" + exitCode);
            }
        });
    }

    private void startAiBot() throws IOException {
        String botScript = homeDir + "/lagrange-ai-bot.py";
        appendLog("🤖 启动 AI Bot...");

        String cmd = "cd " + homeDir + " && " +
            "export PYTHONPATH=" + appDataDir + "/python/lib/python3.11/site-packages && " +
            appDataDir + "/python/bin/python3 -u " + botScript + " 2>&1";

        aiBotProcess = ShellExecutor.exec(cmd, homeDir, new ShellExecutor.OutputCallback() {
            @Override
            public void onOutput(String line) {
                appendLog("[Bot] " + line);
            }
            @Override
            public void onDone(int exitCode) {
                appendLog("[Bot] 进程退出 code=" + exitCode);
            }
        });
    }

    private void stopBot() {
        isRunning = false;
        appendLog("🛑 正在停止服务...");
        if (lagrangeProcess != null) { lagrangeProcess.destroyForcibly(); lagrangeProcess = null; }
        if (aiBotProcess != null) { aiBotProcess.destroyForcibly(); aiBotProcess = null; }
        appendLog("✅ 服务已停止");
    }

    private void appendLog(String line) {
        logBuffer.append(line).append("\n");
        if (logBuffer.length() > MAX_LOG_LINES * 100) {
            logBuffer.delete(0, logBuffer.length() - MAX_LOG_LINES * 50);
        }
        Intent intent = new Intent("com.hper.qqbot.LOG_UPDATE");
        intent.putExtra(EXTRA_LOG, logBuffer.toString());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bot Service", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, BotService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QQ Bot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
            .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stopBot();
        executor.shutdownNow();
        super.onDestroy();
    }
}

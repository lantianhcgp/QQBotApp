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

    private java.lang.Process lagrangeProcess;
    private java.lang.Process aiBotProcess;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_LINES = 500;
    private boolean isRunning = false;

    private String workDir;
    private String shell;
    private String dotnet;
    private String python;

    public static final String ACTION_START = "com.hper.qqbot.START";
    public static final String ACTION_STOP = "com.hper.qqbot.STOP";
    public static final String ACTION_GET_LOG = "com.hper.qqbot.GET_LOG";
    public static final String EXTRA_LOG = "log";

    @Override
    public void onCreate() {
        super.onCreate();
        workDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/QQBotData";
        new File(workDir).mkdirs();
        new File(workDir + "/lagrange").mkdirs();
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
        startForeground(NOTIFICATION_ID, buildNotification("Bot 正在启动..."));
        startBot();
        return START_STICKY;
    }

    private void startBot() {
        if (isRunning) return;
        isRunning = true;

        executor.execute(() -> {
            try {
                appendLog("🚀 正在启动服务...");
                appendLog("📂 数据目录: " + workDir);

                if (!detectEnvironment()) {
                    appendLog("❌ 未检测到运行环境");
                    appendLog("📋 请确保已安装 Termux 并运行过一次");
                    appendLog("📋 或将 dotnet/python3 放入 /QQBotData/runtime/bin/");
                    isRunning = false;
                    return;
                }

                appendLog("🔗 Shell: " + shell);
                appendLog("🔗 Dotnet: " + dotnet);
                appendLog("🔗 Python: " + python);

                startLagrange();
                Thread.sleep(6000);
                startAiBot();
                appendLog("✅ 所有服务已启动");
                updateNotification("Bot 运行中");
            } catch (Exception e) {
                appendLog("❌ 启动失败: " + e.getMessage());
                Log.e(TAG, "Start failed", e);
                isRunning = false;
            }
        });
    }

    private boolean detectEnvironment() {
        // 优先检测 Termux 环境
        String[] termuxShells = {
            "/data/data/com.termux/files/usr/bin/sh",
            "/data/data/com.termux/files/usr/bin/bash"
        };
        String[] termuxDotnet = {
            "/data/data/com.termux/files/usr/lib/dotnet/dotnet",
            "/data/data/com.termux/files/usr/bin/dotnet"
        };
        String[] termuxPython = {
            "/data/data/com.termux/files/usr/bin/python3",
            "/data/data/com.termux/files/usr/bin/python3.11",
            "/data/data/com.termux/files/usr/bin/python3.12",
            "/data/data/com.termux/files/usr/bin/python3.14"
        };

        // 检测 Termux
        for (String s : termuxShells) { if (new File(s).exists()) { shell = s; break; } }
        for (String d : termuxDotnet) { if (new File(d).exists()) { dotnet = d; break; } }
        for (String p : termuxPython) { if (new File(p).exists()) { python = p; break; } }

        if (shell != null && dotnet != null && python != null) {
            appendLog("✅ 检测到 Termux 环境");
            return true;
        }

        // 检测本地 runtime 目录
        String localRuntime = workDir + "/runtime";
        String localShell = localRuntime + "/bin/sh";
        String localDotnet = localRuntime + "/dotnet";
        String localPython = localRuntime + "/bin/python3";

        if (new File(localShell).exists() && new File(localDotnet).exists() && new File(localPython).exists()) {
            shell = localShell;
            dotnet = localDotnet;
            python = localPython;
            appendLog("✅ 检测到本地运行时");
            return true;
        }

        return false;
    }

    private void startLagrange() throws IOException {
        String lagrangeDir = workDir + "/lagrange";
        appendLog("📦 启动 Lagrange.Milky...");

        String envSetup = "";
        if (shell.startsWith("/data/data/com.termux")) {
            envSetup = "export HOME='/data/data/com.termux/files/home' && " +
                "export PATH='/data/data/com.termux/files/usr/bin:$PATH' && " +
                "export LD_LIBRARY_PATH='/data/data/com.termux/files/usr/lib:$LD_LIBRARY_PATH' && ";
        }

        String cmd = envSetup + "cd '" + lagrangeDir + "' && '" + dotnet + "' Lagrange.Milky.dll 2>&1";

        lagrangeProcess = ShellExecutor.exec(cmd, lagrangeDir, shell, new ShellExecutor.OutputCallback() {
            @Override public void onOutput(String line) { appendLog("[Lagrange] " + line); }
            @Override public void onDone(int exitCode) { appendLog("[Lagrange] 退出 code=" + exitCode); }
        });
    }

    private void startAiBot() throws IOException {
        String botScript = workDir + "/lagrange-ai-bot.py";
        appendLog("🤖 启动 AI Bot...");

        String envSetup = "";
        if (shell.startsWith("/data/data/com.termux")) {
            envSetup = "export HOME='/data/data/com.termux/files/home' && " +
                "export PATH='/data/data/com.termux/files/usr/bin:$PATH' && ";
        }

        String cmd = envSetup + "cd '" + workDir + "' && '" + python + "' -u '" + botScript + "' 2>&1";

        aiBotProcess = ShellExecutor.exec(cmd, workDir, shell, new ShellExecutor.OutputCallback() {
            @Override public void onOutput(String line) { appendLog("[Bot] " + line); }
            @Override public void onDone(int exitCode) { appendLog("[Bot] 退出 code=" + exitCode); }
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
        if (logBuffer.length() > MAX_LOG_LINES * 100) logBuffer.delete(0, logBuffer.length() - MAX_LOG_LINES * 50);
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
            .setContentTitle("QQ Bot").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPi)
            .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { stopBot(); executor.shutdownNow(); super.onDestroy(); }
}

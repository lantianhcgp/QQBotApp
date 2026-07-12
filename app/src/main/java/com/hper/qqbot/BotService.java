package com.hper.qqbot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.zip.*;

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

    private String runtimeDir;
    private String workDir;

    public static final String ACTION_START = "com.hper.qqbot.START";
    public static final String ACTION_STOP = "com.hper.qqbot.STOP";
    public static final String ACTION_GET_LOG = "com.hper.qqbot.GET_LOG";
    public static final String EXTRA_LOG = "log";

    private static final String RUNTIME_MANIFEST = "https://raw.githubusercontent.com/lantianhcgp/QQBotApp/main/runtime/manifest.json";

    @Override
    public void onCreate() {
        super.onCreate();
        runtimeDir = new File(getFilesDir(), "runtime").getAbsolutePath();
        workDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/QQBotData";
        new File(runtimeDir).mkdirs();
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

                if (!ensureRuntime()) {
                    appendLog("❌ 运行时初始化失败，请检查网络连接");
                    isRunning = false;
                    return;
                }

                String shell = runtimeDir + "/bin/sh";
                String dotnet = runtimeDir + "/dotnet";
                String python = runtimeDir + "/bin/python3";

                appendLog("🔗 Shell: " + shell);
                appendLog("🔗 Dotnet: " + dotnet);
                appendLog("🔗 Python: " + python);

                startLagrange(shell, dotnet);
                Thread.sleep(6000);
                startAiBot(shell, python);
                appendLog("✅ 所有服务已启动");
                updateNotification("Bot 运行中");
            } catch (Exception e) {
                appendLog("❌ 启动失败: " + e.getMessage());
                Log.e(TAG, "Start failed", e);
                isRunning = false;
            }
        });
    }

    private boolean ensureRuntime() {
        String marker = runtimeDir + "/.initialized";
        if (new File(marker).exists()) {
            appendLog("✅ 运行时已就绪");
            return true;
        }

        appendLog("📦 首次启动，正在初始化运行时...");
        appendLog("⚠️ 请确保设备已连接网络");

        try {
            String zipUrl = getRuntimeUrl();
            File zipFile = new File(getCacheDir(), "runtime.zip");
            appendLog("⬇️ 下载运行时...");
            downloadFile(zipUrl, zipFile);
            appendLog("✅ 下载完成 (" + (zipFile.length() / 1024 / 1024) + "MB)");

            appendLog("📦 解压运行时...");
            unzip(zipFile, runtimeDir);
            zipFile.delete();

            new File(runtimeDir + "/bin/sh").setExecutable(true);
            new File(runtimeDir + "/bin/python3").setExecutable(true);
            new File(runtimeDir + "/dotnet").setExecutable(true);

            new File(marker).createNewFile();
            appendLog("✅ 运行时初始化完成");
            return true;
        } catch (Exception e) {
            appendLog("❌ 运行时初始化失败: " + e.getMessage());
            Log.e(TAG, "Runtime init failed", e);
            return false;
        }
    }

    private String getRuntimeUrl() {
        try {
            URL url = new URL(RUNTIME_MANIFEST);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            String json = sb.toString();
            int idx = json.indexOf("\"url\"");
            if (idx > 0) {
                int start = json.indexOf("\"", idx + 5) + 1;
                int end = json.indexOf("\"", start);
                return json.substring(start, end);
            }
        } catch (Exception e) {
            appendLog("⚠️ 无法获取 manifest，使用默认地址");
        }
        return "https://github.com/nicokosi/dotnet-runtime-aarch64-android/releases/download/v8.0.0/runtime.zip";
    }

    private void downloadFile(String urlStr, File dest) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setRequestProperty("User-Agent", "QQBot/1.0");

        int totalLen = conn.getContentLength();
        InputStream is = conn.getInputStream();
        FileOutputStream fos = new FileOutputStream(dest);

        byte[] buf = new byte[8192];
        int len, downloaded = 0;
        while ((len = is.read(buf)) > 0) {
            fos.write(buf, 0, len);
            downloaded += len;
            if (totalLen > 0) {
                int pct = downloaded * 100 / totalLen;
                if (pct % 10 == 0) appendLog("⬇️ 下载进度: " + pct + "%");
            }
        }
        fos.close();
        is.close();
    }

    private void unzip(File zipFile, String targetDir) throws IOException {
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
        ZipEntry entry;
        byte[] buf = new byte[8192];
        int count = 0;
        while ((entry = zis.getNextEntry()) != null) {
            File out = new File(targetDir, entry.getName());
            if (entry.isDirectory()) {
                out.mkdirs();
            } else {
                out.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                int len;
                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
            }
            zis.closeEntry();
            count++;
            if (count % 100 == 0) appendLog("📦 已解压 " + count + " 个文件...");
        }
        zis.close();
        appendLog("📦 共解压 " + count + " 个文件");
    }

    private void startLagrange(String shell, String dotnet) throws IOException {
        String lagrangeDir = workDir + "/lagrange";
        appendLog("📦 启动 Lagrange.Milky...");
        String cmd = "export HOME='" + workDir + "' && " +
            "export LD_LIBRARY_PATH='" + runtimeDir + "/lib:$LD_LIBRARY_PATH' && " +
            "cd '" + lagrangeDir + "' && " +
            "'" + dotnet + "' Lagrange.Milky.dll 2>&1";
        lagrangeProcess = ShellExecutor.exec(cmd, lagrangeDir, shell, new ShellExecutor.OutputCallback() {
            @Override public void onOutput(String line) { appendLog("[Lagrange] " + line); }
            @Override public void onDone(int exitCode) { appendLog("[Lagrange] 退出 code=" + exitCode); }
        });
    }

    private void startAiBot(String shell, String python) throws IOException {
        String botScript = workDir + "/lagrange-ai-bot.py";
        appendLog("🤖 启动 AI Bot...");
        String cmd = "export HOME='" + workDir + "' && " +
            "cd '" + workDir + "' && " +
            "'" + python + "' -u '" + botScript + "' 2>&1";
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

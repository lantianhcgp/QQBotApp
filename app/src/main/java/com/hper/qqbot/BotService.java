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
import org.json.*;

public class BotService extends Service {
    private static final String TAG = "BotService";
    private static final String CHANNEL_ID = "bot_channel";
    private static final int NOTIFICATION_ID = 1;

    private java.lang.Process lagrangeProcess;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_LINES = 500;
    private boolean isRunning = false;
    private boolean botRunning = false;

    private String workDir;
    private String runtimeDir;
    private String shell;
    private String dotnet;

    // LLM 配置
    private String llmUrl = "https://api.stepfun.com/step_plan/v1/chat/completions";
    private String llmKey = "";
    private String llmModel = "step-3.7-flash";
    private String systemPrompt = "你是一个友好的AI助手，运行在QQ机器人上。\n回复简洁有用，不超过200字。使用中文回复。";

    // Exa Search 配置
    private static final String EXA_MCP_URL = "https://mcp.exa.ai/mcp";

    public static final String ACTION_START = "com.hper.qqbot.START";
    public static final String ACTION_STOP = "com.hper.qqbot.STOP";
    public static final String ACTION_GET_LOG = "com.hper.qqbot.GET_LOG";
    public static final String EXTRA_LOG = "log";

    // 内存中的对话历史
    private final ConcurrentHashMap<String, JSONArray> chatHistories = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        workDir = Environment.getExternalStorageDirectory().getAbsolutePath() + "/QQBotData";
        runtimeDir = new File(getFilesDir(), "runtime").getAbsolutePath();
        new File(workDir).mkdirs();
        new File(workDir + "/lagrange").mkdirs();
        createNotificationChannel();
        loadSettings();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopAll();
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
        startAll();
        return START_STICKY;
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences("bot_settings", MODE_PRIVATE);
        llmUrl = prefs.getString("api_url", llmUrl);
        llmKey = prefs.getString("api_key", llmKey);
        llmModel = prefs.getString("model", llmModel);
        systemPrompt = prefs.getString("prompt", systemPrompt);
    }

    private void startAll() {
        if (isRunning) return;
        isRunning = true;

        executor.execute(() -> {
            try {
                appendLog("🚀 正在启动服务...");
                appendLog("📂 数据目录: " + workDir);

                // 1. 确保 .NET 运行时就绪
                if (!ensureDotnet()) {
                    appendLog("❌ .NET 运行时初始化失败");
                    isRunning = false;
                    return;
                }

                // 2. 启动 Lagrange
                startLagrange();
                Thread.sleep(5000);

                // 3. 启动内置 AI Bot (Java HTTP 服务)
                startJavaBot();

                appendLog("✅ 所有服务已启动");
                updateNotification("Bot 运行中");
            } catch (Exception e) {
                appendLog("❌ 启动失败: " + e.getMessage());
                Log.e(TAG, "Start failed", e);
                isRunning = false;
            }
        });
    }

    private boolean ensureDotnet() {
        String marker = runtimeDir + "/.dotnet_ready";
        if (new File(marker).exists()) {
            appendLog("✅ .NET 运行时已就绪");
            return true;
        }

        appendLog("📦 首次启动，正在下载 .NET 运行时...");
        appendLog("⚠️ 约 22MB，请确保网络连接");

        try {
            String url = "https://dotnetcli.azureedge.net/dotnet/Runtime/8.0.19/dotnet-runtime-8.0.19-linux-bionic-arm64.tar.gz";
            File zipFile = new File(getCacheDir(), "dotnet.tar.gz");
            downloadFile(url, zipFile);
            appendLog("✅ 下载完成");

            appendLog("📦 解压中...");
            // 使用系统 tar 解压
            new File(runtimeDir).mkdirs();
            String cmd = "tar -xzf '" + zipFile.getAbsolutePath() + "' -C '" + runtimeDir + "'";
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.redirectErrorStream(true);
            java.lang.Process p = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            while (reader.readLine() != null) {}
            p.waitFor();
            zipFile.delete();

            // 设置执行权限
            new File(runtimeDir, "dotnet").setExecutable(true);

            new File(marker).createNewFile();
            appendLog("✅ .NET 运行时就绪");
            return true;
        } catch (Exception e) {
            appendLog("❌ 下载失败: " + e.getMessage());
            return false;
        }
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

    private void startLagrange() throws IOException {
        String lagrangeDir = workDir + "/lagrange";
        appendLog("📦 启动 Lagrange.Milky...");

        String dotnetBin = runtimeDir + "/dotnet";
        String cmd = "export DOTNET_ROOT='" + runtimeDir + "' && " +
            "export HOME='" + workDir + "' && " +
            "export LD_LIBRARY_PATH='" + runtimeDir + "/shared/Microsoft.NETCore.App/8.0.19:$LD_LIBRARY_PATH' && " +
            "cd '" + lagrangeDir + "' && " +
            "'" + dotnetBin + "' Lagrange.Milky.dll 2>&1";

        lagrangeProcess = ShellExecutor.exec(cmd, lagrangeDir, "/system/bin/sh", new ShellExecutor.OutputCallback() {
            @Override public void onOutput(String line) { appendLog("[Lagrange] " + line); }
            @Override public void onDone(int exitCode) { appendLog("[Lagrange] 退出 code=" + exitCode); }
        });
    }

    // ========== 内置 Java AI Bot ==========

    private void startJavaBot() {
        appendLog("🤖 启动 AI Bot (Java)...");
        botRunning = true;

        // 启动 HTTP 服务器接收 Lagrange 的 WebHook
        executor.execute(() -> {
            try {
                SimpleHttpServer server = new SimpleHttpServer(3001);
                server.start((body, callback) -> {
                    try {
                        handleWebhook(body);
                        callback.respond(200, "{\"status\":\"ok\"}");
                    } catch (Exception e) {
                        appendLog("[Bot] WebHook 错误: " + e.getMessage());
                        callback.respond(500, "{\"error\":\"" + e.getMessage() + "\"}");
                    }
                });
                appendLog("🤖 AI Bot 监听 http://0.0.0.0:3001/webhook");
            } catch (Exception e) {
                appendLog("❌ AI Bot 启动失败: " + e.getMessage());
            }
        });
    }

    private void handleWebhook(String json) {
        try {
            JSONObject event = new JSONObject(json);
            if (!event.getString("event_type").equals("message_receive")) return;

            int selfId = event.getInt("self_id");
            JSONObject data = event.getJSONObject("data");
            int senderId = data.getInt("sender_id");
            if (senderId == selfId) return;

            String scene = data.optString("message_scene", "friend");
            boolean isGroup = "group".equals(scene);

            // 群聊：只处理 @机器人 的消息
            if (isGroup) {
                boolean mentioned = false;
                JSONArray segments = data.getJSONArray("segments");
                for (int i = 0; i < segments.length(); i++) {
                    JSONObject seg = segments.getJSONObject(i);
                    if ("mention".equals(seg.getString("type")) &&
                        seg.getJSONObject("data").optInt("user_id") == selfId) {
                        mentioned = true;
                        break;
                    }
                }
                if (!mentioned) return;
            }

            // 提取文本
            String text = "";
            JSONArray segments = data.getJSONArray("segments");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONObject seg = segments.getJSONObject(i);
                if ("text".equals(seg.getString("type"))) {
                    sb.append(seg.getJSONObject("data").optString("text", ""));
                }
            }
            text = sb.toString().trim();
            if (text.isEmpty()) return;

            int groupId = isGroup ? data.getJSONObject("group").optInt("group_id", 0) : 0;

            appendLog("[收到] " + (isGroup ? "群(" + groupId + ")" : "私聊") + " " + senderId + ": " + text);

            // 处理命令
            String reply;
            if ("/clear".equals(text) || "重置对话".equals(text)) {
                chatHistories.remove(String.valueOf(senderId));
                reply = "✅ 对话已重置";
            } else if ("/help".equals(text) || "/帮助".equals(text)) {
                reply = "🤖 AI机器人\n模型: " + llmModel + "\n命令: /clear /help /prompt";
            } else if ("/prompt".equals(text)) {
                reply = "📝 Prompt:\n" + systemPrompt;
            } else {
                reply = callLLM(senderId, text);
            }

            appendLog("[AI] 回复: " + reply.substring(0, Math.min(60, reply.length())) + "...");

            // 发送回复
            sendReply(senderId, reply, isGroup, groupId);

        } catch (Exception e) {
            appendLog("[Bot] 处理消息错误: " + e.getMessage());
        }
    }

    private String callLLM(int userId, String userMessage) {
        try {
            String key = String.valueOf(userId);
            if (!chatHistories.containsKey(key)) chatHistories.put(key, new JSONArray());
            JSONArray history = chatHistories.get(key);
            history.put(new JSONObject().put("role", "user").put("content", userMessage));

            // 限制历史长度
            while (history.length() > 20) history.remove(0);

            // 构建请求
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            for (int i = 0; i < history.length(); i++) messages.put(history.getJSONObject(i));

            JSONObject payload = new JSONObject()
                .put("model", llmModel)
                .put("messages", messages)
                .put("temperature", 0.7)
                .put("max_tokens", 500);

            String response = httpPost(llmUrl, payload.toString(), llmKey);
            JSONObject result = new JSONObject(response);
            String reply = result.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "抱歉，无法生成回复");

            history.put(new JSONObject().put("role", "assistant").put("content", reply));
            return reply;
        } catch (Exception e) {
            appendLog("[LLM] 错误: " + e.getMessage());
            return "抱歉，AI服务暂时不可用";
        }
    }

    private void sendReply(int userId, String text, boolean isGroup, int groupId) {
        try {
            JSONArray message = new JSONArray();
            message.put(new JSONObject().put("type", "text").put("data", new JSONObject().put("text", text)));

            JSONObject payload = new JSONObject().put("message", message);
            if (isGroup) {
                payload.put("group_id", groupId);
                httpPost("http://127.0.0.1:3000/api/send_group_message", payload.toString(), null);
            } else {
                payload.put("user_id", userId);
                httpPost("http://127.0.0.1:3000/api/send_private_message", payload.toString(), null);
            }
            appendLog("[发送] OK");
        } catch (Exception e) {
            appendLog("[发送] 失败: " + e.getMessage());
        }
    }

    private String httpPost(String urlStr, String jsonBody, String bearerToken) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (bearerToken != null) conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.getOutputStream().write(jsonBody.getBytes());

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private void stopAll() {
        isRunning = false;
        botRunning = false;
        appendLog("🛑 正在停止服务...");
        if (lagrangeProcess != null) { lagrangeProcess.destroyForcibly(); lagrangeProcess = null; }
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
    @Override public void onDestroy() { stopAll(); executor.shutdownNow(); super.onDestroy(); }
}

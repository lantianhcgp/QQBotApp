package com.hper.qqbot;

import android.app.*;
import android.content.*;
import android.content.res.AssetManager;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class BotService extends Service {
    private static final String TAG = "BotService";
    private static final String CHANNEL_ID = "bot_channel";
    private static final int NOTIFICATION_ID = 1;

    private java.lang.Process lagrangeProcess;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final StringBuilder logBuffer = new StringBuilder();
    private static final int MAX_LOG_LINES = 500;
    private boolean isRunning = false;

    private String dataDir;
    private String runtimeDir;
    private String lagrangeDir;

    private String llmUrl = "https://api.stepfun.com/step_plan/v1/chat/completions";
    private String llmKey = "";
    private String llmModel = "step-3.7-flash";
    private String systemPrompt = "你是一个友好的AI助手，运行在QQ机器人上。\n回复简洁有用，不超过200字。使用中文回复。";

    public static final String ACTION_START = "com.hper.qqbot.START";
    public static final String ACTION_STOP = "com.hper.qqbot.STOP";
    public static final String ACTION_GET_LOG = "com.hper.qqbot.GET_LOG";
    public static final String EXTRA_LOG = "log";

    private final ConcurrentHashMap<String, JSONArray> chatHistories = new ConcurrentHashMap<>();
    private SimpleHttpServer webhookServer;

    @Override
    public void onCreate() {
        super.onCreate();
        // 使用内部存储，避免 scoped storage 权限问题
        dataDir = getFilesDir().getAbsolutePath();
        runtimeDir = dataDir + "/runtime";
        lagrangeDir = dataDir + "/lagrange";
        new File(runtimeDir).mkdirs();
        new File(lagrangeDir).mkdirs();
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
                appendLog("📂 数据目录: " + dataDir);

                // 1. 解压 .NET 运行时
                if (!ensureDotnet()) {
                    appendLog("❌ .NET 运行时初始化失败");
                    isRunning = false;
                    return;
                }

                // 2. 部署 Lagrange 文件
                if (!ensureLagrange()) {
                    appendLog("❌ Lagrange 部署失败");
                    isRunning = false;
                    return;
                }

                // 3. 写入配置文件
                writeLagrangeConfig();

                // 4. 启动 Lagrange
                startLagrange();

                // 5. 等待 Lagrange 启动
                Thread.sleep(5000);

                // 6. 启动 Java AI Bot
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
        File dotnetFile = new File(runtimeDir, "dotnet");
        if (dotnetFile.exists() && dotnetFile.length() > 0) {
            dotnetFile.setExecutable(true, true);
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
            TarUtils.extractTarGz(zipFile, new File(runtimeDir));
            zipFile.delete();

            // 验证解压结果
            File check = new File(runtimeDir, "dotnet");
            appendLog("📦 验证: dotnet 存在=" + check.exists() + " 大小=" + check.length());
            if (!check.exists() || check.length() == 0) {
                appendLog("❌ dotnet 文件不存在或为空");
                return false;
            }

            check.setExecutable(true, true);
            appendLog("✅ .NET 运行时就绪");
            return true;
        } catch (Exception e) {
            appendLog("❌ .NET 运行时初始化失败: " + e.getMessage());
            Log.e(TAG, "ensureDotnet failed", e);
            return false;
        }
    }

    private boolean ensureLagrange() {
        // 检查 Lagrange.DLL 是否已部署
        File checkFile = new File(lagrangeDir, "Lagrange.Milky.dll");
        if (checkFile.exists() && checkFile.length() > 0) {
            appendLog("✅ Lagrange 已部署");
            return true;
        }

        appendLog("📦 首次部署 Lagrange 文件...");

        try {
            AssetManager am = getAssets();
            String[] files = am.list("lagrange");
            if (files == null || files.length == 0) {
                appendLog("❌ assets/lagrange 目录为空");
                return false;
            }

            int count = 0;
            for (String fileName : files) {
                InputStream is = am.open("lagrange/" + fileName);
                File outFile = new File(lagrangeDir, fileName);
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                is.close();
                count++;
            }

            appendLog("✅ Lagrange 部署完成 (" + count + " 个文件)");
            return true;
        } catch (Exception e) {
            appendLog("❌ Lagrange 部署失败: " + e.getMessage());
            Log.e(TAG, "ensureLagrange failed", e);
            return false;
        }
    }

    private void writeLagrangeConfig() {
        try {
            // 读取已保存的 QQ 号和 sign token
            SharedPreferences prefs = getSharedPreferences("bot_settings", MODE_PRIVATE);
            String qqNumber = prefs.getString("qq_number", "3255201290");
            String signToken = prefs.getString("sign_token", "456f7527-34d0-4a24-8860-74d77021a90e");
            int httpPort = prefs.getInt("http_port", 3000);

            String config = "{\n" +
                "  \"QQ\": " + qqNumber + ",\n" +
                "  \"SignToken\": \"" + signToken + "\",\n" +
                "  \"LogLevel\": \"Information\",\n" +
                "  \"HttpServer\": {\n" +
                "    \"Host\": \"127.0.0.1\",\n" +
                "    \"Port\": " + httpPort + ",\n" +
                "    \"AccessToken\": \"\"\n" +
                "  },\n" +
                "  \"ServiceEndpoints\": {\n" +
                "    \"OpcodeService\": {\n" +
                "      \"Uri\": \"https://pb.qsign.hlb0.cn\"\n" +
                "    },\n" +
                "    \"SignServer\": {\n" +
                "      \"Uri\": \"https://pb.qsign.hlb0.cn\"\n" +
                "    }\n" +
                "  }\n" +
                "}";

            File configFile = new File(lagrangeDir, "appsettings.jsonc");
            FileOutputStream fos = new FileOutputStream(configFile);
            fos.write(config.getBytes());
            fos.close();
            appendLog("✅ 配置文件已写入");
        } catch (Exception e) {
            appendLog("⚠️ 写入配置失败: " + e.getMessage());
        }
    }

    private void startLagrange() throws IOException {
        appendLog("📦 启动 Lagrange.Milky...");

        String dotnetBin = runtimeDir + "/dotnet";
        String dllPath = lagrangeDir + "/Lagrange.Milky.dll";

        // 验证文件存在
        File dotnetFile = new File(dotnetBin);
        File dllFile = new File(dllPath);
        if (!dotnetFile.exists()) {
            throw new IOException("dotnet 不存在: " + dotnetBin);
        }
        if (!dllFile.exists()) {
            throw new IOException("Lagrange.Milky.dll 不存在: " + dllPath);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(dotnetBin);
        cmd.add(dllPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(lagrangeDir));
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("DOTNET_ROOT", runtimeDir);
        env.put("HOME", dataDir);

        // 设置 LD_LIBRARY_PATH 包含 runtime 共享库
        String libPath = runtimeDir;
        File nativeDir = new File(runtimeDir, "native");
        if (nativeDir.exists()) libPath = nativeDir.getAbsolutePath() + ":" + libPath;
        env.put("LD_LIBRARY_PATH", libPath);
        env.put("PATH", runtimeDir + ":" + System.getenv("PATH"));

        appendLog("🔗 命令: " + dotnetBin + " " + dllPath);
        appendLog("🔗 工作目录: " + lagrangeDir);

        lagrangeProcess = pb.start();

        // 读取 Lagrange 输出
        new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(lagrangeProcess.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog("[Lagrange] " + line);
                }
            } catch (IOException e) {
                appendLog("[Lagrange] 读取输出失败: " + e.getMessage());
            }
        }).start();

        // 检查进程是否立即退出
        new Thread(() -> {
            try {
                int exitCode = lagrangeProcess.waitFor();
                appendLog("[Lagrange] 进程退出，代码: " + exitCode);
                isRunning = false;
            } catch (InterruptedException e) {
                // 正常中断
            }
        }).start();
    }

    private void startJavaBot() {
        appendLog("🤖 启动 AI Bot (Java)...");
        executor.execute(() -> {
            try {
                webhookServer = new SimpleHttpServer(3001);
                webhookServer.start((body, callback) -> {
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
            while (history.length() > 20) history.remove(0);

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
        if (bearerToken != null && !bearerToken.isEmpty())
            conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
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
        int lastPct = -1;
        while ((len = is.read(buf)) > 0) {
            fos.write(buf, 0, len);
            downloaded += len;
            if (totalLen > 0) {
                int pct = (int)((long) downloaded * 100 / totalLen);
                if (pct / 10 > lastPct / 10) {
                    appendLog("⬇️ 下载进度: " + pct + "%");
                    lastPct = pct;
                }
            }
        }
        fos.close();
        is.close();
    }

    private void stopAll() {
        isRunning = false;
        appendLog("🛑 正在停止服务...");
        if (lagrangeProcess != null) {
            lagrangeProcess.destroyForcibly();
            lagrangeProcess = null;
        }
        if (webhookServer != null) {
            webhookServer.stop();
            webhookServer = null;
        }
        appendLog("✅ 服务已停止");
    }

    private void appendLog(String line) {
        logBuffer.append(line).append("\n");
        if (logBuffer.length() > MAX_LOG_LINES * 100)
            logBuffer.delete(0, logBuffer.length() - MAX_LOG_LINES * 50);
        Intent intent = new Intent("com.hper.qqbot.LOG_UPDATE");
        intent.putExtra(EXTRA_LOG, logBuffer.toString());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Bot Service",
            NotificationManager.IMPORTANCE_LOW);
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

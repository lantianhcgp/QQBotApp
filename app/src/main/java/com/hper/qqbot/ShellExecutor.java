package com.hper.qqbot;

import java.io.*;
import java.util.concurrent.*;

public class ShellExecutor {
    public interface OutputCallback {
        void onOutput(String line);
        void onDone(int exitCode);
    }

    public static Process exec(String command, String workDir, OutputCallback callback) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (callback != null) callback.onOutput(line);
                }
            } catch (IOException e) {
                if (callback != null) callback.onOutput("[ERROR] " + e.getMessage());
            }
        }).start();

        return process;
    }

    public static String execSync(String command, String workDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor(30, TimeUnit.SECONDS);
        return output.toString();
    }
}

package com.hper.qqbot;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class SimpleHttpServer {
    public interface RequestHandler {
        void handle(String body, Callback callback);
    }

    public interface Callback {
        void respond(int code, String body);
    }

    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    public SimpleHttpServer(int port) throws IOException {
        serverSocket = new ServerSocket(port);
    }

    public void start(RequestHandler handler) {
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    executor.execute(() -> handleClient(client, handler));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }).start();
    }

    private void handleClient(Socket client, RequestHandler handler) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            StringBuilder body = new StringBuilder();
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = reader.read(buf, read, contentLength - read);
                    if (n == -1) break;
                    read += n;
                }
                body.append(buf, 0, read);
            }

            handler.handle(body.toString(), (code, respBody) -> {
                try {
                    OutputStream os = client.getOutputStream();
                    String response = "HTTP/1.1 " + code + " OK\r\nContent-Type: application/json\r\nConnection: close\r\n\r\n" + respBody;
                    os.write(response.getBytes());
                    os.flush();
                    os.close();
                } catch (IOException e) {}
            });

            client.close();
        } catch (Exception e) {}
    }

    public void stop() {
        running = false;
        try { serverSocket.close(); } catch (IOException e) {}
        executor.shutdownNow();
    }
}

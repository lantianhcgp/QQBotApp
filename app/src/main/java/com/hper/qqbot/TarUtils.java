package com.hper.qqbot;

import java.io.*;
import java.util.zip.*;

public class TarUtils {

    public static void extractTarGz(File tarGzFile, File targetDir) throws IOException {
        FileInputStream fis = new FileInputStream(tarGzFile);
        GZIPInputStream gzis = new GZIPInputStream(fis);
        try {
            extractTar(gzis, targetDir);
        } finally {
            gzis.close();
        }
    }

    private static void extractTar(InputStream is, File targetDir) throws IOException {
        byte[] header = new byte[512];

        while (true) {
            // 确保读满 512 字节的 header
            int totalRead = 0;
            while (totalRead < 512) {
                int n = is.read(header, totalRead, 512 - totalRead);
                if (n == -1) break;
                totalRead += n;
            }
            if (totalRead < 512) break;

            // 检查全零块（tar 结束标记）
            boolean allZero = true;
            for (byte b : header) {
                if (b != 0) { allZero = false; break; }
            }
            if (allZero) break;

            // 解析文件名（前 100 字节）
            String rawName = new String(header, 0, 100, "ASCII").trim();
            if (rawName.isEmpty()) break;

            // 解析文件大小（124-136 字节，八进制）
            String sizeStr = new String(header, 124, 12, "ASCII").trim();
            long size = 0;
            if (!sizeStr.isEmpty()) {
                size = Long.parseLong(sizeStr, 8);
            }

            // 解析类型标志（156 字节）
            char typeFlag = (char) header[156];

            // 跳过 './' 前缀
            String name = rawName;
            if (name.startsWith("./")) {
                name = name.substring(2);
            }
            // 跳过单独的 '.' 或空名称
            if (name.isEmpty() || name.equals(".")) {
                // 跳过数据部分
                skipFully(is, size);
                long padding = (512 - (size % 512)) % 512;
                skipFully(is, padding);
                continue;
            }

            File outFile = new File(targetDir, name);

            if (typeFlag == '5' || typeFlag == '2' || name.endsWith("/")) {
                // 目录（type '5' = directory, '2' = symlink）
                outFile.mkdirs();
            } else {
                // 确保父目录存在
                File parent = outFile.getParentFile();
                if (parent != null) parent.mkdirs();

                // 读取文件内容
                FileOutputStream fos = new FileOutputStream(outFile);
                try {
                    long remaining = size;
                    byte[] buf = new byte[8192];
                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int len = is.read(buf, 0, toRead);
                        if (len == -1) break;
                        fos.write(buf, 0, len);
                        remaining -= len;
                    }
                } finally {
                    fos.close();
                }

                // 设置可执行权限（对二进制文件很重要）
                if (size > 0) {
                    outFile.setExecutable(true, true);
                }
            }

            // 跳过填充块（tar 每个块 512 字节对齐）
            long padding = (512 - (size % 512)) % 512;
            skipFully(is, padding);
        }
    }

    private static void skipFully(InputStream is, long bytes) throws IOException {
        long remaining = bytes;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int n = is.read(buf, 0, toRead);
            if (n == -1) break;
            remaining -= n;
        }
    }
}

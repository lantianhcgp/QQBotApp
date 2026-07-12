package com.hper.qqbot;

import java.io.*;
import java.util.zip.*;

public class TarUtils {

    public static void extractTarGz(File tarGzFile, File targetDir) throws IOException {
        FileInputStream fis = new FileInputStream(tarGzFile);
        GZIPInputStream gzis = new GZIPInputStream(fis);
        extractTar(gzis, targetDir);
        gzis.close();
    }

    private static void extractTar(InputStream is, File targetDir) throws IOException {
        byte[] header = new byte[512];
        while (true) {
            int read = is.read(header);
            if (read < 512) break;

            // 检查全零块（tar 结束标记）
            boolean allZero = true;
            for (byte b : header) { if (b != 0) { allZero = false; break; } }
            if (allZero) break;

            // 解析文件名（前 100 字节）
            String name = new String(header, 0, 100).trim();
            if (name.isEmpty()) break;

            // 解析文件大小（124-136 字节，八进制）
            String sizeStr = new String(header, 124, 12).trim();
            long size = 0;
            if (!sizeStr.isEmpty()) {
                size = Long.parseLong(sizeStr, 8);
            }

            // 解析类型标志（156 字节）
            char typeFlag = (char) header[156];

            // 跳过 './' 前缀
            if (name.startsWith("./")) name = name.substring(2);

            File outFile = new File(targetDir, name);

            if (typeFlag == '5' || name.endsWith("/")) {
                // 目录
                outFile.mkdirs();
            } else {
                outFile.getParentFile().mkdirs();
                // 读取文件内容
                FileOutputStream fos = new FileOutputStream(outFile);
                long remaining = size;
                byte[] buf = new byte[8192];
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int len = is.read(buf, 0, toRead);
                    if (len == -1) break;
                    fos.write(buf, 0, len);
                    remaining -= len;
                }
                fos.close();
                outFile.setExecutable(true);
            }

            // 跳过填充块（tar 每个块 512 字节对齐）
            long padding = (512 - (size % 512)) % 512;
            is.skip(padding);
        }
    }
}

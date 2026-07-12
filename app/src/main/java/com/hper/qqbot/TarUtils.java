package com.hper.qqbot;

import java.io.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.tar.*;

public class TarUtils {
    public static void extractTarGz(File tarGzFile, File targetDir) throws IOException {
        FileInputStream fis = new FileInputStream(tarGzFile);
        GZIPInputStream gzis = new GZIPInputStream(fis);
        
        TarArchiveInputStream tais = new TarArchiveInputStream(gzis);
        org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
        
        byte[] buffer = new byte[8192];
        int len;
        
        while ((entry = (org.apache.commons.compress.archivers.tar.TarArchiveEntry) tais.getNextTarEntry()) != null) {
            File outFile = new File(targetDir, entry.getName());
            
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                outFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                while ((len = tais.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                if (entry.getMode() != 0) {
                    outFile.setExecutable(true);
                }
            }
        }
        
        tais.close();
    }
}

package com.zz.reader.pro;

import android.content.Context;

import java.io.File;

/** 远程书下载到本机的缓存，设置里可以一键清掉 */
public class CacheStore {

    public static File dir(Context c) {
        File d = new File(c.getCacheDir(), "remote");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static long size(Context c) {
        return sizeOf(dir(c));
    }

    private static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) total += sizeOf(k);
        return total;
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    /** 返回清掉的字节数 */
    public static long clear(Context c) {
        long before = size(c);
        File d = dir(c);
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        return before;
    }

    public static boolean deleteTree(File f) {
        if (f == null) return false;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        return f.delete();
    }
}

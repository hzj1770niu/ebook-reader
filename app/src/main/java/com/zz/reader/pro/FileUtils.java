package com.zz.reader.pro;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 把系统文件选择器给的 content:// 地址复制到应用缓存，供解析器当普通文件读取 */
public class FileUtils {

    public static File copyToCache(Context context, Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            if (f.exists()) return f;
        }
        String name = queryName(context, uri);
        if (name == null || name.trim().isEmpty()) name = "book_" + System.currentTimeMillis();
        name = name.replace('/', '_').replace('\\', '_');
        File dir = new File(context.getCacheDir(), "books");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建缓存目录");
        String uriTag = Integer.toHexString(uri.toString().hashCode());
        File out = new File(dir, uriTag + "_" + name);
        long existing = out.length();
        // 已经复制过且大小一致就复用
        ContentResolver cr = context.getContentResolver();
        long size = -1;
        try (Cursor c = cr.query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) size = c.getLong(idx);
            }
        } catch (Exception ignored) {
        }
        if (existing > 0 && (size <= 0 || existing == size)) {
            return out;
        }
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("无法打开这个文件，可能没有读取权限");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    fos.write(buf, 0, r);
                }
            }
        }
        return out;
    }

    public static String queryName(Context context, Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null) return n;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " 字节";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}

package com.zz.reader.pro.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

/**
 * 图片仓库：把电子书里的封面、插图提取到应用缓存，并提供带采样解码的读取，
 * 避免大图把内存撑爆。
 */
public class ImageStore {

    private static LruCache<String, Bitmap> cache;

    private static LruCache<String, Bitmap> cache() {
        if (cache == null) {
            cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value == null ? 0 : value.getByteCount();
                }
            };
        }
        return cache;
    }

    private final File baseDir;

    public ImageStore(Context context) {
        this(new File(context.getApplicationContext().getCacheDir(), "media"));
    }

    public ImageStore(File baseDir) {
        this.baseDir = baseDir;
        if (!baseDir.exists()) baseDir.mkdirs();
    }

    public File dirFor(String bookKey) {
        String key = md5(bookKey == null ? "default" : bookKey);
        File dir = new File(baseDir, key);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * 把图片字节保存成文件。识别不出图片类型的直接返回 null（宁可不显示，也不显示碎图）。
     */
    public String save(byte[] data, File dir, String nameHint) {
        if (data == null || data.length < 64) return null;
        String ext = sniffExt(data);
        if (ext == null) return null;
        File out = new File(dir, md5(nameHint == null ? String.valueOf(data.length) : nameHint) + "." + ext);
        if (out.exists() && out.length() == data.length) {
            return out.getAbsolutePath();
        }
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(data);
        } catch (IOException e) {
            return null;
        }
        return out.getAbsolutePath();
    }

    public String saveBase64(String base64, File dir, String nameHint) {
        try {
            byte[] data = decodeBase64(base64);
            return save(data, dir, nameHint);
        } catch (Exception e) {
            return null;
        }
    }

    /** 标准 Base64 解码，忽略空白与换行 */
    public static byte[] decodeBase64(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            if (Character.isWhitespace(c)) continue;
            if (c == '+' || c == '/' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        int len = sb.length();
        if (len < 2) return null;
        byte[] out = new byte[len * 3 / 4];
        int pos = 0;
        int buf = 0;
        int bits = 0;
        for (int i = 0; i < len; i++) {
            char c = sb.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+') v = 62;
            else v = 63;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[pos++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        if (pos == out.length) return out;
        byte[] trimmed = new byte[pos];
        System.arraycopy(out, 0, trimmed, 0, pos);
        return trimmed;
    }

    public static String sniffExt(byte[] d) {
        if (d.length < 4) return null;
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) return "jpg";
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F') return "gif";
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F') return "webp";
        if (d[0] == 'B' && d[1] == 'M') return "bmp";
        return null;
    }

    /** 采样解码，宽度不超过 reqWidth，高度不超过 maxHeight */
    public Bitmap load(String path, int reqWidth, int maxHeight) {
        if (path == null || path.isEmpty()) return null;
        String key = path + "@" + reqWidth + "x" + maxHeight;
        Bitmap cached = cache().get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        int sample = 1;
        while ((bounds.outWidth / sample) > reqWidth * 2) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = Math.max(1, sample);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeFile(path, opts);
        } catch (OutOfMemoryError e) {
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = Math.max(2, sample * 2);
            try {
                bmp = BitmapFactory.decodeFile(path, opts);
            } catch (OutOfMemoryError ignored) {
                bmp = null;
            }
        }
        if (bmp != null) cache().put(key, bmp);
        return bmp;
    }

    /**
     * TXT 这类没有封面的书，按书名生成一张封面图，书库列表里不会一片空白。
     */
    public String generateCover(String title, String author, File dir) {
        try {
            return generateCoverInternal(title, author, dir);
        } catch (Throwable t) {
            return null;
        }
    }

    private String generateCoverInternal(String title, String author, File dir) {
        String name = "cover_" + md5((title == null ? "" : title) + (author == null ? "" : author)) + ".png";
        File out = new File(dir, name);
        if (out.exists() && out.length() > 0) return out.getAbsolutePath();

        int w = 480;
        int h = 640;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        int hue = Math.abs((title == null ? "book" : title).hashCode()) % 360;
        int top = Color.HSVToColor(new float[]{hue, 0.45f, 0.75f});
        int bottom = Color.HSVToColor(new float[]{hue, 0.62f, 0.42f});
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, w, h, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, bg);

        Paint line = new Paint();
        line.setColor(Color.argb(70, 255, 255, 255));
        line.setStrokeWidth(4);
        canvas.drawRect(28, 28, w - 28, h - 28, line);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(56);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.LEFT);
        String t = TextUtils.isEmpty(title) ? "未命名" : title;
        drawWrapped(canvas, t, p, 60, 120, w - 120, 68, 4);

        Paint a = new Paint(Paint.ANTI_ALIAS_FLAG);
        a.setColor(Color.argb(220, 255, 255, 255));
        a.setTextSize(30);
        if (!TextUtils.isEmpty(author)) {
            canvas.drawText("作者 " + author, 60, h - 80, a);
        } else {
            canvas.drawText("纯文本", 60, h - 80, a);
        }

        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
        } catch (IOException e) {
            bmp.recycle();
            return null;
        }
        bmp.recycle();
        return out.getAbsolutePath();
    }

    private void drawWrapped(Canvas canvas, String text, Paint p, int x, int y,
                             int maxWidth, int lineHeight, int maxLines) {
        int lines = 0;
        int start = 0;
        int len = text.length();
        while (start < len && lines < maxLines) {
            int count = p.breakText(text, start, len, true, maxWidth, null);
            if (count <= 0) break;
            String s = text.substring(start, start + count);
            canvas.drawText(s, x, y + lines * lineHeight, p);
            start += count;
            lines++;
        }
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(Math.abs(s.hashCode()));
        }
    }

    public void clearMemory() {
        if (cache != null) cache.evictAll();
    }
}

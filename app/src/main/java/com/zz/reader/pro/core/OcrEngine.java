package com.zz.reader.pro.core;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * 本地中文文字识别：PP-OCRv5 检测 + 方向分类 + 识别，ONNX Runtime 本地推理。
 * 做了三件事：整页方向校正、倾斜行摆正后再识别、按栏还原阅读顺序。
 */
public class OcrEngine {

    private static OcrEngine inst;

    /** 最近一次识别失败的原因，界面上要能看到，不能悄悄失败 */
    public static volatile String lastError;

    /** 退出软件时把本地识别占的内存放掉，进程回收才快 */
    public static synchronized void shutdown() {
        OcrEngine e = inst;
        inst = null;
        if (e == null) return;
        try { e.det.close(); } catch (Throwable ignore) {}
        try { e.cls.close(); } catch (Throwable ignore) {}
        try { e.rec.close(); } catch (Throwable ignore) {}
        try { e.closeRenderer(); } catch (Throwable ignore) {}
        try { e.env.close(); } catch (Throwable ignore) {}
    }

    public static synchronized OcrEngine get() throws Exception {
        if (inst == null) inst = new OcrEngine();
        return inst;
    }

    private final OrtEnvironment env;
    private final OrtSession det;
    private final OrtSession cls;
    private final OrtSession rec;
    private final String detInput, clsInput, recInput;
    private final String[] keys;

    private String openPath;
    private ParcelFileDescriptor openFd;
    private PdfRenderer openRenderer;

    private OcrEngine() throws Exception {
        Context c = AppCtx.get();
        if (c == null) throw new Exception("没有应用上下文");
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(2);
        det = env.createSession(readAsset(c, "ocr/det5.onnx"), opts);
        cls = env.createSession(readAsset(c, "ocr/cls.onnx"), opts);
        rec = env.createSession(readAsset(c, "ocr/rec5.onnx"), opts);
        detInput = det.getInputNames().iterator().next();
        clsInput = cls.getInputNames().iterator().next();
        recInput = rec.getInputNames().iterator().next();
        keys = readKeys(c);
    }

    public boolean isReady() {
        return det != null && rec != null && keys != null && keys.length > 0;
    }

    // ---------------- 对外 ----------------

    public String recognizePage(File pdf, int pageIndex) throws Exception {
        Bitmap bmp = renderPage(pdf, pageIndex, 1000);
        if (bmp == null) return "";
        try {
            return recognize(bmp);
        } finally {
            bmp.recycle();
        }
    }

    public Bitmap renderPage(File pdf, int pageIndex, int targetWidth) throws Exception {
        PdfRenderer r = rendererFor(pdf);
        if (r == null || pageIndex < 0 || pageIndex >= r.getPageCount()) return null;
        PdfRenderer.Page page = r.openPage(pageIndex);
        try {
            int w = targetWidth;
            int h = Math.max(1, (int) (w * (page.getHeight() / (float) page.getWidth())));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bmp;
        } finally {
            page.close();
        }
    }

    private PdfRenderer rendererFor(File pdf) throws Exception {
        String p = pdf.getAbsolutePath();
        if (openPath != null && openPath.equals(p) && openRenderer != null) return openRenderer;
        closeRenderer();
        openFd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        openRenderer = new PdfRenderer(openFd);
        openPath = p;
        return openRenderer;
    }

    private void closeRenderer() {
        if (openRenderer != null) try { openRenderer.close(); } catch (Exception ignore) {}
        if (openFd != null) try { openFd.close(); } catch (IOException ignore) {}
        openRenderer = null;
        openFd = null;
        openPath = null;
    }

    private static class Box {
        float cx, cy, w, h, angle;
    }

    // ---------------- 主流程 ----------------

    public String recognize(Bitmap src) throws Exception {
        // 1) 整页方向：上下颠倒的页先转正
        boolean rotated = false;
        try {
            if (classifyDirection(src) == 180) {
                Matrix m = new Matrix();
                m.postRotate(180);
                Bitmap turned = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
                src = turned;
                rotated = true;
            }
        } catch (Throwable ignore) {
        }

        List<Box> boxes;
        try {
            boxes = detect(src);
        } catch (Exception e) {
            if (rotated && !src.isRecycled()) src.recycle();
            throw e;
        }
        if (boxes == null || boxes.isEmpty()) {
            if (rotated && !src.isRecycled()) src.recycle();
            return "";
        }

        final int pageW = src.getWidth();

        // 2) 分栏：按中心横坐标聚类，找出栏与栏之间的大空隙
        List<Float> xs = new ArrayList<>();
        for (Box b : boxes) xs.add(b.cx);
        Collections.sort(xs);
        List<Float> breaks = new ArrayList<>();
        for (int i = 1; i < xs.size(); i++) {
            float gap = xs.get(i) - xs.get(i - 1);
            if (gap > pageW * 0.10f) breaks.add((xs.get(i) + xs.get(i - 1)) / 2f);
        }
        if (breaks.size() > 2) breaks.clear();

        final List<Float> cols = breaks;
        Collections.sort(boxes, new Comparator<Box>() {
            @Override
            public int compare(Box a, Box b) {
                int ca = colIndex(a.cx, cols), cb = colIndex(b.cx, cols);
                if (ca != cb) return ca < cb ? -1 : 1;
                return a.cy < b.cy ? -1 : (a.cy == b.cy ? 0 : 1);
            }
        });

        // 3) 逐块摆正后识别，栏内按行拼接
        StringBuilder out = new StringBuilder();
        int lastCol = -1;
        float lastY = -1e9f;
        float lastH = 20;
        for (Box b : boxes) {
            String t = recognizeBox(src, b);
            if (t == null || t.trim().isEmpty()) continue;
            int col = colIndex(b.cx, cols);
            boolean newLine = lastCol != col || b.cy - lastY > lastH * 0.9f;
            if (newLine) {
                if (out.length() > 0) out.append('\n');
            }
            out.append(t.trim());
            lastCol = col;
            lastY = b.cy;
            lastH = Math.max(12, b.h);
        }
        if (rotated && !src.isRecycled()) src.recycle();
        return out.toString();
    }

    private static int colIndex(float cx, List<Float> breaks) {
        int idx = 0;
        for (Float b : breaks) if (cx > b) idx++;
        return idx;
    }

    // ---------------- 检测 ----------------

    private List<Box> detect(Bitmap src) throws Exception {
        // 整页放大到 1280 再找字：小字、密排的正文比 1024 认得更全
        int maxSide = 1280;
        float scale = Math.min(1f, maxSide / (float) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(32, (((int) (src.getWidth() * scale)) / 32) * 32);
        int h = Math.max(32, (((int) (src.getHeight() * scale)) / 32) * 32);

        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
        float[] data = toTensorInput(scaled, w, h, new float[]{0.485f, 0.456f, 0.406f},
                new float[]{0.229f, 0.224f, 0.225f});
        float[][] map;
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), new long[]{1, 3, h, w});
             OrtSession.Result r = det.run(Collections.singletonMap(detInput, in))) {
            float[][][][] o = (float[][][][]) r.get(0).getValue();
            map = o[0][0];
        } finally {
            scaled.recycle();
        }

        int W = w, H = h;
        boolean[] hit = new boolean[W * H];
        for (int y = 0; y < H; y++) {
            float[] row = map[y];
            for (int x = 0; x < W; x++) hit[y * W + x] = row[x] > 0.3f;
        }

        float sx = src.getWidth() / (float) W;
        float sy = src.getHeight() / (float) H;
        List<Box> out = new ArrayList<>();
        boolean[] seen = new boolean[W * H];
        int[] stack = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p0 = y * W + x;
                if (!hit[p0] || seen[p0]) continue;
                int sp = 0;
                stack[sp++] = p0;
                seen[p0] = true;
                int count = 0;
                double sumX = 0, sumY = 0, sumXX = 0, sumYY = 0, sumXY = 0;
                int minX = x, maxX = x, minY = y, maxY = y;
                while (sp > 0) {
                    int p = stack[--sp];
                    int px = p % W, py = p / W;
                    count++;
                    sumX += px; sumY += py;
                    sumXX += (double) px * px; sumYY += (double) py * py; sumXY += (double) px * py;
                    if (px < minX) minX = px;
                    if (px > maxX) maxX = px;
                    if (py < minY) minY = py;
                    if (py > maxY) maxY = py;
                    for (int k = 0; k < 4; k++) {
                        int nx = px + (k == 0 ? 1 : k == 1 ? -1 : 0);
                        int ny = py + (k == 2 ? 1 : k == 3 ? -1 : 0);
                        if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                        int ni = ny * W + nx;
                        if (hit[ni] && !seen[ni]) {
                            seen[ni] = true;
                            if (sp < stack.length) stack[sp++] = ni;
                        }
                    }
                }
                if (count < 14) continue;
                double mcx = sumX / count, mcy = sumY / count;
                double mu20 = sumXX / count - mcx * mcx;
                double mu02 = sumYY / count - mcy * mcy;
                double mu11 = sumXY / count - mcx * mcy;
                double theta = 0.5 * Math.atan2(2 * mu11, mu20 - mu02);
                if (theta > Math.PI / 4) theta -= Math.PI / 2;
                if (theta < -Math.PI / 4) theta += Math.PI / 2;

                Box b = new Box();
                b.cx = (minX + maxX) / 2f * sx;
                b.cy = (minY + maxY) / 2f * sy;
                b.w = (maxX - minX + 1) * sx;
                b.h = (maxY - minY + 1) * sy;
                b.angle = (float) theta;
                if (b.w < 8 || b.h < 6) continue;
                if (b.h > src.getHeight() * 0.25f) b.h = src.getHeight() * 0.12f;
                out.add(b);
            }
        }
        return out;
    }

    // ---------------- 识别 ----------------

    private String recognizeBox(Bitmap src, Box b) throws Exception {
        int cw = (int) b.w + 8;
        int ch = (int) b.h + 8;
        if (cw < 6 || ch < 6) return "";
        Bitmap crop;
        if (Math.abs(b.angle) > 0.05f) {
            crop = rotatedCrop(src, b, cw, ch);
        } else {
            int left = clamp((int) (b.cx - cw / 2f), 0, Math.max(0, src.getWidth() - 1));
            int top = clamp((int) (b.cy - ch / 2f), 0, Math.max(0, src.getHeight() - 1));
            int w2 = Math.min(cw, src.getWidth() - left);
            int h2 = Math.min(ch, src.getHeight() - top);
            crop = Bitmap.createBitmap(src, left, top, Math.max(1, w2), Math.max(1, h2));
        }
        int rh = 48;
        int rw = Math.max(16, Math.min(1600, (int) (rh * crop.getWidth() / (float) Math.max(1, crop.getHeight()))));
        Bitmap resized = Bitmap.createScaledBitmap(crop, rw, rh, true);
        crop.recycle();

        float[] data = toTensorInput(resized, rw, rh, new float[]{0.5f, 0.5f, 0.5f},
                new float[]{0.5f, 0.5f, 0.5f});
        float[][][] out;
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), new long[]{1, 3, rh, rw});
             OrtSession.Result r = rec.run(Collections.singletonMap(recInput, in))) {
            out = (float[][][]) r.get(0).getValue();
        } finally {
            resized.recycle();
        }
        return ctc(out[0]);
    }

    private Bitmap rotatedCrop(Bitmap src, Box b, int cw, int ch) {
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        Matrix m = new Matrix();
        m.setTranslate(-(b.cx - cw / 2f), -(b.cy - ch / 2f));
        m.postRotate((float) (-b.angle * 180.0 / Math.PI), cw / 2f, ch / 2f);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    private int classifyDirection(Bitmap src) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(src, 192, 48, true);
            float[] data = toTensorInput(small, 192, 48, new float[]{0.5f, 0.5f, 0.5f},
                    new float[]{0.5f, 0.5f, 0.5f});
            small.recycle();
            float[][] out;
            try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), new long[]{1, 3, 48, 192});
                 OrtSession.Result r = cls.run(Collections.singletonMap(clsInput, in))) {
                float[][][] o = (float[][][]) r.get(0).getValue();
                out = o[0];
            }
            if (out != null && out.length > 0 && out[0].length >= 2) {
                return out[0][1] > out[0][0] ? 180 : 0;
            }
        } catch (Throwable ignore) {
        }
        return 0;
    }

    private String ctc(float[][] probs) {
        StringBuilder sb = new StringBuilder();
        int last = -1;
        for (float[] step : probs) {
            int best = 0;
            for (int i = 1; i < step.length; i++) if (step[i] > step[best]) best = i;
            if (best > 0 && best != last) {
                int k = best - 1;
                if (k >= 0 && k < keys.length) {
                    String ch = keys[k];
                    if (ch != null) sb.append(ch);
                }
            }
            last = best;
        }
        return sb.toString();
    }

    private float[] toTensorInput(Bitmap bmp, int w, int h, float[] mean, float[] std) {
        float[] out = new float[3 * w * h];
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int plane = w * h;
        for (int i = 0; i < plane; i++) {
            int c = px[i];
            out[i] = ((Color.red(c) / 255f) - mean[0]) / std[0];
            out[plane + i] = ((Color.green(c) / 255f) - mean[1]) / std[1];
            out[2 * plane + i] = ((Color.blue(c) / 255f) - mean[2]) / std[2];
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static byte[] readAsset(Context c, String path) throws IOException {
        InputStream in = c.getAssets().open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static String[] readKeys(Context c) throws IOException {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getAssets().open("ocr/keys5.txt"), "UTF-8"));
        List<String> list = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) list.add(line);
        r.close();
        list.add(" ");
        return list.toArray(new String[0]);
    }
}

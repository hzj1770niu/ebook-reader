package com.zz.reader.pro.core;

/**
 * 把被检测器切成几块的一行合回一行。
 *
 * PP-OCR 的检测输出的是一整行的概率图，但二值化之后，中文行里字与字的空隙
 * 经常低于阈值，一行就断成两三块。每块单独送去识别，裁出来的图只有半行，
 * 字会被切掉半边或者压扁，错字漏字就是这么来的。
 *
 * 判断很简单：两块竖直方向明显重叠、横向又挨得很近，那就是同一行。
 */
public final class BoxMerge {

    private BoxMerge() {
    }

    /**
     * @return 每个框所属组的代表下标（组内最小的那个），没合并的就是自己
     */
    public static int[] merge(float[] cx, float[] cy, float[] w, float[] h) {
        int n = cx.length;
        int[] leader = new int[n];
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) leader[i] = i;
        if (n < 2) return leader;

        float[] x0 = new float[n];
        float[] x1 = new float[n];
        float[] y0 = new float[n];
        float[] y1 = new float[n];
        for (int i = 0; i < n; i++) {
            x0[i] = cx[i] - w[i] / 2f;
            x1[i] = cx[i] + w[i] / 2f;
            y0[i] = cy[i] - h[i] / 2f;
            y1[i] = cy[i] + h[i] / 2f;
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                for (int j = i + 1; j < n; j++) {
                    if (used[j]) continue;
                    float oy = Math.min(y1[i], y1[j]) - Math.max(y0[i], y0[j]);
                    float minH = Math.min(y1[i] - y0[i], y1[j] - y0[j]);
                    if (minH <= 0) continue;
                    if (oy / minH < 0.55f) continue;
                    float gap = Math.max(x0[j] - x1[i], x0[i] - x1[j]);
                    float ref = Math.max(y1[i] - y0[i], y1[j] - y0[j]);
                    if (gap > ref * 1.15f) continue;
                    // 并到 i 上，j 作废
                    x0[i] = Math.min(x0[i], x0[j]);
                    x1[i] = Math.max(x1[i], x1[j]);
                    y0[i] = Math.min(y0[i], y0[j]);
                    y1[i] = Math.max(y1[i], y1[j]);
                    used[j] = true;
                    leader[j] = i;
                    changed = true;
                }
            }
        }
        return leader;
    }
}

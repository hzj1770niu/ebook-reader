package com.zz.reader.pro.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 分栏：不再看"相邻两行的起点差多少"，那是错的——同栏里段落缩进、居中标题、
 * 行尾不齐，行首本来就参差不齐，差一点点就误当成了栏与栏的分界。
 *
 * 改看整页的横向投影：把页面切成一条条竖格子，看看哪些竖格子从头到尾没有被任何一行
 * 覆盖过。栏与栏之间那片空白会空出来，栏内部不会。这才是真正的栏边界。
 *
 * 只认页面中间的空段，左右页边距不算。最多认四栏，通栏标题这类特例容忍。
 */
public final class ColumnSplit {

    private ColumnSplit() {
    }

    /** 每行/每块的左右边界，用屏幕或页面像素都行，pageW 是同一坐标系下的页宽 */
    public static int[] group(float[] left, float[] right, float pageW, int maxCols) {
        int n = left.length;
        int[] col = new int[n];
        if (n == 0) return col;
        if (pageW <= 0) pageW = 1;
        if (maxCols < 1) maxCols = 1;

        int N = 120;
        int[] cover = new int[N];
        int firstFilled = N;
        int lastFilled = -1;
        for (int i = 0; i < n; i++) {
            // 通栏标题、页眉页脚这类横贯整页的行不参与投影，
            // 否则它会把栏间空白填满，整页就分不出栏了
            if (right[i] - left[i] > pageW * 0.6f) continue;
            int a = (int) Math.floor(left[i] / pageW * N);
            int b = (int) Math.ceil(right[i] / pageW * N);
            if (a < 0) a = 0;
            if (b > N) b = N;
            if (b <= a) b = Math.min(N, a + 1);
            for (int k = a; k < b; k++) {
                cover[k]++;
                if (k < firstFilled) firstFilled = k;
                if (k > lastFilled) lastFilled = k;
            }
        }
        if (lastFilled < 0 || maxCols == 1) return col;

        // 找出"夹在内容中间"的空白竖条
        List<int[]> gaps = new ArrayList<>();
        int i = firstFilled;
        while (i <= lastFilled) {
            if (cover[i] > 0) {
                i++;
                continue;
            }
            int s = i;
            while (i <= lastFilled && cover[i] == 0) i++;
            int e = i;
            // 太窄的不算（那只是字距），至少要有页宽百分之一多一点
            if (e - s >= Math.max(2, N / 90)) gaps.add(new int[]{s, e});
        }
        if (gaps.isEmpty()) return col;

        // 只要最宽的几段，数量受栏数限制
        List<int[]> picked = new ArrayList<>(gaps);
        while (picked.size() > maxCols - 1) {
            int widest = 0;
            for (int k = 1; k < picked.size(); k++) {
                if ((picked.get(k)[1] - picked.get(k)[0]) > (picked.get(widest)[1] - picked.get(widest)[0])) {
                    widest = k;
                }
            }
            picked.remove(widest);
        }

        // 中点换算回原坐标，作为栏的分界
        float[] breaks = new float[picked.size()];
        for (int k = 0; k < picked.size(); k++) {
            int[] g = picked.get(k);
            breaks[k] = (g[0] + g[1]) / 2f / N * pageW;
        }
        java.util.Arrays.sort(breaks);

        for (int k = 0; k < n; k++) {
            int c = 0;
            float mid = (left[k] + right[k]) / 2f;
            for (float b : breaks) if (mid > b) c++;
            col[k] = c;
        }
        return col;
    }
}

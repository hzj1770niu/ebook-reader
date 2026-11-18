package com.zz.reader.pro.core;

import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.LeadingMarginSpan;

import java.util.ArrayList;
import java.util.List;

/**
 * 排版引擎：把整本书按当前字号、行距、段距、边距、页眉页脚，精确切成一页一页。
 * 用 StaticLayout 真实测量每一段的高度，所以不会出现文字被切掉半行、
 * 或者一页刚好差一行的情况。
 */
public class LayoutEngine {

    public static class Page {
        public int startPara;
        public int endPara;
        public int chapterIndex;
        public String chapterTitle = "";
        public int firstChar;      // 到这一页开头累计的字符数，用于百分比
    }

    private final List<Book.Block> paras;
    private final PageStyle style;
    private final int width;
    private final int height;
    private final TextPaint bodyPaint;
    private final TextPaint titlePaint;
    private final int indentPx;
    private final int paraSpacingPx;
    private final int imageMaxHeight;

    private final List<Page> pages = new ArrayList<>();
    private final List<Integer> heights = new ArrayList<>();
    private int totalChars = 1;

    public LayoutEngine(Book book, PageStyle style, int widthPx, int heightPx,
                        int textSizePx, float density, Typeface bodyTypeface) {
        this.paras = new ArrayList<>(book.blocks());
        this.style = style;
        this.width = Math.max(80, widthPx);
        this.height = Math.max(80, heightPx);

        bodyPaint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        bodyPaint.setTextSize(textSizePx);
        bodyPaint.setTypeface(bodyTypeface);
        bodyPaint.setFakeBoldText(style.bold);

        titlePaint = new TextPaint(bodyPaint);
        titlePaint.setTextSize(textSizePx * 1.12f);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTypeface(bodyTypeface);
        titlePaint.setTextAlign(android.graphics.Paint.Align.LEFT);

        indentPx = Math.round(style.indentChars * textSizePx);
        paraSpacingPx = Math.round(style.paraSpacingDp * density);
        imageMaxHeight = Math.round(this.height * 0.62f);

        int chars = 0;
        for (Book.Block b : paras) {
            if (!b.isImage() && b.text != null) chars += b.text.length();
        }
        totalChars = Math.max(1, chars);
    }

    public int paraCount() {
        return paras.size();
    }

    public Book.Block para(int i) {
        return paras.get(i);
    }

    public List<Page> pages() {
        return pages;
    }

    private boolean splitDone = false;

    /**
     * 一段排出来高过一页的时候，分页只能让它独占一页，超出的字在分页模式里看不到，就是漏字。
     * 所以先在这里按行把超高的段切成几段，每段都装得下一页，后面的分页就自然接得上。
     */
    private void splitOverflow() {
        if (splitDone) return;
        splitDone = true;
        List<Book.Block> out = new ArrayList<>();
        for (Book.Block b : paras) {
            if (b.isImage() || b.text == null || b.text.trim().isEmpty()) {
                out.add(b);
                continue;
            }
            boolean title = isTitle(b.text);
            TextPaint paint = title ? titlePaint : bodyPaint;
            CharSequence cs = buildPara(b.text, title, indentPx);
            StaticLayout sl = new StaticLayout(cs, paint, width,
                    title ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL,
                    style.lineMult, 0f, true);
            int extra = paraSpacingPx + (title ? paraSpacingPx / 2 : 0);
            if (sl.getHeight() + extra <= height) {
                out.add(b);
                continue;
            }
            int lineCount = sl.getLineCount();
            int lineH = Math.max(1, sl.getLineBottom(0) - sl.getLineTop(0));
            int maxLines = Math.max(1, (height - extra) / lineH);
            int i = 0;
            while (i < lineCount) {
                int end = Math.min(lineCount, i + maxLines);
                int s0 = sl.getLineStart(i);
                int e0 = end >= lineCount ? b.text.length() : sl.getLineStart(end);
                String sub = b.text.substring(s0, e0).trim();
                if (!sub.isEmpty()) out.add(new Book.Block(b.chapterIndex, b.chapterTitle, sub));
                i = end;
            }
        }
        paras.clear();
        paras.addAll(out);
    }

    public void compute() {
        pages.clear();
        splitOverflow();
        int n = paras.size();
        if (n == 0) {
            Page p = new Page();
            p.startPara = 0;
            p.endPara = 0;
            p.chapterIndex = 0;
            p.chapterTitle = "";
            pages.add(p);
            return;
        }

        int used = 0;
        int start = 0;
        int charsBefore = 0;
        int charsInPage = 0;

        heights.clear();
        for (int i = 0; i < n; i++) heights.add(measure(i));

        int prevChapter = -1;
        for (int i = 0; i < n; i++) {
            int h = heights.get(i);
            boolean tooTall = h > height;
            Book.Block cb = paras.get(i);
            // 新的一章从新的一页开始，这是纸书和 Kindle 的通行规则
            if (used > 0 && cb.chapterIndex != prevChapter) {
                pages.add(makePage(start, i - 1, charsBefore));
                charsBefore += charsInPage;
                start = i;
                used = 0;
                charsInPage = 0;
            }
            prevChapter = cb.chapterIndex;
            if (used > 0 && used + h > height) {
                pages.add(makePage(start, i - 1, charsBefore));
                charsBefore += charsInPage;
                start = i;
                used = 0;
                charsInPage = 0;
            }
            used += h;
            charsInPage += charsOf(i);
            if (tooTall) {
                pages.add(makePage(i, i, charsBefore));
                charsBefore += charsInPage;
                start = i + 1;
                used = 0;
                charsInPage = 0;
            }
        }
        if (start < n) {
            pages.add(makePage(start, n - 1, charsBefore));
        }
        if (pages.isEmpty()) {
            pages.add(makePage(0, n - 1, 0));
        }
    }

    private Page makePage(int s, int e, int charsBefore) {
        Page p = new Page();
        p.startPara = s;
        p.endPara = e;
        Book.Block b = paras.get(Math.min(s, paras.size() - 1));
        p.chapterIndex = b.chapterIndex;
        p.chapterTitle = b.chapterTitle == null ? "" : b.chapterTitle;
        p.firstChar = charsBefore;
        return p;
    }

    private int charsOf(int i) {
        Book.Block b = paras.get(i);
        if (b.isImage() || b.text == null) return 0;
        return b.text.length();
    }

    /** 一段内容排出来要多高 */
    /** 渲染侧用它拿到和排版时完全一致的高度 */
    public int heightOf(int i) {
        if (i < 0 || i >= heights.size()) return 0;
        return heights.get(i);
    }

    private int measure(int i) {
        Book.Block b = paras.get(i);
        if (b.isImage()) {
            int imgH = imageHeight(b.imagePath);
            return imgH + paraSpacingPx;
        }
        if (b.text == null || b.text.trim().isEmpty()) return 0;
        boolean isTitle = isTitle(b.text);
        CharSequence text = buildPara(b.text, isTitle, indentPx);
        TextPaint paint = isTitle ? titlePaint : bodyPaint;
        StaticLayout sl = new StaticLayout(text, paint, width,
                isTitle ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL,
                style.lineMult, 0f, true);
        int extra = paraSpacingPx + (isTitle ? paraSpacingPx / 2 : 0);
        return sl.getHeight() + extra;
    }

    /** 渲染侧用同一个方法生成文本，保证测量和显示一致 */
    public static CharSequence buildPara(String text, boolean isTitle, int indentPx) {
        if (isTitle || indentPx <= 0) return text;
        SpannableStringBuilder ssb = new SpannableStringBuilder(text);
        ssb.setSpan(new LeadingMarginSpan.Standard(indentPx, 0), 0, ssb.length(),
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ssb;
    }

    public static boolean isTitle(String text) {
        if (text == null) return false;
        return text.length() > 2 && text.startsWith("【") && text.endsWith("】");
    }

    private int imageHeight(String path) {
        if (path == null || path.isEmpty()) return Math.round(height * 0.2f);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return Math.round(height * 0.2f);
        float ratio = (float) o.outHeight / (float) o.outWidth;
        int h = Math.round(width * ratio);
        return Math.min(h, imageMaxHeight);
    }

    /** 第 n 页整体进度，0 到 100 */
    public int percentOfPage(int pageIndex) {
        if (pages.isEmpty()) return 100;
        if (pages.size() <= 1) return 100;
        Page p = pages.get(Math.max(0, Math.min(pageIndex, pages.size() - 1)));
        return Math.round(100f * p.firstChar / totalChars);
    }

    public int pageOfPara(int paraIndex) {
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (paraIndex >= p.startPara && paraIndex <= p.endPara) return i;
        }
        return paraIndex <= 0 ? 0 : pages.size() - 1;
    }

    public int pageForPercent(int percent) {
        if (pages.isEmpty()) return 0;
        int target = Math.round(totalChars * percent / 100f);
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).firstChar >= target) return i;
        }
        return pages.size() - 1;
    }

    /** 剩余阅读时间估算，按每分钟 300 个汉字 */
    public int minutesLeft(int pageIndex, int percent) {
        int charsLeft = Math.round(totalChars * (100 - percent) / 100f);
        return charsLeft / 300;
    }
}

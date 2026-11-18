package com.zz.reader.pro.core;

import java.util.ArrayList;
import java.util.List;

/** 一章：由若干"文本段"和"插图"按原始顺序组成 */
public class Chapter {
    public String title = "";
    /** 标题是程序凑出来的（如"第 3 节""正文"），不是书里原有的文字：这种标题不当作正文标题块插进去 */
    public boolean autoTitle = false;
    public final List<Item> items = new ArrayList<>();
    /** 这一章（PDF 的一页）还在等本地识别结果，识别完就地替换 */
    public boolean pendingOcr = false;

    public Chapter() {
    }

    public Chapter(String title) {
        this.title = title == null ? "" : title;
    }

    public Chapter(String title, boolean autoTitle) {
        this.title = title == null ? "" : title;
        this.autoTitle = autoTitle;
    }

    /**
     * 加正文。整页文字（PDF 的一页、OCR 的一页）不能当成一个整段塞进来：
     * 一个段落高过一页时排版只能让它独占一页，超出的部分在分页模式下看不到，会漏字。
     * 所以这里按行切开，按空行分段，每块最多几行、几十到一百多字，保证任何字号下都排得下。
     */
    public void addText(String s) {
        if (s == null) return;
        String t = s.replace("\r", "").trim();
        if (t.isEmpty()) return;
        if (!t.contains("\n") && t.length() <= 120) {
            items.add(Item.text(t));
            return;
        }
        runLengthCap(t);
        return;
    }

    private void runLengthCap(String t) {
        StringBuilder cur = new StringBuilder();
        int lines = 0;
        for (String raw : t.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                flush(cur);
                lines = 0;
                continue;
            }
            // 一行本身就特别长（没有换行的排版），按句号再切
            while (line.length() > 120) {
                int cut = Math.max(line.lastIndexOf('。', 120), line.lastIndexOf('，', 120));
                if (cut < 40) cut = 120;
                flush(cur);
                items.add(Item.text(line.substring(0, cut + 1).trim()));
                line = line.substring(cut + 1).trim();
            }
            if (line.isEmpty()) continue;
            boolean full = cur.length() + line.length() > 120 || lines >= 3;
            if (full) {
                flush(cur);
                lines = 0;
            }
            if (cur.length() > 0) cur.append('　');
            cur.append(line);
            lines++;
        }
        flush(cur);
    }

    private static int countLines(StringBuilder sb) {
        int n = 1;
        for (int i = 0; i < sb.length(); i++) if (sb.charAt(i) == '\n') n++;
        return n;
    }

    private void flush(StringBuilder cur) {
        String v = cur.toString().trim();
        cur.setLength(0);
        if (!v.isEmpty()) items.add(Item.text(v));
    }

    public void addImage(String path, String alt) {
        if (path == null || path.isEmpty()) return;
        items.add(Item.image(path, alt));
    }

    public boolean hasText() {
        for (Item i : items) {
            if (!i.isImage && !i.text.trim().isEmpty()) return true;
        }
        return false;
    }

    public static class Item {
        public final boolean isImage;
        public final String text;
        public final String imagePath;
        public final String alt;

        private Item(boolean isImage, String text, String imagePath, String alt) {
            this.isImage = isImage;
            this.text = text;
            this.imagePath = imagePath;
            this.alt = alt;
        }

        public static Item text(String s) {
            return new Item(false, s, null, null);
        }

        public static Item image(String path, String alt) {
            return new Item(true, null, path, alt);
        }
    }
}

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

    public void addText(String s) {
        if (s == null) return;
        String t = s.trim();
        if (t.isEmpty()) return;
        items.add(Item.text(t));
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

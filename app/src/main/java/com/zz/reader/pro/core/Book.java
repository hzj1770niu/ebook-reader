package com.zz.reader.pro.core;

import java.util.ArrayList;
import java.util.List;

/** 一本已解析完成的书 */
public class Book {

    public String title = "";
    public String author = "";
    public String format = "";
    public String encoding = "";
    public String sourcePath = "";
    public String sourceUri = "";
    public String coverPath = "";
    public long sourceSize = 0;
    /** PDF 扫描页（或开了文字层校对的页）：识别放到打开之后异步做，这里记着 pdf 和页对章的关系 */
    public String pdfPath;
    public int pdfPageCount;
    public boolean needsOcr = false;
    public final java.util.Map<Integer, Chapter> pdfPages = new java.util.HashMap<>();
    public final List<Chapter> chapters = new ArrayList<>();

    /** 拉平后的内容块（文本/插图），用于分屏、渲染与朗读 */
    private List<Block> blocks;

    /** PDF 用的缓存目录：识别结果按页存这里，插图索引也存这里，下次打开直接读，不用重跑 */
    public java.io.File ocrDir;
    public java.io.File mediaDir;

    public static class Block {
        public static final int TYPE_TEXT = 0;
        public static final int TYPE_IMAGE = 1;

        public int type = TYPE_TEXT;
        public int chapterIndex;
        public String chapterTitle;
        public String text;
        public String imagePath;
        public String imageAlt;

        public Block(int chapterIndex, String chapterTitle, String text) {
            this.chapterIndex = chapterIndex;
            this.chapterTitle = chapterTitle;
            this.type = TYPE_TEXT;
            this.text = text;
        }

        public Block(int chapterIndex, String chapterTitle, String imagePath, String imageAlt) {
            this.chapterIndex = chapterIndex;
            this.chapterTitle = chapterTitle;
            this.type = TYPE_IMAGE;
            this.imagePath = imagePath;
            this.imageAlt = imageAlt;
        }

        public boolean isImage() {
            return type == TYPE_IMAGE;
        }
    }

    public void addChapter(Chapter c) {
        chapters.add(c);
    }

    public void invalidateBlocks() {
        blocks = null;
    }

    public List<Block> blocks() {
        if (blocks != null) return blocks;
        List<Block> list = new ArrayList<>();
        for (int ci = 0; ci < chapters.size(); ci++) {
            Chapter c = chapters.get(ci);
            if (!c.autoTitle && c.title != null && !c.title.trim().isEmpty()) {
                list.add(new Block(ci, c.title, chapterHeader(c.title)));
            }
            for (Chapter.Item item : c.items) {
                if (item.isImage) {
                    list.add(new Block(ci, c.title, item.imagePath, item.alt));
                    continue;
                }
                String p = item.text;
                if (p == null || p.trim().isEmpty()) continue;
                if (p.length() > 1200) {
                    for (String piece : TextUtil.chunkLongText(p, 600)) {
                        list.add(new Block(ci, c.title, piece));
                    }
                } else {
                    list.add(new Block(ci, c.title, p));
                }
            }
        }
        blocks = list;
        return blocks;
    }

    /** 章节标题在正文中以特殊行显示，便于读屏念出"第X章" */
    public static String chapterHeader(String title) {
        return "【" + title + "】";
    }

    public int chapterStartBlock(int chapterIndex) {
        List<Block> bs = blocks();
        for (int i = 0; i < bs.size(); i++) {
            if (bs.get(i).chapterIndex == chapterIndex) return i;
        }
        return 0;
    }

    public int chapterOfBlock(int blockIndex) {
        List<Block> bs = blocks();
        if (blockIndex < 0 || blockIndex >= bs.size()) return 0;
        return bs.get(blockIndex).chapterIndex;
    }

    public String chapterTitle(int chapterIndex) {
        if (chapterIndex >= 0 && chapterIndex < chapters.size()) {
            return chapters.get(chapterIndex).title;
        }
        return "";
    }

    public int blockCount() {
        return blocks().size();
    }

    public int totalChars() {
        int total = 0;
        for (Chapter c : chapters) {
            for (Chapter.Item item : c.items) {
                if (!item.isImage && item.text != null) total += item.text.length();
            }
        }
        return total;
    }

    public int imageCount() {
        int n = 0;
        for (Chapter c : chapters) {
            for (Chapter.Item item : c.items) {
                if (item.isImage) n++;
            }
        }
        return n;
    }
}

package com.zz.reader.pro.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 分屏器。
 * 目标：一屏的文字量不会多到读屏软件读不完、也不会卡顿；
 * 插图算作固定的版面开销，不会把一屏挤满文字。
 */
public class Pager {

    public static class Screen {
        public int startBlock;
        public int endBlock;
        /** 这一屏里的纯文字（朗读用），可能为空 */
        public String text;
        public boolean hasImage;

        Screen(int startBlock, int endBlock, String text, boolean hasImage) {
            this.startBlock = startBlock;
            this.endBlock = endBlock;
            this.text = text;
            this.hasImage = hasImage;
        }
    }

    private final List<Screen> screens = new ArrayList<>();
    private final List<Book.Block> blocks;

    public Pager(Book book, int charsPerScreen) {
        this.blocks = book.blocks();
        int budget = Math.max(40, charsPerScreen);
        int imageCost = Math.max(40, budget / 3);
        int start = 0;
        StringBuilder buf = new StringBuilder();
        boolean hasImage = false;
        for (int i = 0; i < blocks.size(); i++) {
            Book.Block b = blocks.get(i);
            int extra;
            if (b.isImage()) {
                extra = imageCost;
            } else {
                extra = (b.text == null ? 0 : b.text.length()) + (buf.length() > 0 ? 1 : 0);
            }
            if (buf.length() > 0 || hasImage) {
                int used = buf.length() + (hasImage ? imageCost : 0);
                if (used + extra > budget * 1.15) {
                    screens.add(new Screen(start, i - 1, buf.toString(), hasImage));
                    buf.setLength(0);
                    hasImage = false;
                    start = i;
                }
            }
            if (b.isImage()) {
                hasImage = true;
            } else if (b.text != null) {
                if (buf.length() > 0) buf.append('\n');
                buf.append(b.text);
            }
        }
        if (buf.length() > 0 || hasImage) {
            screens.add(new Screen(start, blocks.size() - 1, buf.toString(), hasImage));
        }
        if (screens.isEmpty()) {
            screens.add(new Screen(0, 0, "（这本没有可显示的文字）", false));
        }
    }

    public int size() {
        return screens.size();
    }

    public Screen get(int index) {
        if (index < 0) index = 0;
        if (index >= screens.size()) index = screens.size() - 1;
        return screens.get(index);
    }

    /** 找到包含某内容块的屏 */
    public int screenForBlock(int blockIndex) {
        for (int i = 0; i < screens.size(); i++) {
            Screen s = screens.get(i);
            if (blockIndex >= s.startBlock && blockIndex <= s.endBlock) return i;
        }
        if (blockIndex <= 0) return 0;
        return screens.size() - 1;
    }

    /** 某章节第一次出现的屏 */
    public int screenForChapter(int chapterIndex) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).chapterIndex == chapterIndex) return screenForBlock(i);
        }
        return 0;
    }

    public String chapterTitleAt(int screenIndex) {
        Screen s = get(screenIndex);
        if (blocks.isEmpty()) return "";
        int b = Math.min(Math.max(s.startBlock, 0), blocks.size() - 1);
        return blocks.get(b).chapterTitle;
    }

    public int progressPercent(int screenIndex) {
        if (screens.size() <= 1) return 100;
        return (int) Math.round(100.0 * screenIndex / (screens.size() - 1));
    }
}

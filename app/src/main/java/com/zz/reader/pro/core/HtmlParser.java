package com.zz.reader.pro.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * 单个 HTML / HTM / XHTML 文件解析。
 * 内嵌式图片（data:image，体量不大时常见于单文件 HTML）会正常显示；
 * 指向别的文件夹的图片，因为文件已经被单独导入，取不到原图。
 */
public class HtmlParser implements BookParser {

    @Override
    public Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        if (file == null || !file.exists()) throw new ParseException("文件不存在或无法读取");
        byte[] data;
        try (FileInputStream in = new FileInputStream(file)) {
            data = EncodingDetector.readAll(in);
        }
        String encoding;
        if (forcedEncoding != null && !forcedEncoding.trim().isEmpty()
                && !"自动检测".equals(forcedEncoding)) {
            encoding = forcedEncoding;
        } else {
            encoding = guessEncoding(data);
        }
        String html = EncodingDetector.decodeStrict(data, encoding);
        if (html == null) html = EncodingDetector.decodeLenient(data, encoding);

        Book book = new Book();
        book.format = "HTML";
        book.encoding = encoding;
        book.title = TextUtil.cleanTitle(TxtParser.stripExtension(file.getName()));

        String title = extractTitle(html);
        if (title != null && !title.isEmpty()) book.title = TextUtil.cleanTitle(title);

        // 正文按 h1/h2 分章；没有标题就整体一章
        for (String[] part : TextUtil.splitHtmlChapters(html)) {
            boolean autoTitle = part[0] == null || part[0].trim().isEmpty();
            String pt = autoTitle ? book.title : part[0].trim();
            Chapter chapter = new Chapter(TextUtil.cleanTitle(pt), autoTitle);
            HtmlContentParser.parseInto(chapter, part[1], (src, alt) -> {
                if (src == null || !src.startsWith("data:")) return null;
                String b64 = src.substring(src.indexOf(',') + 1);
                if (images == null || mediaDir == null) return null;
                return images.saveBase64(b64, mediaDir, "html:" + alt + b64.length());
            });
            if (chapter.items.isEmpty()) continue;
            book.addChapter(chapter);
        }
        if (book.chapters.isEmpty()) throw new ParseException("这个 HTML 里没有可读文字");
        if (images != null && mediaDir != null) {
            book.coverPath = images.generateCover(book.title, book.author, mediaDir);
        }
        return book;
    }

    private String guessEncoding(byte[] data) {
        String head = new String(data, 0, Math.min(data.length, 2048), java.nio.charset.StandardCharsets.ISO_8859_1);
        String lower = head.toLowerCase();
        int idx = lower.indexOf("charset=");
        if (idx >= 0) {
            int start = idx + 8;
            int end = start;
            while (end < head.length() && (Character.isLetterOrDigit(head.charAt(end))
                    || head.charAt(end) == '-' || head.charAt(end) == '_')) {
                end++;
            }
            String cs = head.substring(start, end).trim().toLowerCase();
            if (cs.startsWith("utf-8") || cs.equals("utf8")) return EncodingDetector.UTF8;
            if (cs.startsWith("gb") || cs.equals("euc-cn") || cs.equals("cp936")) return EncodingDetector.GB18030;
            if (cs.startsWith("big5") || cs.equals("cp950")) return EncodingDetector.BIG5;
            if (cs.equals("utf-16le")) return EncodingDetector.UTF16LE;
            if (cs.equals("utf-16be")) return EncodingDetector.UTF16BE;
        }
        return EncodingDetector.detect(data);
    }

    private String extractTitle(String html) {
        try {
            String lower = html.toLowerCase();
            int s = lower.indexOf("<title");
            if (s < 0) return null;
            int gt = lower.indexOf('>', s);
            int e = lower.indexOf("</title>", gt);
            if (gt < 0 || e < 0) return null;
            return TextUtil.htmlToPlain(html.substring(gt + 1, e)).trim();
        } catch (Exception ex) {
            return null;
        }
    }
}

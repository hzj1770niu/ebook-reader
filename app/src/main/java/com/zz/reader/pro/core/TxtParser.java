package com.zz.reader.pro.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * TXT 解析：编码自动识别 + 章节切分。
 * 只要文件本身正常（非二进制乱码文件），就不会出现乱码。
 */
public class TxtParser implements BookParser {

    private static final long MAX_SIZE = 80L * 1024 * 1024; // 80MB 上限，避免低内存手机解析崩溃

    @Override
    public Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        if (file == null || !file.exists()) throw new ParseException("文件不存在或无法读取");
        if (file.length() > MAX_SIZE) throw new ParseException("文件太大，暂不支持超过 80MB 的单个文本");
        byte[] data;
        try (FileInputStream in = new FileInputStream(file)) {
            data = EncodingDetector.readAll(in);
        }
        Book book = parseBytes(data, file.getName(), forcedEncoding);
        if (images != null && mediaDir != null) {
            book.coverPath = images.generateCover(book.title, book.author, mediaDir);
        }
        return book;
    }

    public Book parseBytes(byte[] data, String fileName, String forcedEncoding) throws ParseException {
        String encoding;
        String text;
        if (forcedEncoding != null && !forcedEncoding.trim().isEmpty()
                && !"自动检测".equals(forcedEncoding)) {
            encoding = forcedEncoding;
            text = EncodingDetector.decodeStrict(data, encoding);
            if (text == null) {
                text = EncodingDetector.decodeLenient(data, encoding);
            }
        } else {
            encoding = EncodingDetector.detect(data);
            text = EncodingDetector.decodeStrict(data, encoding);
            if (text == null || EncodingDetector.garbageCount(text) > 0) {
                String best = null;
                int bestBad = Integer.MAX_VALUE;
                String[] candidates = {EncodingDetector.UTF8, EncodingDetector.GB18030,
                        EncodingDetector.BIG5, EncodingDetector.UTF16LE, EncodingDetector.UTF16BE};
                for (String cs : candidates) {
                    String s = EncodingDetector.decodeStrict(data, cs);
                    if (s == null) continue;
                    int bad = EncodingDetector.garbageCount(s);
                    if (bad < bestBad) {
                        bestBad = bad;
                        best = s;
                        encoding = cs;
                    }
                    if (bad == 0) break;
                }
                if (best != null) {
                    text = best;
                } else {
                    text = EncodingDetector.decodeLenient(data, EncodingDetector.GB18030);
                    encoding = EncodingDetector.GB18030;
                }
            }
        }
        if (text == null || text.trim().isEmpty()) {
            throw new ParseException("这个文件里没有可读的文字，可能不是文本格式");
        }

        Book book = new Book();
        book.format = "TXT";
        book.encoding = encoding;
        book.title = stripExtension(fileName);
        book.author = "";

        List<String> paragraphs = TextUtil.splitParagraphs(text);
        buildChapters(book, paragraphs);
        if (book.chapters.isEmpty()) {
            Chapter c = new Chapter("正文", true);
            for (String p : paragraphs) c.addText(p);
            book.addChapter(c);
        }
        return book;
    }

    private void buildChapters(Book book, List<String> paragraphs) {
        Chapter current = null;
        List<String> pre = new ArrayList<>();
        for (String p : paragraphs) {
            if (TextUtil.isChapterTitle(p)) {
                if (current != null) book.addChapter(current);
                if (current == null && !pre.isEmpty()) {
                    Chapter head = new Chapter("开始", true);
                    for (String s : pre) head.addText(s);
                    book.addChapter(head);
                    pre.clear();
                }
                current = new Chapter(p);
            } else {
                if (current == null) {
                    pre.add(p);
                } else {
                    current.addText(p);
                }
            }
        }
        if (current != null) book.addChapter(current);
        if (book.chapters.isEmpty() && !pre.isEmpty()) {
            Chapter c = new Chapter("正文", true);
            for (String s : pre) c.addText(s);
            book.addChapter(c);
        }
    }

    public static String stripExtension(String name) {
        if (name == null) return "未命名";
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot);
        return name;
    }
}

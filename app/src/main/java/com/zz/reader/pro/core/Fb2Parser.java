package com.zz.reader.pro.core;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FB2（FictionBook）解析，含封面与插图（<binary> 里的图片） */
public class Fb2Parser implements BookParser {

    private final Map<String, String> binaryPaths = new HashMap<>();
    private String coverId;
    private boolean inCoverPage = false;

    @Override
    public Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        byte[] data;
        try (FileInputStream in = new FileInputStream(file)) {
            data = EncodingDetector.readAll(in);
        }
        String text = EncodingDetector.decodeStrict(data, EncodingDetector.UTF8);
        if (text == null) text = EncodingDetector.decodeLenient(data, EncodingDetector.detect(data));

        Book book = new Book();
        book.format = "FB2";
        book.encoding = "UTF-8";
        book.title = TextUtil.cleanTitle(TxtParser.stripExtension(file.getName()));

        binaryPaths.clear();
        coverId = null;
        inCoverPage = false;

        preScanBinaries(data, images, mediaDir);

        try {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(new ByteArrayInputStream(data), null);
            int ev;
            Chapter current = null;
            StringBuilder titleBuf = null;


            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                String tag = localName(p.getName());
                if (ev == XmlPullParser.START_TAG) {
                    if (tag.equalsIgnoreCase("book-title")) {
                        String t = readText(p);
                        if (!t.trim().isEmpty()) book.title = TextUtil.cleanTitle(t);
                    } else if (tag.equalsIgnoreCase("coverpage")) {
                        inCoverPage = true;
                    } else if (tag.equalsIgnoreCase("binary")) {
                        readText(p);
                    } else if (tag.equalsIgnoreCase("image")) {
                        String href = attrHref(p);
                        String path = href == null ? null : binaryPaths.get(href);
                        if (inCoverPage) {
                            if (path != null) coverId = path;
                        } else if (current != null) {
                            if (path != null) {
                                current.addImage(path, null);
                            } else {
                                current.addText("插图");
                            }
                        }
                    } else if (tag.equalsIgnoreCase("title")) {
                        if (titleBuf == null) titleBuf = new StringBuilder();
                        titleBuf.setLength(0);
                        // 读取标题文本
                        int depth = 1;
                        while (depth > 0) {
                            int e2 = p.next();
                            if (e2 == XmlPullParser.START_TAG) depth++;
                            else if (e2 == XmlPullParser.END_TAG) depth--;
                            else if (e2 == XmlPullParser.TEXT) titleBuf.append(p.getText());
                        }
                        String t = TextUtil.cleanTitle(titleBuf.toString());
                        if (!t.isEmpty()) {
                            if (current != null && !current.items.isEmpty()) {
                                book.addChapter(current);
                            }
                            current = new Chapter(t);
                        }
                    } else if (tag.equalsIgnoreCase("p")) {
                        String t = TextUtil.cleanTitle(readText(p));
                        if (t.isEmpty()) continue;
                        if (current == null) {
                            current = new Chapter(book.title, true);
                        }
                        current.addText(t);
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (tag.equalsIgnoreCase("coverpage")) {
                        inCoverPage = false;
                    } else if (tag.equalsIgnoreCase("section")) {
                        if (current != null && !current.items.isEmpty()) {
                            book.addChapter(current);
                            current = null;
                        }
                        current = null;
                    }
                }
            }
            if (current != null && !current.items.isEmpty()) book.addChapter(current);
        } catch (Exception e) {
            throw new ParseException("FB2 文件格式异常，无法解析", e);
        }
        if (book.chapters.isEmpty()) throw new ParseException("这个 FB2 里没有可读文字");
        book.coverPath = coverId;
        return book;
    }

    /** 图片数据块通常在正文后面，先整体扫一遍，正文里的图片引用才能对上 */
    private void preScanBinaries(byte[] data, ImageStore images, File mediaDir) {
        if (images == null || mediaDir == null) return;
        try {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(new ByteArrayInputStream(data), null);
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "binary".equals(localName(p.getName()))) {
                    String id = attr(p, "id");
                    String body = readText(p);
                    if (id != null && !body.isEmpty()) {
                        String path = images.saveBase64(body, mediaDir, "fb2:" + id);
                        if (path != null) binaryPaths.put("#" + id, path);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String localName(String raw) {
        if (raw == null) return "";
        int i = raw.indexOf(':');
        return i >= 0 ? raw.substring(i + 1) : raw;
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            if (n != null && n.equals(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static String attrHref(XmlPullParser p) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            if (n == null) continue;
            String lower = n.toLowerCase();
            if (lower.equals("href") || lower.endsWith(":href")) return p.getAttributeValue(i);
        }
        return null;
    }

    private static String attrLower(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            if (n != null && n.equalsIgnoreCase(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static String readText(XmlPullParser p) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
            else if (ev == XmlPullParser.TEXT) sb.append(p.getText());
        }
        return sb.toString();
    }
}

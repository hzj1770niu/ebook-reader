package com.zz.reader.pro.core;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** DOCX 解析：正文文字 + 内嵌图片（word/media）+ 文档缩略图当封面 */
public class DocxParser implements BookParser {

    private ZipFile zip;
    private ImageStore images;
    private File mediaDir;
    private final Map<String, String> rels = new HashMap<>();
    private final Map<String, String> imageCache = new HashMap<>();

    @Override
    public Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        ZipFile zf;
        try {
            zf = new ZipFile(file);
        } catch (Exception e) {
            throw new ParseException("这个 DOCX 打不开，可能文件已损坏", e);
        }
        this.zip = zf;
        this.images = images;
        this.mediaDir = mediaDir;
        rels.clear();
        imageCache.clear();
        try {
            byte[] docXml = readEntry("word/document.xml");
            if (docXml == null) throw new ParseException("这不是有效的 Word 文档（找不到正文部分）");

            readRels();

            Book book = new Book();
            book.format = "DOCX";
            book.encoding = "UTF-8";
            book.title = TextUtil.cleanTitle(TxtParser.stripExtension(file.getName()));
            book.author = readCoreAuthor();

            List<Chapter> chapters = parseDocument(docXml, book);
            if (chapters.isEmpty()) throw new ParseException("这个 Word 文档里没有可读文字");

            book.coverPath = readThumbnail();
            if (book.coverPath == null && !imageCache.isEmpty()) {
                for (String p : imageCache.values()) {
                    if (p != null) {
                        book.coverPath = p;
                        break;
                    }
                }
            }
            return book;
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
            this.zip = null;
        }
    }

    private void readRels() {
        byte[] data = readEntry("word/_rels/document.xml.rels");
        if (data == null) return;
        try {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(new ByteArrayInputStream(data), null);
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "Relationship".equals(p.getName())) {
                    String id = null, target = null;
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        String n = p.getAttributeName(i);
                        if ("Id".equals(n)) id = p.getAttributeValue(i);
                        else if ("Target".equals(n)) target = p.getAttributeValue(i);
                    }
                    if (id != null && target != null) {
                        String path = target.startsWith("/") ? target.substring(1) : "word/" + target;
                        rels.put(id, path);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private List<Chapter> parseDocument(byte[] docXml, Book book) throws ParseException {
        List<Chapter> chapters = new ArrayList<>();
        try {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(new ByteArrayInputStream(docXml), null);
            int ev;
            Chapter current = new Chapter("正文", true);
            String paraStyle = null;
            StringBuilder para = new StringBuilder();
            List<String> paraImages = new ArrayList<>();
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = localName(p.getName());
                    if ("pStyle".equals(tag)) {
                        for (int ai = 0; ai < p.getAttributeCount(); ai++) {
                            String an = p.getAttributeName(ai);
                            if (an != null && ("val".equals(an) || "w:val".equals(an))) {
                                paraStyle = p.getAttributeValue(ai);
                            }
                        }
                    }
                    if ("t".equals(tag)) {
                        String t = readText(p);
                        if (t != null) para.append(t);
                    } else if ("blip".equals(tag) || "imagedata".equals(tag)) {
                        String id = null;
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            String n = p.getAttributeName(i);
                            if (n != null && (n.equals("embed") || n.equals("id") || n.equals("r:embed") || n.equals("r:id"))) {
                                id = p.getAttributeValue(i);
                            }
                        }
                        if (id != null) {
                            String path = imagePathFor(id);
                            if (path != null) paraImages.add(path);
                        }
                    } else if ("tab".equals(tag)) {
                        para.append('\u3000');
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    String tag = localName(p.getName());
                    if ("p".equals(tag) || "br".equals(tag)) {
                        String t = para.toString();
                        para.setLength(0);
                        if (!paraImages.isEmpty()) {
                            for (String img : paraImages) current.addImage(img, null);
                            paraImages.clear();
                        }
                        boolean headingStyle = paraStyle != null
                                && (paraStyle.toLowerCase().contains("heading")
                                || paraStyle.equals("1") || paraStyle.equals("2")
                                || paraStyle.equals("标题1") || paraStyle.equals("标题2"));
                        paraStyle = null;
                        if (t != null && !t.trim().isEmpty()) {
                            String clean = t.replace('\u00a0', ' ').trim();
                            if ((headingStyle && clean.length() <= 60)
                                    || (TextUtil.isChapterTitle(clean) && clean.length() <= 44)) {
                                chapters.add(current);
                                current = new Chapter(clean);
                            } else {
                                current.addText(clean);
                            }
                        }
                    }
                }
            }
            if (!current.items.isEmpty()) chapters.add(current);
            for (Chapter c : chapters) {
                if (!c.items.isEmpty()) book.addChapter(c);
            }
        } catch (Exception e) {
            throw new ParseException("Word 正文解析失败，文件可能已损坏", e);
        }
        return chapters;
    }

    private String imagePathFor(String rId) {
        if (imageCache.containsKey(rId)) return imageCache.get(rId);
        String path = rels.get(rId);
        String local = null;
        if (path != null) {
            byte[] data = readEntry(path);
            if (data != null && images != null && mediaDir != null) {
                local = images.save(data, mediaDir, "docx:" + path);
            }
        }
        imageCache.put(rId, local);
        return local;
    }

    private String readThumbnail() {
        byte[] data = readEntry("docProps/thumbnail.jpeg");
        if (data == null) data = readEntry("docProps/thumbnail.jpg");
        if (data == null || images == null || mediaDir == null) return null;
        return images.save(data, mediaDir, "docx:thumbnail");
    }

    private String readCoreAuthor() {
        byte[] data = readEntry("docProps/core.xml");
        if (data == null) return "";
        try {
            XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
            p.setInput(new ByteArrayInputStream(data), null);
            int ev;
            String lastTag = "";
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    lastTag = p.getName() == null ? "" : p.getName();
                } else if (ev == XmlPullParser.TEXT && "creator".equals(lastTag)) {
                    return TextUtil.cleanTitle(p.getText());
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String localName(String raw) {
        if (raw == null) return "";
        int i = raw.indexOf(':');
        return i >= 0 ? raw.substring(i + 1) : raw;
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

    private byte[] readEntry(String name) {
        if (name == null || zip == null) return null;
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return EncodingDetector.readAll(in);
        } catch (IOException ex) {
            return null;
        }
    }
}

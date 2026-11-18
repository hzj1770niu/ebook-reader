package com.zz.reader.pro.core;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** EPUB 2 / EPUB 3 解析：自己实现，含封面与章节插图提取 */
public class EpubParser implements BookParser {

    private ZipFile zip;
    private ImageStore images;
    private File mediaDir;
    private final Map<String, String> extracted = new HashMap<>();

    @Override
    public Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        ZipFile zf;
        try {
            zf = new ZipFile(file);
        } catch (Exception e) {
            throw new ParseException("这个 EPUB 文件打不开，可能文件已损坏或没有完整下载", e);
        }
        this.zip = zf;
        this.images = images;
        this.mediaDir = mediaDir;
        try {
            Book book = new Book();
            book.format = "EPUB";
            book.title = TextUtil.cleanTitle(TxtParser.stripExtension(file.getName()));

            String opfPath = readContainer();
            if (opfPath == null) {
                for (ZipEntry e : Collections.list(zip.entries())) {
                    if (e.getName().toLowerCase().endsWith(".opf")) {
                        opfPath = e.getName();
                        break;
                    }
                }
            }
            if (opfPath == null) throw new ParseException("这个 EPUB 里找不到书籍描述文件，可能不是标准 EPUB");

            String baseDir = dirOf(opfPath);
            Opf opf = readOpf(opfPath);
            if (opf.title != null && !opf.title.trim().isEmpty()) {
                book.title = TextUtil.cleanTitle(opf.title);
            }
            if (opf.creator != null) book.author = TextUtil.cleanTitle(opf.creator);

            // 封面
            String coverHref = opf.coverHref;
            if (coverHref == null) coverHref = guessCoverHref(opf);
            if (coverHref != null) {
                book.coverPath = extractImage(resolve(baseDir, coverHref));
            }

            Map<String, String> tocTitles = readToc(baseDir, opf);

            if (opf.spineHrefs.isEmpty()) {
                throw new ParseException("这个 EPUB 没有可读的正文顺序信息，可能文件损坏");
            }

            for (String href : opf.spineHrefs) {
                final String chapterPath = resolve(baseDir, href);
                byte[] data = readEntry(chapterPath);
                if (data == null && href.startsWith("/")) {
                    data = readEntry(href.substring(1));
                    if (data != null) {
                        // 用实际路径做相对基准
                    }
                }
                if (data == null) continue;
                String html = decodeText(data);

                String title = findTitle(tocTitles, href, chapterPath);
                if (title == null) title = firstHeading(html);
                final String chapterDir = dirOf(chapterPath);
                final String fileTitle = title;

                // 很多 EPUB 把整本塞进一个 xhtml，只按文件分章会得到一章，这里按 h1/h2 再切
                java.util.List<String[]> parts = TextUtil.splitHtmlChapters(html);
                boolean wroteAny = false;
                for (int pi = 0; pi < parts.size(); pi++) {
                    String partTitle = parts.get(pi)[0];
                    String body = parts.get(pi)[1];
                    boolean autoTitle = false;
                    if (partTitle == null || partTitle.trim().isEmpty()) {
                        autoTitle = true;
                        partTitle = (pi == 0 && fileTitle != null) ? fileTitle
                                : "第 " + (book.chapters.size() + 1) + " 节";
                    }
                    Chapter c = new Chapter(TextUtil.cleanTitle(partTitle), autoTitle);
                    HtmlContentParser.parseInto(c, body, (src, alt) -> {
                        if (src == null) return null;
                        if (src.startsWith("data:")) return saveDataUri(src, alt);
                        String path = resolve(chapterDir, src);
                        return extractImage(path);
                    });
                    if (c.items.isEmpty()) continue;
                    book.addChapter(c);
                    wroteAny = true;
                }
                if (!wroteAny) continue;
            }

            if (book.chapters.isEmpty()) throw new ParseException("这个 EPUB 里没有提取到可读文字");
            if (book.coverPath == null) {
                book.coverPath = findAnyCoverImage(opf, baseDir);
            }
            book.encoding = "UTF-8";
            return book;
        } finally {
            try {
                zip.close();
            } catch (IOException ignored) {
            }
            this.zip = null;
        }
    }

    // ---------------------------------------------------------------- 图片

    private String extractImage(String zipPath) {
        if (zipPath == null) return null;
        String cached = extracted.get(zipPath);
        if (cached != null) return cached;
        byte[] data = readEntry(zipPath);
        if (data == null) return null;
        if (images == null || mediaDir == null) return null;
        String ext = ImageStore.sniffExt(data);
        if (ext == null) {
            // SVG 封面等这里显示不了，记为取不到，让调用方退回到默认封面
            extracted.put(zipPath, "");
            return null;
        }
        String path = images.save(data, mediaDir, "epub:" + zipPath);
        extracted.put(zipPath, path == null ? "" : path);
        return path;
    }

    private String saveDataUri(String src, String alt) {
        try {
            int comma = src.indexOf(',');
            if (comma < 0) return null;
            String meta = src.substring(5, comma);
            if (!meta.contains("base64")) return null;
            String b64 = src.substring(comma + 1);
            if (images == null || mediaDir == null) return null;
            return images.saveBase64(b64, mediaDir, "epub-data:" + alt + b64.length());
        } catch (Exception e) {
            return null;
        }
    }

    private String findAnyCoverImage(Opf opf, String baseDir) {
        // 没有明确封面时，找第一张符合"封面/cover"命名的图片
        for (Map.Entry<String, String> e : opf.manifest.entrySet()) {
            String v = e.getValue();
            String href = v.substring(0, v.indexOf('\u0000'));
            String type = v.substring(v.indexOf('\u0000') + 1);
            if (!type.startsWith("image/")) continue;
            String lower = href.toLowerCase();
            if (lower.contains("cover") || lower.contains("封面")) {
                String p = extractImage(resolve(baseDir, href));
                if (p != null) return p;
            }
        }
        return null;
    }

    private String guessCoverHref(Opf opf) {
        return null;
    }

    // ---------------------------------------------------------------- OPF

    private static class Opf {
        String title;
        String creator;
        String tocId;
        String navPath;
        String ncxPath;
        String coverHref;
        List<String> spineHrefs = new ArrayList<>();
        Map<String, String> manifest = new LinkedHashMap<>();
    }

    private String readContainer() {
        byte[] data = readEntry("META-INF/container.xml");
        if (data == null) return null;
        try {
            XmlPullParser p = newParser();
            p.setInput(new ByteArrayInputStream(data), null);
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "rootfile".equals(p.getName())) {
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        if ("full-path".equals(p.getAttributeName(i))) return p.getAttributeValue(i);
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private Opf readOpf(String opfPath) throws IOException, ParseException {
        byte[] data = readEntry(opfPath);
        if (data == null) throw new ParseException("EPUB 描述文件读不出来");
        Opf opf = new Opf();
        List<String> spineIds = new ArrayList<>();
        String metaCoverId = null;
        String guideCoverHref = null;
        Map<String, String> properties = new HashMap<>();
        try {
            XmlPullParser p = newParser();
            p.setInput(new ByteArrayInputStream(data), null);
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev != XmlPullParser.START_TAG) continue;
                String tag = localName(p.getName());
                if (tag.equals("title") && opf.title == null) {
                    opf.title = readText(p);
                } else if (tag.equals("creator") && opf.creator == null) {
                    opf.creator = readText(p);
                } else if (tag.equals("meta")) {
                    String name = attr(p, "name");
                    String content = attr(p, "content");
                    if ("cover".equalsIgnoreCase(name) && content != null) metaCoverId = content;
                } else if (tag.equals("item")) {
                    String id = attr(p, "id");
                    String href = attr(p, "href");
                    String type = attr(p, "media-type");
                    String props = attr(p, "properties");
                    if (id != null && href != null) {
                        opf.manifest.put(id, href + "\u0000" + (type == null ? "" : type));
                        properties.put(id, props == null ? "" : props);
                        if (props != null && props.contains("cover-image")) {
                            opf.coverHref = href;
                        }
                    }
                    if (props != null && props.contains("nav") && href != null) opf.navPath = href;
                } else if (tag.equals("spine")) {
                    opf.tocId = attr(p, "toc");
                } else if (tag.equals("itemref")) {
                    String idref = attr(p, "idref");
                    if (idref != null) spineIds.add(idref);
                } else if (tag.equals("reference")) {
                    String type = attr(p, "type");
                    String href = attr(p, "href");
                    if ("cover".equalsIgnoreCase(type) && href != null) guideCoverHref = href;
                }
            }
        } catch (Exception e) {
            throw new ParseException("EPUB 描述文件格式异常，无法解析", e);
        }

        if (opf.coverHref == null && metaCoverId != null) {
            String v = opf.manifest.get(metaCoverId);
            if (v != null) opf.coverHref = v.substring(0, v.indexOf('\u0000'));
        }
        if (opf.coverHref == null && guideCoverHref != null) {
            opf.coverHref = guideCoverHref;
        }

        for (String id : spineIds) {
            String v = opf.manifest.get(id);
            if (v == null) continue;
            String href = v.substring(0, v.indexOf('\u0000'));
            String type = v.substring(v.indexOf('\u0000') + 1);
            if (type.contains("xhtml") || type.contains("html") || type.isEmpty()) {
                opf.spineHrefs.add(href);
            }
        }
        if (opf.tocId != null) {
            String v = opf.manifest.get(opf.tocId);
            if (v != null) opf.ncxPath = v.substring(0, v.indexOf('\u0000'));
        }
        return opf;
    }

    private static String localName(String raw) {
        if (raw == null) return "";
        int i = raw.indexOf(':');
        return i >= 0 ? raw.substring(i + 1) : raw;
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            String n = p.getAttributeName(i);
            if (n != null && (n.equals(name) || localName(n).equals(name))) return p.getAttributeValue(i);
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
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 目录

    private Map<String, String> readToc(String baseDir, Opf opf) {
        Map<String, String> map = new HashMap<>();
        if (opf.navPath != null) {
            byte[] data = readEntry(resolve(baseDir, opf.navPath));
            if (data != null) parseNav(data, map);
        }
        if (opf.ncxPath != null && map.isEmpty()) {
            byte[] data = readEntry(resolve(baseDir, opf.ncxPath));
            if (data != null) parseNcx(data, map);
        }
        return map;
    }

    private void parseNav(byte[] data, Map<String, String> out) {
        try {
            String html = decodeText(data);
            int idx = 0;
            while (true) {
                int a = html.indexOf("<a", idx);
                if (a < 0) break;
                int tagEnd = html.indexOf('>', a);
                if (tagEnd < 0) break;
                String tag = html.substring(a, tagEnd);
                int end = html.indexOf("</a>", tagEnd);
                if (end < 0) break;
                String inner = html.substring(tagEnd + 1, end);
                String text = TextUtil.htmlToPlain(inner).trim();
                int h = tag.indexOf("href=");
                if (h > 0 && text.length() > 0) {
                    char q = tag.charAt(h + 5);
                    int s = h + 6;
                    int e = tag.indexOf(q, s);
                    if (e > s) {
                        String href = tag.substring(s, e);
                        String key = stripHash(href);
                        if (!out.containsKey(key)) out.put(key, text);
                    }
                }
                idx = end + 4;
            }
        } catch (Exception ignored) {
        }
    }

    private void parseNcx(byte[] data, Map<String, String> out) {
        try {
            XmlPullParser p = newParser();
            p.setInput(new ByteArrayInputStream(data), null);
            int ev;
            String label = null;
            String src = null;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = localName(p.getName());
                    if ("content".equals(tag)) {
                        String s = attr(p, "src");
                        if (s != null) src = s;
                    } else if ("text".equals(tag)) {
                        StringBuilder sb = new StringBuilder();
                        int depth = 1;
                        while (depth > 0) {
                            int e2 = p.next();
                            if (e2 == XmlPullParser.START_TAG) depth++;
                            else if (e2 == XmlPullParser.END_TAG) depth--;
                            else if (e2 == XmlPullParser.TEXT) sb.append(p.getText());
                        }
                        label = sb.toString().trim();
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("navPoint".equals(localName(p.getName()))) {
                        if (label != null && !label.isEmpty() && src != null) {
                            String key = stripHash(src);
                            if (!out.containsKey(key)) out.put(key, label);
                        }
                        label = null;
                        src = null;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String findTitle(Map<String, String> toc, String href, String path) {
        String k1 = stripHash(href);
        String k2 = stripHash(path);
        String t = toc.get(k1);
        if (t != null) return t;
        t = toc.get(k2);
        if (t != null) return t;
        for (Map.Entry<String, String> e : toc.entrySet()) {
            String k = e.getKey();
            if (k.endsWith(k1) || k1.endsWith(k)) return e.getValue();
        }
        return null;
    }

    private String firstHeading(String html) {
        try {
            String lower = html.toLowerCase();
            int idx = -1;
            for (String tag : new String[]{"<h1", "<h2", "<h3"}) {
                int i = lower.indexOf(tag);
                if (i >= 0 && (idx < 0 || i < idx)) idx = i;
            }
            if (idx < 0) return null;
            int tagEnd = html.indexOf('>', idx);
            int end = lower.indexOf("</h", tagEnd);
            if (tagEnd < 0 || end < 0) return null;
            return TextUtil.htmlToPlain(html.substring(tagEnd + 1, end)).trim();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 基础

    private String decodeText(byte[] data) {
        String s = EncodingDetector.decodeStrict(data, EncodingDetector.UTF8);
        if (s != null && EncodingDetector.garbageCount(s) == 0) return s;
        String enc = EncodingDetector.detect(data);
        return EncodingDetector.decodeLenient(data, enc);
    }

    private byte[] readEntry(String path) {
        if (path == null || zip == null) return null;
        ZipEntry e = zip.getEntry(path);
        if (e == null) e = zip.getEntry(path.replaceAll("^\\./", ""));
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return EncodingDetector.readAll(in);
        } catch (IOException ex) {
            return null;
        }
    }

    private static XmlPullParser newParser() throws Exception {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newPullParser();
    }

    static String stripHash(String href) {
        int i = href.indexOf('#');
        return i >= 0 ? href.substring(0, i) : href;
    }

    static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(0, i + 1) : "";
    }

    static String resolve(String baseDir, String href) {
        String h = stripHash(href);
        try {
            h = java.net.URLDecoder.decode(h.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        String full = (h.startsWith("/") ? h.substring(1) : baseDir + h);
        List<String> parts = new ArrayList<>();
        for (String p : full.split("/")) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else {
                parts.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}

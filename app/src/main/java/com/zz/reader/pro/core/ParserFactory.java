package com.zz.reader.pro.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class ParserFactory {

    public static final String SUPPORTED_TEXT =
            "支持 EPUB、Kindle 的 MOBI/AZW3/PRC/AZW、PDF（文字版与扫描版，扫描页本地识别）、TXT、FB2、HTML、RTF、DOCX（Word）";

    public static Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        if (file == null || !file.isFile()) throw new ParseException("找不到这个文件");
        String name = file.getName();
        String ext = extOf(name);

        Book book = dispatch(file, ext, forcedEncoding, images, mediaDir);
        if (book != null) {
            afterParse(book, file, images, mediaDir);
            return book;
        }

        // 无扩展名或陌生扩展名：用文件头嗅探
        String sniff = sniff(file);
        book = dispatch(file, sniff, forcedEncoding, images, mediaDir);
        if (book == null) book = new TxtParser().parse(file, forcedEncoding, images, mediaDir);
        afterParse(book, file, images, mediaDir);
        return book;
    }

    private static Book dispatch(File file, String ext, String enc, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        if (ext.isEmpty()) return null;
        if (ext.equals("pdf")) return new PdfBookParser().parse(file, enc, images, mediaDir);
        if (ext.equals("epub")) return new EpubParser().parse(file, enc, images, mediaDir);
        if (ext.equals("mobi") || ext.equals("prc") || ext.equals("azw") || ext.equals("azw3")
                || ext.equals("kf8") || ext.equals("azw4")) {
            return new MobiParser().parse(file, enc, images, mediaDir);
        }
        if (ext.equals("fb2") || ext.equals("fb2.zip") || ext.equals("fast")) {
            return new Fb2Parser().parse(file, enc, images, mediaDir);
        }
        if (ext.equals("html") || ext.equals("htm") || ext.equals("xhtml") || ext.equals("xht")) {
            return new HtmlParser().parse(file, enc, images, mediaDir);
        }
        if (ext.equals("rtf")) return new RtfParser().parse(file, enc, images, mediaDir);
        if (ext.equals("docx")) return new DocxParser().parse(file, enc, images, mediaDir);
        if (ext.equals("txt") || ext.equals("text") || ext.equals("log") || ext.equals("md")
                || ext.equals("markdown") || ext.equals("csv") || ext.equals("json")
                || ext.equals("xml")) {
            return new TxtParser().parse(file, enc, images, mediaDir);
        }
        return null;
    }

    /** 兜底：任何格式没拿到封面时，生成一张按书名排版的封面，书库列表不会缺口 */
    private static void afterParse(Book book, File file, ImageStore images, File mediaDir) {
        if (book == null || images == null || mediaDir == null) return;
        if (book.coverPath != null && !book.coverPath.isEmpty()
                && new File(book.coverPath).exists()) {
            return;
        }
        book.coverPath = images.generateCover(book.title, book.author, mediaDir);
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    private static String sniff(File file) throws IOException {
        byte[] head = new byte[64];
        int n;
        try (FileInputStream in = new FileInputStream(file)) {
            n = in.read(head);
        }
        if (n < 8) return "txt";
        if (head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(file)) {
                if (zf.getEntry("mimetype") != null) return "epub";
                if (zf.getEntry("word/document.xml") != null) return "docx";
            } catch (Exception ignored) {
            }
            return "epub";
        }
        if (n >= 60) {
            String type = new String(head, 60, 4, "ISO-8859-1").trim();
            if (type.equals("BOOK") || type.equals("TEXt")) return "mobi";
        }
        String s = new String(head, 0, n, "ISO-8859-1");
        if (s.startsWith("{\\rtf")) return "rtf";
        if (s.contains("<html") || s.contains("<!DOCTYPE html") || s.contains("<?xml")) {
            if (s.contains("FictionBook")) return "fb2";
            return "html";
        }
        return "txt";
    }
}

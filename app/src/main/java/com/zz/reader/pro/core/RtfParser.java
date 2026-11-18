package com.zz.reader.pro.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/** RTF 解析：剥离格式控制字，只取可读文字 */
public class RtfParser implements BookParser {

    @Override
    public Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        byte[] data;
        try (FileInputStream in = new FileInputStream(file)) {
            data = EncodingDetector.readAll(in);
        }
        String raw = EncodingDetector.decodeLenient(data, sniffCharset(data));
        String plain = strip(raw);
        List<String> paragraphs = TextUtil.splitParagraphs(plain);
        if (paragraphs.isEmpty()) throw new ParseException("这个 RTF 文件里没有可读文字");

        Book book = new Book();
        book.format = "RTF";
        book.encoding = "见原始文件";
        book.title = TextUtil.cleanTitle(TxtParser.stripExtension(file.getName()));
        Chapter c = new Chapter("正文", true);
        for (String para : paragraphs) c.addText(para);
        book.addChapter(c);
        if (images != null && mediaDir != null) {
            book.coverPath = images.generateCover(book.title, book.author, mediaDir);
        }
        return book;
    }

    /** RTF 头部通常写着 \ansicpg936 之类的代码页，据此选编码 */
    private String sniffCharset(byte[] data) {
        int n = Math.min(data.length, 512);
        String head = new String(data, 0, n, java.nio.charset.Charset.forName("ISO-8859-1"));
        int idx = head.indexOf("\\ansicpg");
        if (idx >= 0) {
            StringBuilder num = new StringBuilder();
            int i = idx + 8;
            while (i < head.length() && Character.isDigit(head.charAt(i))) {
                num.append(head.charAt(i));
                i++;
            }
            if (num.length() > 0) {
                int cp = Integer.parseInt(num.toString());
                if (cp == 936 || cp == 20936) return EncodingDetector.GB18030;
                if (cp == 950) return EncodingDetector.BIG5;
                if (cp == 65001) return EncodingDetector.UTF8;
                if (cp == 1200) return EncodingDetector.UTF16LE;
                if (cp == 1201) return EncodingDetector.UTF16BE;
                if (cp == 1252) return "windows-1252";
            }
        }
        // 没有声明时按内容猜
        String enc = EncodingDetector.detect(data);
        if (EncodingDetector.UTF8.equals(enc)) return enc;
        return EncodingDetector.GB18030;
    }

    private String strip(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        int skipGroupDepth = 0; // >0 表示正在跳过 {\* ...}
        int depth = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
                // 判断是否 {\* 目标组 或 字体/颜色表
                int j = i + 1;
                while (j < n && s.charAt(j) == ' ') j++;
                boolean ignorable = j < n && s.charAt(j) == '\\' && j + 1 < n && s.charAt(j + 1) == '*';
                String word = peekWord(s, j);
                boolean table = word.equals("fonttbl") || word.equals("colortbl") || word.equals("stylesheet")
                        || word.equals("info") || word.equals("pict") || word.equals("object")
                        || word.equals("header") || word.equals("footer");
                if ((ignorable || table) && skipGroupDepth == 0) {
                    skipGroupDepth = depth;
                }
                i++;
                continue;
            }
            if (c == '}') {
                if (skipGroupDepth > 0 && depth == skipGroupDepth) skipGroupDepth = 0;
                depth--;
                if (depth <= 0) break;
                i++;
                continue;
            }
            if (skipGroupDepth > 0) {
                i++;
                continue;
            }
            if (c == '\\') {
                if (i + 1 >= n) break;
                char nx = s.charAt(i + 1);
                if (nx == '\\' || nx == '{' || nx == '}') {
                    out.append(nx);
                    i += 2;
                    continue;
                }
                if (nx == '\'') {
                    // \'hh 单字节，连续两个组成 GBK 汉字
                    int startByte = i;
                    StringBuilder hex = new StringBuilder();
                    int k = i;
                    while (k + 3 < n && s.charAt(k) == '\\' && s.charAt(k + 1) == '\'') {
                        hex.append(s.charAt(k + 2)).append(s.charAt(k + 3));
                        k += 4;
                    }
                    try {
                        int len = hex.length() / 2;
                        byte[] b = new byte[len];
                        for (int t = 0; t < len; t++) {
                            b[t] = (byte) Integer.parseInt(hex.substring(t * 2, t * 2 + 2), 16);
                        }
                        out.append(EncodingDetector.decodeLenient(b, EncodingDetector.GB18030));
                    } catch (Exception ignored) {
                    }
                    i = k;
                    continue;
                }
                if (nx == 'u') {
                    // RTF 的 unicode 控制字 u + 十进制码位
                    int k = i + 2;
                    StringBuilder num = new StringBuilder();
                    if (k < n && s.charAt(k) == '-') {
                        num.append('-');
                        k++;
                    }
                    while (k < n && Character.isDigit(s.charAt(k))) {
                        num.append(s.charAt(k));
                        k++;
                    }
                    try {
                        int code = Integer.parseInt(num.toString());
                        if (code < 0) code += 65536;
                        out.append(new String(Character.toChars(code)));
                    } catch (Exception ignored) {
                    }
                    if (k < n && s.charAt(k) == '?') k++;
                    if (k < n && s.charAt(k) == ' ') k++;
                    i = k;
                    continue;
                }
                // 普通控制字
                int k = i + 1;
                StringBuilder word = new StringBuilder();
                while (k < n && Character.isLetter(s.charAt(k))) {
                    word.append(s.charAt(k));
                    k++;
                }
                // 数字参数
                StringBuilder arg = new StringBuilder();
                while (k < n && (Character.isDigit(s.charAt(k)) || s.charAt(k) == '-')) {
                    arg.append(s.charAt(k));
                    k++;
                }
                if (k < n && s.charAt(k) == ' ') k++;
                String w = word.toString();
                if (w.equals("par") || w.equals("line") || w.equals("sect") || w.equals("page")) {
                    out.append('\n');
                } else if (w.equals("tab")) {
                    out.append('\t');
                } else if (w.equals("emdash")) {
                    out.append('—');
                } else if (w.equals("endash")) {
                    out.append('–');
                } else if (w.equals("lquote")) {
                    out.append('‘');
                } else if (w.equals("rquote")) {
                    out.append('’');
                } else if (w.equals("ldblquote")) {
                    out.append('“');
                } else if (w.equals("rdblquote")) {
                    out.append('”');
                } else if (w.equals("bullet")) {
                    out.append('•');
                } else if (w.equals("cell") || w.equals("row")) {
                    out.append('\n');
                }
                i = k;
                continue;
            }
            if (c == '\r' || c == '\n') {
                i++;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private String peekWord(String s, int from) {
        int k = from;
        if (k >= s.length()) return "";
        if (s.charAt(k) == '\\') k++;
        StringBuilder sb = new StringBuilder();
        while (k < s.length() && Character.isLetter(s.charAt(k))) {
            sb.append(s.charAt(k));
            k++;
        }
        return sb.toString();
    }
}

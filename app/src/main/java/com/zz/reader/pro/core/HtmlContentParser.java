package com.zz.reader.pro.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 XHTML/HTML 解析成"文本段 + 插图"的块序列。
 * 比直接剥标签更准确：图片留在它原本的位置上。
 */
public class HtmlContentParser {

    /** 把 img 的 src 变成可显示的本地文件路径；返回 null 表示这张图取不到 */
    public interface ImageResolver {
        String resolve(String src, String alt);
    }

    public static void parseInto(Chapter chapter, String html, ImageResolver resolver) {
        if (html == null || html.isEmpty()) return;
        StringBuilder text = new StringBuilder();
        int n = html.length();
        int i = 0;
        boolean inScript = false;
        boolean inStyle = false;
        boolean inHead = false;

        while (i < n) {
            char c = html.charAt(i);
            if (c == '<') {
                int close = html.indexOf('>', i);
                if (close < 0) break;
                String raw = html.substring(i + 1, close).trim();
                String lower = raw.toLowerCase();
                boolean closing = lower.startsWith("/");
                String name = tagName(lower);
                i = close + 1;

                if (name.equals("script")) {
                    inScript = !closing;
                    continue;
                }
                if (name.equals("style")) {
                    inStyle = !closing;
                    continue;
                }
                if (name.equals("head")) {
                    inHead = !closing;
                    continue;
                }
                if (inScript || inStyle || inHead) continue;

                if (name.equals("img")) {
                    flush(chapter, text);
                    String src = attrOf(raw, "src");
                    String alt = attrOf(raw, "alt");
                    if (src == null || src.isEmpty()) {
                        String rec = attrOf(raw, "recindex");
                        if (rec != null) src = "recindex:" + rec;
                    }
                    if (src == null || src.isEmpty()) src = attrOf(raw, "xlink:href");
                    if (resolver != null && src != null && !src.isEmpty()) {
                        String path = resolver.resolve(src, alt);
                        if (path != null) {
                            chapter.addImage(path, alt);
                        } else {
                            // 图片取不到时留一句说明，读屏用户不会觉得凭空少了一段
                            chapter.addText(alt != null && !alt.isEmpty() ? "插图：" + alt : "插图（未能读取）");
                        }
                    }
                    continue;
                }
                if (isBlockTag(name)) {
                    flush(chapter, text);
                }
                continue;
            }
            if (!inScript && !inStyle && !inHead) {
                if (c == '&') {
                    int semi = html.indexOf(';', i);
                    if (semi > i && semi - i <= 10) {
                        String decoded = TextUtil.decodeEntity(html.substring(i, semi + 1));
                        if (decoded != null) {
                            text.append(decoded);
                            i = semi + 1;
                            continue;
                        }
                    }
                }
                text.append(c);
            }
            i++;
        }
        flush(chapter, text);
    }

    private static void flush(Chapter chapter, StringBuilder buf) {
        if (buf.length() == 0) return;
        String s = buf.toString().replace('\u3000', ' ');
        buf.setLength(0);
        for (String para : TextUtil.splitParagraphs(s)) {
            chapter.addText(para);
        }
    }

    private static boolean isBlockTag(String name) {
        return name.equals("p") || name.equals("div") || name.equals("br") || name.equals("li")
                || name.equals("tr") || name.equals("section") || name.equals("article")
                || name.equals("header") || name.equals("footer") || name.equals("blockquote")
                || name.equals("h1") || name.equals("h2") || name.equals("h3") || name.equals("h4")
                || name.equals("h5") || name.equals("h6") || name.equals("pre") || name.equals("table")
                || name.equals("figure") || name.equals("caption") || name.equals("dd") || name.equals("dt")
                || name.equals("hr") || name.equals("picture") || name.equals("ul") || name.equals("ol");
    }

    private static String tagName(String lowerTag) {
        int i = 0;
        if (lowerTag.startsWith("/")) i = 1;
        StringBuilder sb = new StringBuilder();
        while (i < lowerTag.length()) {
            char c = lowerTag.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else break;
            i++;
        }
        return sb.toString();
    }

    public static String attrOf(String tag, String name) {
        String lower = tag.toLowerCase();
        int idx = lower.indexOf(name.toLowerCase() + "=");
        while (idx >= 0) {
            int before = idx - 1;
            if (before >= 0) {
                char prev = lower.charAt(before);
                if (Character.isLetterOrDigit(prev) || prev == '-' || prev == ':') {
                    idx = lower.indexOf(name.toLowerCase() + "=", idx + 1);
                    continue;
                }
            }
            int p = idx + name.length() + 1;
            if (p >= tag.length()) return null;
            char q = tag.charAt(p);
            if (q == '"' || q == '\'') {
                int end = tag.indexOf(q, p + 1);
                if (end > p) return tag.substring(p + 1, end);
                return null;
            }
            int end = p;
            while (end < tag.length() && !Character.isWhitespace(tag.charAt(end))
                    && tag.charAt(end) != '>') {
                end++;
            }
            return tag.substring(p, end);
        }
        return null;
    }
}

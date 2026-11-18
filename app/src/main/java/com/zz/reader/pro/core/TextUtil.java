package com.zz.reader.pro.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextUtil {

    /**
     * 章节标题的多套规则，按顺序试，任一命中就当成一章的开头。
     * 小说 TXT 的写法太杂，单条正则覆盖不住：带序号的中文/阿拉伯数字、卷/章/回/节/部/篇/话，
     * 序号在后（卷一），各种前后缀（序章、楔子、番外一），括号包住的，英文 Chapter/Part/Section。
     */
    private static final Pattern[] CHAPTER_RULES = {
            // 第X章 / 第12回 / 第００1节，允许前面有括号和“【”，后面跟章名
            Pattern.compile("^【?\\[?\\(?第[〇零一二三四五六七八九十百千万两0-9０-９]{1,10}"
                    + "[章节回卷部篇集话幕]\\)?\\]?】?(\\s*[:：、．.]?\\s*[^。，！？；\\n]{0,32})?$"),
            // 卷一 风起、卷二
            Pattern.compile("^[卷章回节部篇]\\s?[〇零一二三四五六七八九十百千万两0-9０-９]{1,8}"
                    + "(\\s*[:：、．.]?\\s*[^。，！？；\\n]{0,32})?$"),
            // 序章、序言、序、楔子、引子、前言、后记、尾声、终章、大结局、番外一、外传、附录
            Pattern.compile("^(序章|序言|序|楔子|引子|前言|后记|尾声|终章|大结局|番外[〇零一二三四五六七八九十0-9]*"
                    + "|外传|附录)(\\s*[:：、．.]?\\s*[^。，！？；\\n]{0,32})?$"),
            // 整行被括号包住的标题：【第一章 开端】、（二）楔子、【尾声】
            Pattern.compile("^[【\\(（\\[][^。，！？；\\n]{1,32}[】\\)）\\]]$"),
            // Chapter 1 / CHAPTER 12 A new start
            Pattern.compile("^[Cc][Hh]apter\\s*[0-9]{1,4}(\\s*[:：、．.]?\\s*[^。，！？；\\n]{0,40})?$"),
            Pattern.compile("^[Pp]art\\s*([0-9]{1,4}|[IVX]{1,6})(\\s*[:：、．.]?\\s*[^。，！？；\\n]{0,40})?$"),
            Pattern.compile("^[Ss]ection\\s*[0-9]{1,4}(\\s*[:：、．.]?\\s*[^。，！？；\\n]{0,40})?$"),
    };

    private TextUtil() {
    }

    /** 把任意空白行结构化为段落列表，保证每段不为空串 */
    public static List<String> splitParagraphs(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace("\uFEFF", "").replace("\u200B", "");
        String[] lines = text.split("\n", -1);

        // 判断这本书是"一行一段"还是"软换行(段落在句子中间断行)"
        int nonEmpty = 0;
        int endsWithPunct = 0;
        int totalLen = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            nonEmpty++;
            totalLen += t.length();
            if (endsSentence(t)) endsWithPunct++;
        }
        // 行平均超过 50 字且多数行不以句末标点结尾 → 视为软换行的长文
        boolean softWrap = nonEmpty > 3 && (totalLen / nonEmpty) >= 50
                && endsWithPunct * 2 < nonEmpty;

        StringBuilder buf = new StringBuilder();
        for (String line : lines) {
            String t = line.replace('\u3000', ' ');
            t = t.replaceAll("[ \\t]+$", "").trim();
            if (t.isEmpty()) {
                flush(buf, out);
                continue;
            }
            if (softWrap) {
                if (buf.length() > 0 && !isAsciiWord(buf.charAt(buf.length() - 1)
                        + "") && !isAsciiWord(t.charAt(0) + "")) {
                    // 中文软换行直接拼接
                } else if (buf.length() > 0) {
                    buf.append(' ');
                }
                buf.append(t);
                if (endsSentence(t) || looksLikeChapterTitle(t)) {
                    flush(buf, out);
                }
                if (buf.length() > 4000) flush(buf, out);
            } else {
                flush(buf, out);
                if (t.length() > 900) {
                    out.addAll(chunkLongText(t, 700));
                } else {
                    out.add(t);
                }
            }
        }
        flush(buf, out);
        return out;
    }

    private static boolean isAsciiWord(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        return c < 128 && Character.isLetterOrDigit(c);
    }

    /** 行首是否以句末标点结束 */
    private static boolean endsSentence(String line) {
        if (line.isEmpty()) return false;
        char last = line.charAt(line.length() - 1);
        return last == '。' || last == '！' || last == '？' || last == '；' || last == '…'
                || last == '"' || last == '”' || last == '」' || last == '』'
                || last == '.' || last == '!' || last == '?';
    }

    private static void flush(StringBuilder buf, List<String> out) {
        if (buf.length() > 0) {
            String s = buf.toString().trim();
            if (!s.isEmpty()) out.add(s);
            buf.setLength(0);
        }
    }

    /** 单段文本过长时（例如无换行的整本 txt）切成可视段落 */
    public static List<String> chunkLongText(String s, int size) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        int n = s.length();
        int start = 0;
        while (start < n) {
            int end = Math.min(n, start + size);
            if (end < n) {
                int cut = findBreak(s, start, end);
                if (cut > start) end = cut;
            }
            String piece = s.substring(start, end).trim();
            if (!piece.isEmpty()) out.add(piece);
            start = end;
        }
        return out;
    }

    private static int findBreak(String s, int start, int end) {
        for (int i = end; i > start + (end - start) / 2; i--) {
            char c = s.charAt(i - 1);
            if (c == '。' || c == '！' || c == '？' || c == '；') return i;
        }
        return end;
    }

    /** 句子切分：给逐句朗读、逐句导航用 */
    public static List<String> splitSentences(String paragraph) {
        List<String> out = new ArrayList<>();
        if (paragraph == null || paragraph.isEmpty()) return out;
        int start = 0;
        int n = paragraph.length();
        for (int i = 0; i < n; i++) {
            char c = paragraph.charAt(i);
            boolean end = false;
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '．' || c == '…') {
                end = true;
                // 连续的省略号 / 叹问号合并成一句
                while (i + 1 < n) {
                    char nx = paragraph.charAt(i + 1);
                    if (nx == '…' || nx == '！' || nx == '？' || nx == '。' || nx == '"' || nx == '”'
                            || nx == '」' || nx == '』') {
                        i++;
                    } else break;
                }
            } else if (c == '\n') {
                end = true;
            } else if (c == '.' || c == '!' || c == '?') {
                // 英文句末，要求后面是空格或结尾，避免把 3.14 切断
                if (i + 1 >= n) end = true;
                else {
                    char nx = paragraph.charAt(i + 1);
                    if (nx == ' ' || nx == '\n' || nx == '"') end = true;
                }
            }
            if (end) {
                String piece = paragraph.substring(start, i + 1).trim();
                if (!piece.isEmpty()) out.add(piece);
                start = i + 1;
            }
        }
        if (start < n) {
            String rest = paragraph.substring(start).trim();
            if (!rest.isEmpty()) out.add(rest);
        }
        return out;
    }

    private static final Pattern HEADING_TAG =
            Pattern.compile("<h[12][^>]*>(.*?)</h[12]>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * 把一段 HTML 按 h1/h2 切成若干章，返回 [标题, 片段]。
     * EPUB 有的书整本放在一个 xhtml 里，光按文件分章只能得到一章，这里按标题再切一次。
     * 没有标题时返回一段（标题为空，由调用方补）。
     */
    public static List<String[]> splitHtmlChapters(String html) {
        List<String[]> res = new ArrayList<>();
        if (html == null || html.isEmpty()) return res;
        List<int[]> spans = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        Matcher m = HEADING_TAG.matcher(html);
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
            String t = htmlToPlain(m.group(1)).trim();
            if (t.length() > 60) t = t.substring(0, 60);
            titles.add(t);
        }
        if (spans.isEmpty()) {
            res.add(new String[]{"", html});
            return res;
        }
        if (spans.get(0)[0] > 0) {
            String pre = html.substring(0, spans.get(0)[0]);
            if (htmlToPlain(pre).trim().length() > 20) {
                res.add(new String[]{"开始", pre});
            }
        }
        for (int i = 0; i < spans.size(); i++) {
            int from = spans.get(i)[1];
            int to = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : html.length();
            if (to < from) to = from;
            res.add(new String[]{titles.get(i), html.substring(from, to)});
        }
        return res;
    }

    /** HTML 片段转可读纯文本，保留段落结构 */
    public static String htmlToPlain(String html) {
        if (html == null || html.isEmpty()) return "";
        StringBuilder out = new StringBuilder(html.length());
        int n = html.length();
        int i = 0;
        boolean inTag = false;
        boolean inScript = false;
        boolean inStyle = false;
        boolean inHead = false;
        String tagName = "";
        while (i < n) {
            char c = html.charAt(i);
            if (c == '<') {
                int close = html.indexOf('>', i);
                if (close < 0) break;
                String raw = html.substring(i + 1, close).trim();
                String lower = raw.toLowerCase();
                String name = tagNameOf(lower);
                if (name.equals("script")) {
                    if (!lower.startsWith("/")) inScript = true;
                    else inScript = false;
                    i = close + 1;
                    continue;
                }
                if (name.equals("style")) {
                    if (!lower.startsWith("/")) inStyle = true;
                    else inStyle = false;
                    i = close + 1;
                    continue;
                }
                if (name.equals("head")) {
                    if (!lower.startsWith("/")) inHead = true;
                    else inHead = false;
                    i = close + 1;
                    continue;
                }
                if (!inScript && !inStyle && !inHead) {
                    if (lower.startsWith("/")) {
                        if (isBlockStart(name)) appendNewline(out, 1);
                    } else {
                        if (isBlockStart(name)) appendNewline(out, 1);
                        if (name.equals("br")) appendNewline(out, 1);
                        if (name.equals("img")) {
                            appendAltText(out, lower);
                        }
                    }
                }
                i = close + 1;
                continue;
            }
            if (!inScript && !inStyle && !inHead) {
                if (c == '&') {
                    int semi = html.indexOf(';', i);
                    if (semi > i && semi - i <= 10) {
                        String ent = html.substring(i, semi + 1);
                        String decoded = decodeEntity(ent);
                        if (decoded != null) {
                            out.append(decoded);
                            i = semi + 1;
                            continue;
                        }
                    }
                }
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    private static void appendAltText(StringBuilder out, String lowerTag) {
        int altIdx = lowerTag.indexOf("alt=");
        if (altIdx < 0) return;
        char quote = lowerTag.charAt(altIdx + 4);
        int start = altIdx + 5;
        int end = lowerTag.indexOf(quote, start);
        if (end < 0) return;
        String alt = lowerTag.substring(start, end);
        if (!alt.trim().isEmpty()) out.append('【').append(alt.trim()).append('】');
    }

    private static String tagNameOf(String lowerTag) {
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

    private static boolean isBlockStart(String name) {
        return name.equals("p") || name.equals("div") || name.equals("br") || name.equals("li")
                || name.equals("tr") || name.equals("section") || name.equals("article")
                || name.equals("header") || name.equals("footer") || name.equals("blockquote")
                || name.equals("h1") || name.equals("h2") || name.equals("h3") || name.equals("h4")
                || name.equals("h5") || name.equals("h6") || name.equals("pre") || name.equals("table")
                || name.equals("figure") || name.equals("caption") || name.equals("dd") || name.equals("dt");
    }

    private static void appendNewline(StringBuilder out, int count) {
        int len = out.length();
        if (len == 0) return;
        char last = out.charAt(len - 1);
        if (last == '\n') return;
        out.append('\n');
    }

    public static String decodeEntity(String ent) {
        if (ent.equals("&amp;")) return "&";
        if (ent.equals("&lt;")) return "<";
        if (ent.equals("&gt;")) return ">";
        if (ent.equals("&quot;")) return "\"";
        if (ent.equals("&#39;") || ent.equals("&apos;")) return "'";
        if (ent.equals("&nbsp;")) return " ";
        if (ent.equals("&mdash;")) return "—";
        if (ent.equals("&ndash;")) return "–";
        if (ent.equals("&hellip;")) return "…";
        if (ent.equals("&ldquo;")) return "“";
        if (ent.equals("&rdquo;")) return "”";
        if (ent.equals("&lsquo;")) return "‘";
        if (ent.equals("&rsquo;")) return "’";
        if (ent.startsWith("&#")) {
            try {
                String body = ent.substring(2, ent.length() - 1);
                int code;
                if (body.startsWith("x") || body.startsWith("X")) {
                    code = Integer.parseInt(body.substring(1), 16);
                } else {
                    code = Integer.parseInt(body);
                }
                return new String(Character.toChars(code));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** 粗略估算一行能放多少字（中文按 1 字宽，西文按 0.55 字宽） */
    public static int estimateCharsPerLine(int widthPx, float textSizePx) {
        if (textSizePx <= 0) return 20;
        return Math.max(4, (int) Math.floor(widthPx / textSizePx));
    }

    public static boolean looksLikeChapterTitle(String line) {
        return isChapterTitle(line);
    }

    /** 多规则判断一行是不是章节标题 */
    public static boolean isChapterTitle(String line) {
        if (line == null) return false;
        String t = line.trim();
        if (t.length() == 0 || t.length() > 44) return false;
        // 正文句子不能被当成标题：出现句号、或长度太长、或以逗号结尾的都排除
        if (t.indexOf('。') >= 0) return false;
        if (t.endsWith("，") || t.endsWith("、")) return false;
        for (Pattern rule : CHAPTER_RULES) {
            if (rule.matcher(t).matches()) return true;
        }
        return false;
    }

    public static String cleanTitle(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n\\t]", " ").replaceAll("  +", " ").trim();
    }
}

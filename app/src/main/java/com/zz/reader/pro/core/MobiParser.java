package com.zz.reader.pro.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Kindle 电子书解析：MOBI / PRC / AZW / AZW3(KF8)。
 *
 * 支持：未加密、PalmDOC 压缩或未压缩的 MOBI 族文件；封面与插图按记录顺序提取。
 * 明确不支持：带 DRM 加密的书（encryption != 0）；Huffman/CDIC 压缩（少数旧书）。
 */
public class MobiParser implements BookParser {

    private static final int COMPRESSION_NONE = 1;
    private static final int COMPRESSION_PALMDOC = 2;
    private static final int COMPRESSION_HUFF = 17480;

    private byte[] data;
    private int recordTable;
    private int numRecords;
    private int firstImageIndex;
    private int firstNonBook;
    private ImageStore images;
    private File mediaDir;
    private final List<String> imagePaths = new ArrayList<>(); // 按出现顺序，第 1 张 = index 0
    private boolean imagesScanned = false;

    @Override
    public Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        byte[] raw;
        try (FileInputStream in = new FileInputStream(file)) {
            raw = EncodingDetector.readAll(in);
        }
        this.data = raw;
        this.images = images;
        this.mediaDir = mediaDir;
        this.imagePaths.clear();
        this.imagesScanned = false;
        return parseInternal(file);
    }

    private Book parseInternal(File file) throws ParseException {
        if (data.length < 100) throw new ParseException("文件太小，不像是 Kindle 电子书");

        String type = new String(data, 60, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        String creator = new String(data, 64, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!(type.equals("BOOK") || type.equals("TEXt"))
                && !creator.equals("MOBI") && !creator.equals("Mobi")) {
            throw new ParseException("这个文件不是 Kindle 的 MOBI/AZW3 格式，无法识别");
        }

        numRecords = u16(data, 76);
        if (numRecords <= 1) throw new ParseException("文件结构不完整，记录数为零，可能下载不完整");
        recordTable = 78;
        long rec0Start = u32(data, recordTable);
        long rec1Start = u32(data, recordTable + 8);
        if (rec0Start <= 0 || rec1Start <= rec0Start || rec1Start > data.length) {
            throw new ParseException("文件结构异常，无法定位书籍头信息");
        }
        int rec0Len = (int) (rec1Start - rec0Start);
        int rec0 = (int) rec0Start;

        int compression = u16(data, rec0);
        int textLength = (int) u32(data, rec0 + 4);
        int textRecordCount = u16(data, rec0 + 8);
        int encryption = u16(data, rec0 + 12);

        if (encryption != 0) {
            throw new ParseException("这本书带有 DRM 版权加密，阅读器无法直接读取。请在你有权处理的前提下，"
                    + "先在电脑上用 Calibre 转成 EPUB 或未加密的 MOBI，再导入本阅读器。");
        }
        if (compression == COMPRESSION_HUFF) {
            throw new ParseException("这本书使用了 Amazon 的 Huffman 压缩，当前版本还不能直接读。"
                    + "请先用 Calibre 转成 EPUB 格式，再导入本阅读器，转换后文字完全一样。");
        }
        if (compression != COMPRESSION_NONE && compression != COMPRESSION_PALMDOC) {
            throw new ParseException("这本书使用了不常见的压缩方式，暂时读不了，建议先用 Calibre 转成 EPUB。");
        }

        String mobiId = "";
        int mobiHeader = rec0 + 16;
        if (mobiHeader + 8 <= rec0 + rec0Len) {
            mobiId = new String(data, mobiHeader, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        if (!mobiId.equals("MOBI")) {
            throw new ParseException("找不到 Kindle 书籍头，可能文件损坏");
        }
        int headerLen = (int) u32(data, mobiHeader + 4);
        int textEncoding = (int) u32(data, mobiHeader + 12);
        firstNonBook = (int) u32(data, mobiHeader + 64);
        int fullNameOffset = (int) u32(data, mobiHeader + 68);
        int fullNameLength = (int) u32(data, mobiHeader + 72);
        firstImageIndex = (int) u32(data, mobiHeader + 92);
        if (firstImageIndex <= 0) firstImageIndex = firstNonBook;

        int textRecords = firstNonBook > 0 ? firstNonBook - 1 : textRecordCount;
        if (textRecords <= 0) textRecords = numRecords - 1;
        if (textRecords <= 0) throw new ParseException("这本书没有正文记录");

        GrowBuffer text = new GrowBuffer(Math.max(textLength, 1024));
        for (int i = 1; i <= textRecords; i++) {
            int entryPos = recordTable + i * 8;
            if (entryPos + 8 > data.length) break;
            long start = u32(data, entryPos);
            long end = u32(data, entryPos + 4);
            if (end <= start || end > data.length) end = u32(data, entryPos + 8);
            if (start <= 0 || end <= start || end > data.length) continue;
            if (compression == COMPRESSION_PALMDOC) {
                palmDocDecompress(data, (int) start, (int) end, text);
            } else {
                text.write(data, (int) start, (int) (end - start));
            }
            if (text.size() >= textLength) break;
        }
        if (text.size() == 0) throw new ParseException("这本书的正文字节是空的，文件可能损坏");

        byte[] raw = text.toByteArray(textLength);
        String charset = textEncoding == 65001 ? "UTF-8" : "windows-1252";
        String html;
        try {
            html = new String(raw, charset);
        } catch (Exception e) {
            html = EncodingDetector.decodeLenient(raw, EncodingDetector.UTF8);
            charset = "UTF-8";
        }
        html = stripPalmDocMarks(html);

        Book book = new Book();
        book.format = "MOBI";
        book.encoding = charset.equals("UTF-8") ? "UTF-8" : "Windows-1252";
        book.title = TextUtil.cleanTitle(extractFullName(rec0, headerLen, fullNameOffset, fullNameLength));
        if (book.title.isEmpty()) book.title = TextUtil.cleanTitle(TxtParser.stripExtension(file.getName()));
        book.author = "";

        ensureImagesScanned();
        int coverIndex = readCoverIndex(mobiHeader, headerLen);

        // 先按这本书自带的目录分章；拿不到目录（或对不上正文）再退回按"第X章"切
        List<String> tocTitles = readTocTitles(mobiHeader, headerLen, compression, charset);
        if (!buildChaptersByToc(book, html, tocTitles)) {
            final Chapter chapter = new Chapter("正文", true);
            HtmlContentParser.parseInto(chapter, html, (src, alt) -> resolveImage(src, alt, firstImageIndex));
            splitIntoChapters(book, chapter);
        }

        if (book.coverPath == null || book.coverPath.isEmpty()) {
            if (coverIndex >= 0 && coverIndex < imagePaths.size()) {
                book.coverPath = imagePaths.get(coverIndex);
            } else if (!imagePaths.isEmpty()) {
                book.coverPath = imagePaths.get(0);
            }
        }
        if (book.chapters.isEmpty()) throw new ParseException("这本书没有提取到可读文字");
        return book;
    }


    // ---------------------------------------------------------------- 自带目录（INDX / NCX）

    /** 读出这本书自带的章节目录标题；读不到就返回空表 */
    private List<String> readTocTitles(int mobiHeader, int headerLen, int compression, String charset) {
        List<String> out = new ArrayList<>();
        try {
            int exthFlags = (int) u32(data, mobiHeader + 128);
            if ((exthFlags & 0x40) == 0) return out;
            int p = mobiHeader + headerLen;
            if (p + 12 > data.length) return out;
            if (!"EXTH".equals(new String(data, p, 4, java.nio.charset.StandardCharsets.ISO_8859_1))) return out;
            int count = (int) u32(data, p + 8);
            if (count <= 0 || count > 10000) return out;
            int q = p + 12;
            int tocIndex = -1;
            int ncxIndex = -1;
            for (int i = 0; i < count; i++) {
                if (q + 8 > data.length) break;
                int type = (int) u32(data, q);
                int len = (int) u32(data, q + 4);
                if (len < 8) break;
                int valPos = q + 8;
                int valLen = Math.min(4, len - 8);
                int val = valPos + 4 <= data.length ? (int) u32(data, valPos) : -1;
                if (type == 116 && valLen > 0) tocIndex = val;
                else if (type == 121 && valLen > 0) ncxIndex = val;
                q += len;
            }
            if (tocIndex <= 0) tocIndex = ncxIndex;
            if (tocIndex <= 0 || tocIndex >= numRecords) return out;

            byte[] main = loadRecord(tocIndex, compression);
            if (main == null || main.length < 56) return out;
            if (!"INDX".equals(new String(main, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1))) return out;
            int indexCount = (int) u32(main, 24);
            if (indexCount <= 0 || indexCount > 5000) return out;
            if (tocIndex + indexCount >= numRecords) indexCount = numRecords - tocIndex - 1;
            if (indexCount <= 0) return out;

            for (int i = 1; i <= indexCount; i++) {
                byte[] rec = loadRecord(tocIndex + i, compression);
                if (rec == null || rec.length < 32) continue;
                int idxtPos = (int) u32(rec, 20);
                int entryCount = (int) u32(rec, 24);
                if (idxtPos <= 0 || idxtPos + 4 > rec.length || entryCount <= 0 || entryCount > 5000) continue;
                int[] pos = new int[entryCount + 1];
                for (int j = 0; j < entryCount; j++) {
                    int o = idxtPos + 4 + 2 * j;
                    pos[j] = (o + 2 <= rec.length) ? u16(rec, o) : -1;
                }
                pos[entryCount] = idxtPos;
                for (int j = 0; j < entryCount; j++) {
                    int st = pos[j];
                    if (st <= 0 || st >= rec.length) continue;
                    int tl = rec[st] & 0xFF;
                    if (tl <= 0 || st + 1 + tl > rec.length) continue;
                    String t;
                    try {
                        t = new String(rec, st + 1, tl, charset);
                    } catch (Exception e) {
                        continue;
                    }
                    t = t.replace("\u0000", "").trim();
                    if (t.isEmpty() || t.length() > 120) continue;
                    if (!out.isEmpty() && out.get(out.size() - 1).equals(t)) continue;
                    out.add(t);
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    /** 按目录标题在正文里定位，切成章；定位不到一半就放弃，退回老办法 */
    private boolean buildChaptersByToc(Book book, String html, List<String> titles) {
        if (titles == null || titles.size() < 2) return false;
        List<Integer> cuts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        int from = 0;
        for (String t : titles) {
            int p1 = html.indexOf(t, from);
            if (p1 < 0) continue;
            // 目录页里也会出现同样的标题，优先取正文里那一次
            int p2 = html.indexOf(t, p1 + t.length());
            int cut = p2 > p1 ? p2 : p1;
            if (cut < from) continue;
            cuts.add(cut);
            names.add(t);
            from = cut + t.length();
        }
        if (cuts.size() < 2 || cuts.size() < titles.size() / 2) return false;

        if (cuts.get(0) > 0) {
            Chapter pre = new Chapter("开始", true);
            HtmlContentParser.parseInto(pre, html.substring(0, cuts.get(0)),
                    (src, alt) -> resolveImage(src, alt, firstImageIndex));
            if (pre.hasText() || !pre.items.isEmpty()) book.addChapter(pre);
        }
        for (int i = 0; i < cuts.size(); i++) {
            int s = cuts.get(i);
            int e = (i + 1 < cuts.size()) ? cuts.get(i + 1) : html.length();
            Chapter c = new Chapter(names.get(i));
            HtmlContentParser.parseInto(c, html.substring(s, Math.max(s, e)),
                    (src, alt) -> resolveImage(src, alt, firstImageIndex));
            if (c.hasText()) book.addChapter(c);
        }
        return !book.chapters.isEmpty();
    }

    /** 取某一记录的原始字节（PalmDOC 压缩的先解开） */
    private byte[] loadRecord(int index, int compression) {
        int entryPos = recordTable + index * 8;
        if (entryPos + 8 > data.length) return null;
        long start = u32(data, entryPos);
        long end = u32(data, entryPos + 4);
        if (end <= start || end > data.length) end = u32(data, entryPos + 8);
        if (start <= 0 || end <= start || end > data.length) return null;
        int len = (int) (end - start);
        if (compression == COMPRESSION_PALMDOC) {
            GrowBuffer gb = new GrowBuffer(Math.max(len * 2, 256));
            palmDocDecompress(data, (int) start, (int) end, gb);
            return gb.toByteArray(gb.size());
        }
        byte[] b = new byte[len];
        System.arraycopy(data, (int) start, b, 0, len);
        return b;
    }

    private void splitIntoChapters(Book book, Chapter body) {
        Chapter current = null;
        List<Chapter.Item> pre = new ArrayList<>();
        for (Chapter.Item item : body.items) {
            if (item.isImage) {
                if (current == null) pre.add(item);
                else current.items.add(item);
                continue;
            }
            String p = item.text;
            if (TextUtil.isChapterTitle(p)) {
                if (current != null) book.addChapter(current);
                if (current == null && !pre.isEmpty()) {
                    Chapter head = new Chapter("开始", true);
                    head.items.addAll(pre);
                    book.addChapter(head);
                    pre.clear();
                }
                current = new Chapter(p);
            } else if (current == null) {
                pre.add(item);
            } else {
                current.items.add(item);
            }
        }
        if (current != null) book.addChapter(current);
        if (book.chapters.isEmpty() && !pre.isEmpty()) {
            Chapter c = new Chapter("正文", true);
            c.items.addAll(pre);
            book.addChapter(c);
        }
    }

    // ---------------------------------------------------------------- 图片

    private void ensureImagesScanned() {
        if (imagesScanned) return;
        imagesScanned = true;
        if (images == null || mediaDir == null) return;
        int start = firstImageIndex > 0 ? firstImageIndex : firstNonBook;
        if (start <= 0) start = 1;
        for (int i = start; i < numRecords; i++) {
            int entryPos = recordTable + i * 8;
            if (entryPos + 8 > data.length) break;
            long s = u32(data, entryPos);
            long e = u32(data, entryPos + 4);
            if (e <= s || e > data.length) e = u32(data, entryPos + 8);
            if (s <= 0 || e <= s || e > data.length) continue;
            byte[] rec = new byte[(int) (e - s)];
            System.arraycopy(data, (int) s, rec, 0, rec.length);
            String ext = ImageStore.sniffExt(rec);
            if (ext == null) continue;
            String path = images.save(rec, mediaDir, "mobi:rec" + i);
            if (path != null) imagePaths.add(path);
        }
    }

    private String resolveImage(String src, String alt, int firstImageIndex) {
        if (src == null) return null;
        String s = src.trim();
        int index = -1;
        if (s.startsWith("kindle:embed:")) {
            String v = s.substring("kindle:embed:".length());
            int q = v.indexOf('?');
            if (q >= 0) v = v.substring(0, q);
            try {
                index = base32Decode(v);
            } catch (Exception ignored) {
                index = -1;
            }
        } else if (s.startsWith("recindex:")) {
            String v = s.substring("recindex:".length());
            try {
                index = Integer.parseInt(v) - 1;
            } catch (Exception ignored) {
                index = -1;
            }
        } else if (s.matches("[0-9]{1,6}")) {
            try {
                index = Integer.parseInt(s) - 1;
            } catch (Exception ignored) {
                index = -1;
            }
        }
        ensureImagesScanned();
        if (index >= 0 && index < imagePaths.size()) return imagePaths.get(index);
        return null;
    }

    private int base32Decode(String s) {
        int result = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toUpperCase(s.charAt(i));
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= '2' && c <= '7') v = c - '2' + 26;
            else continue;
            result = result * 32 + v;
        }
        return result;
    }

    private int readCoverIndex(int mobiHeader, int headerLen) {
        try {
            int exth = -1;
            int base = mobiHeader + headerLen;
            for (int off = 0; off < 16; off++) {
                int pos = base + off;
                if (pos + 4 <= data.length
                        && data[pos] == 'E' && data[pos + 1] == 'X'
                        && data[pos + 2] == 'T' && data[pos + 3] == 'H') {
                    exth = pos;
                    break;
                }
            }
            if (exth < 0) return -1;
            int count = (int) u32(data, exth + 8);
            int p = exth + 12;
            for (int i = 0; i < count; i++) {
                if (p + 8 > data.length) break;
                int type = (int) u32(data, p);
                int len = (int) u32(data, p + 4);
                if (len < 8 || p + len > data.length) break;
                if (type == 201 || type == 202) {
                    return (int) u32(data, p + 8);
                }
                p += len;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private String extractFullName(int rec0, int headerLen, int offset, int length) {
        if (offset <= 0 || length <= 0) return "";
        int[] candidates = {rec0 + offset, rec0 + 16 + offset};
        for (int abs : candidates) {
            try {
                if (abs + length > data.length) continue;
                byte[] bytes = new byte[length];
                System.arraycopy(data, abs, bytes, 0, length);
                String name = new String(bytes, "UTF-8").trim();
                if (!name.isEmpty() && EncodingDetector.garbageCount(name) == 0) return name;
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private String stripPalmDocMarks(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u0001' || c == '\u0002' || c == '\u0000') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private void palmDocDecompress(byte[] src, int start, int end, GrowBuffer out) {
        int i = start;
        while (i < end) {
            int b = src[i] & 0xFF;
            if (b == 0) {
                out.writeByte(0);
                i++;
            } else if (b <= 0x08) {
                int count = b;
                i++;
                for (int k = 0; k < count && i < end; k++) {
                    out.writeByte(src[i] & 0xFF);
                    i++;
                }
            } else if (b <= 0x7F) {
                out.writeByte(b);
                i++;
            } else if (b <= 0xBF) {
                if (i + 1 >= end) break;
                int b2 = src[i + 1] & 0xFF;
                int distance = ((b - 0x80) << 5) | ((b2 >> 3) & 0x1F);
                int length = (b2 & 0x07) + 3;
                if (distance == 0) distance = 1;
                out.copyFromHistory(distance, length);
                i += 2;
            } else {
                out.writeByte(' ');
                out.writeByte(b ^ 0x80);
                i++;
            }
        }
    }

    private static int u16(byte[] d, int p) {
        if (p + 1 >= d.length) return 0;
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    private static long u32(byte[] d, int p) {
        if (p + 3 >= d.length) return 0;
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    static class GrowBuffer {
        private byte[] buf;
        private int size;

        GrowBuffer(int cap) {
            buf = new byte[Math.max(16, cap)];
        }

        void ensure(int extra) {
            if (size + extra <= buf.length) return;
            int newCap = buf.length * 2;
            while (newCap < size + extra) newCap *= 2;
            byte[] nb = new byte[newCap];
            System.arraycopy(buf, 0, nb, 0, size);
            buf = nb;
        }

        void writeByte(int b) {
            ensure(1);
            buf[size++] = (byte) b;
        }

        void write(byte[] src, int off, int len) {
            ensure(len);
            System.arraycopy(src, off, buf, size, len);
            size += len;
        }

        void copyFromHistory(int distance, int length) {
            ensure(length);
            int srcPos = size - distance;
            for (int k = 0; k < length; k++) {
                if (srcPos < 0) {
                    buf[size++] = 0;
                } else {
                    buf[size] = buf[srcPos];
                    size++;
                    srcPos++;
                }
            }
        }

        int size() {
            return size;
        }

        byte[] toByteArray(int limit) {
            int n = limit > 0 && limit < size ? limit : size;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        }
    }
}

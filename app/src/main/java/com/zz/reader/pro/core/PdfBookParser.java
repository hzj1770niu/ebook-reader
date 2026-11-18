package com.zz.reader.pro.core;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import com.zz.reader.pro.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * PDF：立刻交书，识别绝不占着打开流程。
 * 文字层好用的页直接用文字层，一页一章；扫描页、文字层坏掉的页先放一句占位文字，
 * 由阅读界面在书打开之后一页一页在后台识别，识别完就地替换进正文。
 * 每页按横坐标判断栏，两栏三栏按"左栏读完再读右栏"输出。
 */
public class PdfBookParser implements BookParser {

    /** 一页文字少于这个字数，就当没有文字层 */
    private static final int SCAN_PAGE_CHARS = 25;
    /** 文字层里认不出的字符占到这么多，这一页就改用识别结果 */
    private static final float BROKEN_RATIO = 0.08f;

    interface Progress {
        void onProgress(String message);
    }

    private static Progress progress;

    public static void setProgress(Progress p) {
        progress = p;
    }

    /** 一行文字：属于第几页、起点坐标、宽度、内容 */
    static class Line {
        int page;
        float x;
        float y;
        float width;
        String text;
    }

    /** 收集每页每一行的位置，不做默认输出 */
    static class LineStripper extends PDFTextStripper {
        final List<Line> lines = new ArrayList<>();
        private final java.util.HashMap<Object, Integer> pageIndex = new java.util.HashMap<>();

        LineStripper(PDDocument doc) throws IOException {
            super();
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage p = doc.getPage(i);
                if (p != null) pageIndex.put(p.getCOSObject(), i + 1);
            }
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (text == null || text.trim().isEmpty()) return;
            float x = 0, y = 0, w = 0;
            if (positions != null && !positions.isEmpty()) {
                TextPosition f = positions.get(0);
                TextPosition l = positions.get(positions.size() - 1);
                x = f.getXDirAdj();
                y = f.getYDirAdj();
                w = l.getXDirAdj() + l.getWidthDirAdj() - x;
            }
            Line ln = new Line();
            Integer pg = null;
            try {
                PDPage cur = getCurrentPage();
                if (cur != null) pg = pageIndex.get(cur.getCOSObject());
            } catch (Throwable ignore) {
            }
            ln.page = pg == null ? 1 : pg;
            ln.x = x;
            ln.y = y;
            ln.width = w;
            ln.text = text.trim();
            lines.add(ln);
        }
    }

    @Override
    public Book parse(File file, String forcedEncoding, ImageStore images, File mediaDir)
            throws IOException, ParseException {
        PDFBoxResourceLoader.init(AppCtx.get());
        PDDocument doc = null;
        try {
            doc = PDDocument.load(file);
            int pageCount = doc.getNumberOfPages();
            if (pageCount <= 0) throw new ParseException("这个 PDF 没有页面");

            boolean proof = false;
            try {
                proof = new Prefs(AppCtx.get()).proofOcr();
            } catch (Throwable ignore) {
            }

            Book book = new Book();
            book.format = "PDF";
            book.sourcePath = file.getAbsolutePath();
            book.title = pdfTitle(doc, file);
            book.pdfPath = file.getAbsolutePath();
            book.pdfPageCount = pageCount;
            book.mediaDir = mediaDir;

            // 1) 一次解析，收集每页每一行的位置
            LineStripper ls = new LineStripper(doc);
            ls.setStartPage(1);
            ls.setEndPage(pageCount);
            try {
                ls.getText(doc);
            } catch (Throwable ignore) {
            }

            // 2) 书签目录
            List<int[]> chapterPages = new ArrayList<>();
            List<String> chapterTitles = new ArrayList<>();
            readOutline(doc, chapterPages, chapterTitles);

            // 3) 逐页定内容：能直接用文字层的先用，其余先放占位，识别交给打开之后做
            File ocrDir = new File(mediaDir, "ocr");
            if (!ocrDir.exists()) ocrDir.mkdirs();
            book.ocrDir = ocrDir;

            int pending = 0;
            for (int i = 0; i < pageCount; i++) {
                List<Line> pageLines = new ArrayList<>();
                for (Line l : ls.lines) if (l.page == i + 1) pageLines.add(l);
                String raw = joinRaw(pageLines);
                File cache = new File(ocrDir, "p" + i + ".txt");
                boolean cached = cache.isFile() && cache.length() > 0;

                String title = book.title;
                boolean auto = true;
                for (int k = 0; k < chapterPages.size(); k++) {
                    if (chapterPages.get(k)[0] == i) {
                        title = chapterTitles.get(k);
                        auto = false;
                        break;
                    }
                }

                Chapter c = new Chapter(title, auto);
                PDPage pg = null;
                try {
                    pg = doc.getPage(i);
                } catch (Throwable ignore) {
                }
                final PDPage pageObj = pg;
                addPageImages(pageObj, c, i + 1, images, mediaDir);
                if (cached) {
                    String t = readFile(cache);
                    if (!t.trim().isEmpty()) c.addText(t);
                    c.pendingOcr = false;
                } else if (pageLines.isEmpty() || looksBroken(raw)) {
                    c.addText("（第 " + (i + 1) + " 页正在本地识别，识别好了会自动出现在这里）");
                    c.pendingOcr = true;
                    pending++;
                } else {
                    c.addText(orderPage(pageLines));
                    // 开了文字层校对：先给文字层原样，识别完再替换
                    if (proof) {
                        c.pendingOcr = true;
                        pending++;
                    }
                }
                book.pdfPages.put(i + 1, c);
                book.addChapter(c);
            }
            book.needsOcr = pending > 0;

            // 4) 封面
            book.coverPath = renderCover(file, mediaDir);
            book.sourceSize = file.length();
            return book;
        } catch (ParseException e) {
            throw e;
        } catch (Throwable e) {
            throw new ParseException("PDF 解析失败：" + (e.getMessage() == null ? "文件可能已损坏" : e.getMessage()));
        } finally {
            if (doc != null) try { doc.close(); } catch (IOException ignore) {}
        }
    }

    // ---------------- 页内排版 ----------------

    /** 这一页是不是没有可用的文字层 */
    private static boolean looksBroken(String text) {
        if (text == null) return true;
        if (text.trim().length() < SCAN_PAGE_CHARS) return true;
        int bad = 0, total = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == ' ' || ch == '\t' || ch == '\r') continue;
            total++;
            if (ch >= 0xE000 && ch <= 0xF8FF) bad++;
            else if (ch == '\uFFFD') bad++;
            else if (ch < 0x20) bad++;
        }
        return total > 0 && (bad / (float) total) > BROKEN_RATIO;
    }

    private static String joinRaw(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.text);
        }
        return sb.toString();
    }

    /**
     * 按栏重排：用行起点的横坐标找栏边界（相邻起点差超过页宽一成就算跨栏），
     * 再按"第一栏从上到下、第二栏从上到下"输出。
     */
    private static String orderPage(List<Line> lines) {
        if (lines.isEmpty()) return "";
        float maxRight = 0;
        for (Line l : lines) if (l.x + l.width > maxRight) maxRight = l.x + l.width;
        float pageW = Math.max(1f, maxRight);

        List<Float> xs = new ArrayList<>();
        for (Line l : lines) xs.add(l.x);
        Collections.sort(xs);
        List<Float> breaks = new ArrayList<>();
        for (int i = 1; i < xs.size(); i++) {
            float gap = xs.get(i) - xs.get(i - 1);
            if (gap > pageW * 0.12f) breaks.add((xs.get(i) + xs.get(i - 1)) / 2f);
        }
        if (breaks.size() > 2) breaks.clear();

        final List<Float> cols = breaks;
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                int ca = colIndex(a.x, cols), cb = colIndex(b.x, cols);
                if (ca != cb) return ca < cb ? -1 : 1;
                return a.y < b.y ? -1 : (a.y == b.y ? 0 : 1);
            }
        });

        StringBuilder sb = new StringBuilder();
        int lastCol = -1;
        for (Line l : lines) {
            int col = colIndex(l.x, cols);
            if (sb.length() > 0) sb.append('\n');
            sb.append(l.text);
            lastCol = col;
        }
        return sb.toString();
    }

    private static int colIndex(float x, List<Float> breaks) {
        int idx = 0;
        for (Float b : breaks) if (x > b) idx++;
        return idx;
    }

    /** 读 PDF 书签，算出每章起始页 */
    private void readOutline(PDDocument doc, List<int[]> ranges, List<String> titles) {
        try {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null) return;
            collect(outline.getFirstChild(), 0, doc, ranges, titles);
        } catch (Throwable ignore) {
        }
    }

    private void collect(PDOutlineItem item, int depth, PDDocument doc, List<int[]> ranges, List<String> titles) {
        if (item == null || depth > 2 || ranges.size() > 300) return;
        int page = -1;
        try {
            PDPage p = item.findDestinationPage(doc);
            if (p != null) page = doc.getPages().indexOf(p);
        } catch (Throwable ignore) {
        }
        if (page >= 0) {
            String title = item.getTitle();
            if (title == null || title.trim().isEmpty()) title = "第 " + (ranges.size() + 1) + " 章";
            if (ranges.isEmpty() || ranges.get(ranges.size() - 1)[0] != page) {
                ranges.add(new int[]{page, page});
                titles.add(title.trim());
            }
        }
        collect(item.getFirstChild(), depth + 1, doc, ranges, titles);
        collect(item.getNextSibling(), depth, doc, ranges, titles);
    }

    private String renderCover(File pdf, File mediaDir) {
        if (mediaDir == null) return null;
        ParcelFileDescriptor fd = null;
        PdfRenderer renderer = null;
        try {
            fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fd);
            if (renderer.getPageCount() <= 0) return null;
            PdfRenderer.Page page = renderer.openPage(0);
            int w = 640;
            int h = Math.max(1, (int) (w * (page.getHeight() / (float) page.getWidth())));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            page.close();
            File out = new File(mediaDir, "pdf-cover.png");
            if (!mediaDir.exists()) mediaDir.mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
            fos.close();
            bmp.recycle();
            return out.getAbsolutePath();
        } catch (Throwable e) {
            return null;
        } finally {
            if (renderer != null) try { renderer.close(); } catch (Exception ignore) {}
            if (fd != null) try { fd.close(); } catch (IOException ignore) {}
        }
    }

    private static String readFile(File f) {
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n <= 0) break;
                off += n;
            }
            in.close();
            return new String(b, 0, off, "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }

    private static void writeFile(File f, String text) {
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignore) {
        }
    }

    /**
     * 把这一页里内嵌的插图取出来，保存成图片文件，插在本页文字后面。
     * 整页扫描图（就是这页本身）不算插图，跳过去，否则每页一张大图，书就没法读了。
     */
    private static void addPageImages(PDPage page, Chapter ch, int pageNo,
                                      ImageStore images, File mediaDir) {
        if (page == null || ch == null || images == null || mediaDir == null) return;
        // 这一页的插图以前取过，直接按索引挂上，省掉重新解码
        File idxFile = new File(new File(mediaDir, "pdfimg"), "p" + pageNo + ".idx");
        if (idxFile.isFile() && idxFile.length() > 0) {
            try {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(idxFile), "UTF-8"));
                String l;
                while ((l = br.readLine()) != null) {
                    String[] kv = l.split("\t", 2);
                    if (kv.length >= 1 && !kv[0].isEmpty() && new File(kv[0]).isFile()) {
                        ch.addImage(kv[0], kv.length > 1 ? kv[1] : "第 " + pageNo + " 页插图");
                    }
                }
                br.close();
            } catch (Throwable ignore) {
            }
            return;
        }
        StringBuilder idx = new StringBuilder();
        try {
            PDResources res = page.getResources();
            if (res == null) return;
            float pw = page.getMediaBox().getWidth();
            float ph = page.getMediaBox().getHeight();
            if (pw <= 0 || ph <= 0) return;
            float pageRatio = pw / ph;
            int taken = 0;
            for (COSName name : res.getXObjectNames()) {
                if (taken >= 6) break;
                PDXObject x = null;
                try {
                    x = res.getXObject(name);
                } catch (Throwable e) {
                    continue;
                }
                if (!(x instanceof PDImageXObject)) continue;
                PDImageXObject img = (PDImageXObject) x;
                int w = img.getWidth();
                int h = img.getHeight();
                if (w < 48 || h < 48) continue;
                // 超大图先放过，避免解成位图时把内存撑爆
                if ((long) w * h > 24000000L) continue;
                float imgRatio = w / (float) h;
                boolean fullPage = (long) w * h > 1500000L
                        && (Math.abs(imgRatio - pageRatio) < 0.12f
                        || Math.abs(imgRatio - 1f / pageRatio) < 0.12f);
                if (fullPage) continue;
                Bitmap bmp = null;
                try {
                    bmp = img.getImage();
                } catch (Throwable e) {
                    continue;
                }
                if (bmp == null) continue;
                try {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
                    String path = images.save(bos.toByteArray(), mediaDir,
                            "pdfimg:p" + pageNo + "_" + taken);
                    if (path != null && !path.isEmpty()) {
                        ch.addImage(path, "第 " + pageNo + " 页插图");
                        idx.append(path).append('\t').append("第 ").append(pageNo).append(" 页插图\n");
                        taken++;
                    }
                } finally {
                    try {
                        bmp.recycle();
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        if (idx.length() > 0) {
            try {
                File dir = new File(mediaDir, "pdfimg");
                dir.mkdirs();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(idxFile, false);
                fos.write(idx.toString().getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static void report(String msg) {
        if (progress != null) progress.onProgress(msg);
    }

    /**
     * 书名：先看 PDF 自己带的标题（中文书大多写在这里），没有或不可读才用文件名。
     * 文件名常见"001234_书名.pdf""12345678书名.pdf"这种前面挂一串数字的，一并去掉。
     */
    private static String pdfTitle(PDDocument doc, java.io.File file) {
        String meta = null;
        try {
            if (doc != null && doc.getDocumentInformation() != null) {
                meta = doc.getDocumentInformation().getTitle();
            }
        } catch (Throwable ignore) {
        }
        // 以前识别出来的名字优先（扫描版文件名是纯数字时，第一页识别出的书名会存在这里）
        try {
            String ov = new Prefs(AppCtx.get()).pdfTitle(file.getAbsolutePath());
            if (ov != null && !ov.trim().isEmpty()) return ov.trim();
        } catch (Throwable ignore) {
        }
        String t = cleanTitle(meta);
        if (!t.isEmpty()) return t;
        String name = cleanTitle(stripExt(file.getName()));
        return name.isEmpty() ? stripExt(file.getName()) : name;
    }

    private static String cleanTitle(String s) {
        if (s == null) return "";
        s = s.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        // 生成工具留下的前缀
        s = s.replaceAll("(?i)^(microsoft word|microsoft powerpoint|microsoft publisher)\\s*-\\s*", "");
        // 前面挂的数字、序号、下划线、括号
        // 缓存文件名前面那串十六进制（带字母的那种）先去掉
        s = s.replaceAll("^[0-9a-fA-F]{5,16}[_\\-]", "");
        s = s.replaceAll("^[0-9０-９\\s_.\\-()（）【】\\[\\]]+", "");
        s = s.replaceAll("\\s{2,}", " ").trim();
        if (s.isEmpty()) return "";
        // 乱码、控制符、纯数字一律不要
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x20 || ch == 0xFFFD) return "";
        }
        String keep = s.replaceAll("[0-9０-９\\s\\p{Punct}]", "");
        if (keep.isEmpty()) return "";
        if (s.length() > 80) s = s.substring(0, 80).trim();
        return s;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}

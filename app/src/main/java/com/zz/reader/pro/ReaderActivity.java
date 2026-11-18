package com.zz.reader.pro;

import com.zz.reader.pro.core.Chapter;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AlphaAnimation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.zz.reader.pro.core.Book;
import com.zz.reader.pro.core.ImageStore;
import com.zz.reader.pro.core.LayoutEngine;
import com.zz.reader.pro.core.PageStyle;
import com.zz.reader.pro.core.ParseException;
import com.zz.reader.pro.core.ParserFactory;
import com.zz.reader.pro.core.TextUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 阅读界面：专业排版（分页、页眉页脚、页码进度）＋ 全程无障碍 */
public class ReaderActivity extends Activity {

    public static final String EXTRA_START_PARA = "start_para";

    private View readerRoot;
    private TextView tvHeader;
    private TextView tvFooter;
    private ScrollView scroll;
    private LinearLayout contentArea;
    private View progressLine;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private TextView tvBookInfo;
    private SeekBar seekProgress;
    private Button btnSpeak;
    private LinearLayout splash;
    private ImageView ivSplashCover;
    private TextView tvSplashTitle;
    private TextView tvSplashSub;
    private boolean splashVisible = false;

    private Prefs prefs;
    private PageStyle style;
    private RecentStore recent;
    private TtsPlayer tts;

    private Book book;
    private LayoutEngine engine;
    private int pageIndex = 0;
    private int pendingPara = 0;
    private ImageStore imageStore;

    private Uri sourceUri;
    private boolean barVisible = false;
    private boolean loading = false;

    // 手势
    private boolean twoFingerArmed = false;
    private float gestureStartX;
    private float gestureStartY;
    private final float[] fingerStartX = new float[4];
    private final float[] fingerStartY = new float[4];
    private final int[] fingerId = {-1, -1, -1, -1};
    private float downX;
    private float downY;
    private long downTime;
    private android.view.GestureDetector gestureDetector;
    private boolean singleSwipeDone = false;
    /** 正在横向拖动正文（跟手），此时页面随手指平移 */
    private boolean dragHorizontal = false;
    private boolean swipeHandled = false;

    // 朗读
    private List<String> sentences = new ArrayList<>();

    // ---- 连续流（读屏连续朗读用）：一次渲染若干页的正文，滚动时接着追加，不做整页替换
    private static final int FLOW_BATCH_PAGES = 4;
    private static final int FLOW_MAX_PAGES = 12;
    private int flowStartPage = -1;
    private int flowEndPage = -1;
    private final List<Integer> flowPageChildIndex = new ArrayList<>();
    private final List<Integer> flowPageY = new ArrayList<>();
    private final List<Integer> flowPageSentenceStart = new ArrayList<>();
    private boolean flowAppending = false;
    private boolean speaking = false;
    private boolean continuous = false;
    private int pendingSpeak = -1;
    private boolean pendingContinuous;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean ocrStarted = false;
    private long lastTouchSave = 0;
    private long lastSeek = 0;
    private int layoutRetry = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        prefs = new Prefs(this);
        style = prefs.style();
        recent = new RecentStore(this);
        tts = new TtsPlayer(this);

        readerRoot = findViewById(R.id.readerRoot);
        tvHeader = findViewById(R.id.tvHeader);
        tvHeader.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tvHeader.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tvHeader.setFocusable(false);
        tvFooter = findViewById(R.id.tvFooter);
        // 页码这行是给眼睛的装饰：不让读屏去念它，也不让它被焦点遍历到
        tvFooter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tvFooter.setFocusable(false);
        tvFooter.setContentDescription(null);
        scroll = findViewById(R.id.scroll);
        contentArea = findViewById(R.id.contentArea);
        gestureDetector = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) { return true; }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (e1 == null || e2 == null || singleSwipeDone) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        float adx = Math.abs(dx);
                        boolean byPos = adx >= dp(16);
                        boolean byVel = Math.abs(vx) > 220f && Math.abs(vx) >= Math.abs(vy);
                        if (!byPos && !byVel) return false;
                        singleSwipeDone = true;
                        swipeHandled = false;
                        ui.post(dx < 0 ? ReaderActivity.this::nextPage : ReaderActivity.this::prevPage);
                        return true;
                    }
                });
        progressLine = findViewById(R.id.progressLine);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        tvBookInfo = findViewById(R.id.tvBookInfo);
        seekProgress = findViewById(R.id.seekProgress);
        btnSpeak = findViewById(R.id.btnSpeak);
        splash = findViewById(R.id.splash);
        ivSplashCover = findViewById(R.id.ivSplashCover);
        tvSplashTitle = findViewById(R.id.tvSplashTitle);
        tvSplashSub = findViewById(R.id.tvSplashSub);

        sourceUri = getIntent().getData() != null
                ? getIntent().getData()
                : (Uri) getIntent().getParcelableExtra("uri");
        pendingPara = getIntent().getIntExtra(EXTRA_START_PARA, 0);
        if (pendingPara == 0 && getIntent().getParcelableExtra("uri") != null) {
            pendingPara = getIntent().getIntExtra(EXTRA_START_PARA, 0);
        }

        splash.setOnClickListener(v -> hideSplash());
        bindTools();
        applyTheme();
        scroll.setAccessibilityDelegate(pagingDelegate());
        scroll.getViewTreeObserver().addOnScrollChangedListener(() -> ui.post(this::onFlowScrolled));
        scroll.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        tts.setListener(new TtsPlayer.Listener() {
            @Override
            public void onReady(boolean ok, String message) {
                ui.post(() -> {
                    if (!ok) {
                        announce(message);
                        return;
                    }
                    if (pendingSpeak >= 0) {
                        int idx = pendingSpeak;
                        boolean c = pendingContinuous;
                        pendingSpeak = -1;
                        startSpeak(idx, c);
                    } else if (style.autoSpeak && book != null && !speaking) {
                        startSpeak(0, true);
                    }
                });
            }

            @Override
            public void onSpeakingSentence(int index) {
            }

            @Override
            public void onFinishedAll() {
                ui.post(ReaderActivity.this::onSpeakFinished);
            }
        });

        if (sourceUri == null) {
            Toast.makeText(this, "没有收到要打开的文件", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        loadBook();
    }

    private void bindTools() {
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnToc).setOnClickListener(v -> showToc());
        findViewById(R.id.btnBookmark).setOnClickListener(v -> showBookmarks());
        findViewById(R.id.btnSearch).setOnClickListener(v -> showSearch());
        findViewById(R.id.btnAa).setOnClickListener(v -> SettingsPanel.show(this, style, prefs,
                new SettingsPanel.Callback() {
                    @Override
                    public void onThemeChanged() {
                        applyTheme();
                    }

                    @Override
                    public void onLayoutChanged() {
                        onStyleChanged();
                    }

                    @Override
                    public String encoding() {
                        return prefs.encodingFor(sourceUri == null ? "" : sourceUri.toString());
                    }

                    @Override
                    public void setEncoding(String v) {
                        prefs.setEncodingFor(sourceUri == null ? "" : sourceUri.toString(), v);
                        pendingPara = currentPara();
                        loadBook();
                    }
                }));
        findViewById(R.id.btnPrevPage).setOnClickListener(v -> goPage(pageIndex - 1));
        findViewById(R.id.btnNextPage).setOnClickListener(v -> goPage(pageIndex + 1));
        btnSpeak.setOnClickListener(v -> {
            if (speaking) stopSpeak();
            else startSpeak(flowActive() ? currentPageSentenceStart() : 0, true);
        });
        findViewById(R.id.btnHideBar).setOnClickListener(v -> setBarVisible(false));

        seekProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || engine == null) return;
                int max = Math.max(1, engine.pages().size() - 1);
                int target = Math.round(progress / 1000f * max);
                if (flowActive()) {
                    long now = System.currentTimeMillis();
                    if (now - lastSeek < 350) return;
                    lastSeek = now;
                }
                goPage(target);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }

    // ---------------------------------------------------------------- 主题

    private void applyTheme() {
        int bg = style.bgColor();
        int fg = style.fgColor();
        int dim = style.dimColor();
        readerRoot.setBackgroundColor(bg);
        scroll.setBackgroundColor(bg);
        contentArea.setBackgroundColor(bg);
        tvHeader.setTextColor(dim);
        tvFooter.setTextColor(dim);
        int lineColor = style.theme == PageStyle.THEME_CONTRAST ? 0xFFFFE600
                : (style.theme == PageStyle.THEME_DARK || style.theme == PageStyle.THEME_BLACK
                ? 0x33FFFFFF : 0x33000000);
        progressLine.setBackgroundColor(lineColor);
        if (splash != null) {
            splash.setBackgroundColor(bg);
            tvSplashTitle.setTextColor(fg);
            tvSplashSub.setTextColor(dim);
        }
        progressLine.setVisibility(style.showProgressBar ? View.VISIBLE : View.GONE);
        tvBookInfo.setTextColor(0xB3FFFFFF);
    }

    // ---------------------------------------------------------------- 加载与排版

    private void loadBook() {
        if (loading) return;
        loading = true;
        announce("正在读取");
        new Thread(() -> {
            Book result = null;
            String error = null;
            ImageStore store = null;
            File dir = null;
            try {
                try {
                    getContentResolver().takePersistableUriPermission(sourceUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                File f = FileUtils.copyToCache(this, sourceUri);
                store = new ImageStore(getApplicationContext());
                dir = store.dirFor(f.getName() + "_" + f.length());
                result = ParserFactory.parse(f, prefs.encodingFor(sourceUri.toString()), store, dir);
                result.sourcePath = f.getAbsolutePath();
                result.sourceUri = sourceUri.toString();
                result.sourceSize = f.length();
            } catch (ParseException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = "读取失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final Book b = result;
            final String err = error;
            final ImageStore s2 = store;
            ui.post(() -> {
                loading = false;
                if (b == null) {
                    showError(err);
                    return;
                }
                book = b;
                imageStore = s2;
                afterBookLoaded();
            });
        }).start();
    }

    private void afterBookLoaded() {
        tvBookInfo.setText(book.title + (book.author.isEmpty() ? "" : " · " + book.author)
                + " · " + book.format + " · " + book.chapters.size() + " 章 · "
                + formatCount(book.totalChars()) + "字 · " + book.imageCount() + " 张图");
        readerRoot.post(() -> {
            if (readerRoot.getHeight() <= 0) {
                if (layoutRetry++ < 40) readerRoot.postDelayed(this::afterBookLoaded, 60);
                return;
            }
            layoutRetry = 0;
            showSplash();
            buildLayout(true);
        });
    }


    /**
     * 扫描版 PDF、以及开了文字层校对的页，识别一律在书打开之后一页一页做，
     * 每做完一页就替换正文并重排，随时能看到进度，不会把打开卡住。
     */
    private void startOcrWorker() {
        if (ocrStarted || book == null || !book.needsOcr || book.pdfPath == null) return;
        ocrStarted = true;
        final Book b = book;
        final java.io.File pdf = new java.io.File(b.pdfPath);
        Thread t = new Thread(() -> {
            com.zz.reader.pro.core.OcrEngine o = null;
            String fail = null;
            try {
                o = com.zz.reader.pro.core.OcrEngine.get();
            } catch (Throwable e) {
                fail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (o == null) {
                final String f = fail;
                ui.post(() -> announce("本地识别没能启动：" + f));
                return;
            }
            final int total = b.pdfPageCount;
            ui.post(() -> announce("打开好了，正在后台识别，共 " + total + " 页"));
            int done = 0;
            for (int p = 1; p <= total; p++) {
                final Chapter ch = b.pdfPages.get(p);
                if (ch == null || !ch.pendingOcr) continue;
                String text = "";
                try {
                    text = o.recognizePage(pdf, p - 1);
                } catch (Throwable e) {
                    com.zz.reader.pro.core.OcrEngine.lastError =
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                if (text == null) text = "";
                done++;
                if (text.trim().isEmpty()) {
                    ch.pendingOcr = false;
                    continue;
                }
                final String tx = text;
                final int idx = p;
                final int dn = done;
                ui.post(() -> {
                    ch.items.clear();
                    ch.addText(tx);
                    ch.pendingOcr = false;
                    b.invalidateBlocks();
                    if (dn == 1) announce("第 " + idx + " 页识别好了");
                    if (dn <= 3 || dn % 3 == 0) buildLayout(true);
                });
            }
            final int finalDone = done;
            ui.post(() -> announce("要识别的页面都做完了，共 " + finalDone + " 页"));
        }, "reader-ocr");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static String formatCount(int n) {
        if (n >= 10000) return (n / 1000) / 10f + "万";
        return String.valueOf(n);
    }

    /** 重新分页，keepPara 表示按当前段落位置保持阅读位置 */
    private void buildLayout(boolean restore) {
        int rootW = readerRoot.getWidth();
        int rootH = readerRoot.getHeight();
        if (rootW <= 0 || rootH <= 0 || book == null) return;

        float density = getResources().getDisplayMetrics().density;
        int marginPx = Math.round(style.marginHorizontalDp * density);
        int contentW = rootW - marginPx * 2;
        int headerH = style.showHeader ? dp(28) : 0;
        int footerH = style.showFooter ? dp(28) : 0;
        // 正文容器自己有 6dp 上、10dp 下内边距，必须从可用高度里扣除，否则每页会溢出十几像素
        int contentH = rootH - headerH - footerH - dp(6) - dp(10) - dp(2);
        if (contentH < dp(80)) contentH = dp(80);

        int textPx = Math.round(style.textSizeSp * getResources().getDisplayMetrics().scaledDensity);
        final Typeface tf = typeface();

        final int keepPara = restore ? currentPara() : pendingPara;
        final int w = contentW;
        final int h = contentH;
        final int size = textPx;

        announce("正在排版");
        new Thread(() -> {
            LayoutEngine e = new LayoutEngine(book, style, w, h, size, density, tf);
            e.compute();
            ui.post(() -> {
                engine = e;
                if (book.blocks().isEmpty()) {
                    Toast.makeText(this, "这本书没有可显示的内容", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                pageIndex = keepPara <= 0 ? 0 : e.pageOfPara(keepPara);
                clamp();
                renderPage(true);
                hideSplash();
                startOcrWorker();
                if (style.autoSpeak) hideBarQuietly();
                if (style.autoSpeak && tts.isReady() && !speaking) startSpeak(0, true);
            });
        }).start();
    }

    private void onStyleChanged() {
        prefs.save(style);
        applyTheme();
        buildLayout(true);
    }

    private Typeface typeface() {
        if (style.fontFamily == PageStyle.FONT_SERIF) return Typeface.SERIF;
        if (style.fontFamily == PageStyle.FONT_MONO) return Typeface.MONOSPACE;
        return Typeface.SANS_SERIF;
    }

    private int currentPara() {
        if (engine == null || engine.pages().isEmpty()) return pendingPara;
        LayoutEngine.Page p = engine.pages().get(Math.max(0, Math.min(pageIndex, engine.pages().size() - 1)));
        return p.startPara;
    }

    private void clamp() {
        if (engine == null) return;
        int total = engine.pages().size();
        if (pageIndex < 0) pageIndex = 0;
        if (pageIndex >= total) pageIndex = total - 1;
    }

    private void showError(String message) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("这本书读不出来")
                .setMessage((message == null ? "未知原因" : message)
                        + "\n\n如果是 TXT 出现乱码，可以换一种文字编码重新读。"
                        + "\n如果是提示没有权限，说明这个文件的访问许可已经过期，回书库重新选一次这个文件就能恢复。")
                .setPositiveButton("换编码重试", (d, w) -> {
                    String[] choices = Prefs.ENCODINGS;
                    new AlertDialog.Builder(this)
                            .setTitle("选择文字编码")
                            .setItems(choices, (d2, which) -> {
                                prefs.setEncodingChoice(choices[which]);
                                loadBook();
                            })
                            .show();
                })
                .setNegativeButton("返回", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ---------------------------------------------------------------- 渲染

    /** 按段落范围把正文加进 contentArea，供分页渲染和连续流渲染共用 */
    private void renderRange(int fromPara, int toPara, int textPxExact, Typeface tf,
                             StringBuilder outText) {
        if (engine == null) return;
        if (fromPara < 0) fromPara = 0;
        if (toPara > engine.paraCount() - 1) toPara = engine.paraCount() - 1;
        for (int i = fromPara; i <= toPara; i++) {
            Book.Block b = engine.para(i);
            if (b == null) continue;
            if (b.isImage()) {
                addImageBlock(b, i);
                continue;
            }
            if (b.text == null || b.text.trim().isEmpty()) continue;
            boolean title = LayoutEngine.isTitle(b.text);
            TextView tv = new TextView(this);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, title ? textPxExact * 1.12f : textPxExact);
            tv.setTypeface(tf);
            tv.getPaint().setFakeBoldText(title || style.bold);
            if (Build.VERSION.SDK_INT >= 23) {
                tv.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                tv.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            }
            tv.setLineSpacing(0f, style.lineMult);
            tv.setTextColor(style.fgColor());
            tv.setText(LayoutEngine.buildPara(b.text, title, Math.round(style.indentChars * textPxExact)));
            if (title) tv.setGravity(android.view.Gravity.CENTER);
            if (Build.VERSION.SDK_INT >= 26 && style.justify && !title) {
                tv.setJustificationMode(android.text.Layout.JUSTIFICATION_MODE_INTER_WORD);
            }
            int spacingTop = dp(style.paraSpacingDp) + (title ? dp(style.paraSpacingDp / 2) : 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = spacingTop;
            tv.setLayoutParams(lp);
            tv.setFocusable(true);
            tv.setFocusableInTouchMode(true);
            tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            tv.setAccessibilityDelegate(scrollOnlyDelegate());
            contentArea.addView(tv);
            if (outText != null) outText.append(b.text).append('\n');
        }
    }

    /** 分页模式在页末放一个哨兵：读屏的连续朗读念到它，就自动翻下一页 */
    private void addPageEndSentinel(boolean lastPage) {
        if (flowActive() || lastPage) return;
        View sentinel = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(1, 1);
        lp.topMargin = 0;
        sentinel.setLayoutParams(lp);
        sentinel.setFocusable(true);
        sentinel.setFocusableInTouchMode(true);
        sentinel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        // 这个节点只是用来"读到页末"的信号，不能让它自己念出文字，所以描述只留一个零宽字符
        sentinel.setContentDescription(ZERO_WIDTH);
        sentinel.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(ZERO_WIDTH);
                info.setText(ZERO_WIDTH);
                info.setClassName("android.view.View");
                info.setFocusable(true);
                info.setVisibleToUser(true);
            }

            @Override
            public void sendAccessibilityEvent(View host, int eventType) {
                super.sendAccessibilityEvent(host, eventType);
                if (eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                        || eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    onReachPageEnd(lastPage);
                }
            }
        });
        contentArea.addView(sentinel);
    }

    private static final String ZERO_WIDTH = "\u200b";

    private boolean sentinelBusy = false;

    private void onReachPageEnd(boolean lastPage) {
        if (sentinelBusy || lastPage || engine == null) return;
        if (!style.autoTurnOnFocus) return;
        sentinelBusy = true;
        ui.postDelayed(() -> {
            sentinelBusy = false;
            if (isFinishing() || engine == null) return;
            if (pageIndex >= engine.pages().size() - 1) return;
            pageIndex++;
            renderPage(true);
            focusA11yFirstOnPage();
        }, 320);
    }

    /** 把无障碍焦点直接交给当前页第一段，读屏就能接着往下念 */
    private void focusA11yFirstOnPage() {
        if (contentArea == null || contentArea.getChildCount() == 0) return;
        View first = contentArea.getChildAt(0);
        first.requestFocus();
        if (Build.VERSION.SDK_INT >= 21) {
            first.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
        }
    }

    private void renderPage(boolean silent) {
        if (engine == null || book == null) return;
        clamp();
        if (flowActive()) {
            renderFlow(pageIndex, silent);
            return;
        }
        LayoutEngine.Page page = engine.pages().get(pageIndex);
        int marginPx = Math.round(style.marginHorizontalDp * getResources().getDisplayMetrics().density);
        contentArea.setPadding(marginPx, dp(6), marginPx, dp(10));

        contentArea.removeAllViews();
        int textPxSize = style.textSizeSp;
        // 渲染字号必须和分页测量用同一个像素值，否则某些机型会出现"测量能放下、显示却被切行"
        int textPxExact = Math.round(textPxSize * getResources().getDisplayMetrics().scaledDensity);
        Typeface tf = typeface();

        StringBuilder pageText = new StringBuilder();
        renderRange(page.startPara, Math.min(page.endPara, engine.paraCount() - 1),
                textPxExact, tf, pageText);

        // 页眉、页脚
        String chapter = page.chapterTitle == null ? "" : page.chapterTitle;
        if (style.showHeader) {
            tvHeader.setText(book.title + (chapter.isEmpty() ? "" : "  ·  " + chapter));
        }
        int percent = engine.percentOfPage(pageIndex);
        int minutes = engine.minutesLeft(pageIndex, percent);
        if (style.showFooter) {
            String left = minutes > 0 ? "还剩约 " + minutes + " 分钟" : "快读完了";
            tvFooter.setText("第 " + (pageIndex + 1) + " / " + engine.pages().size()
                    + " 页   " + percent + "%   " + left);
        }
        applyChrome();

        // 逐句念的句间停顿很明显，把相邻短句拼成一段再交给语音引擎，听感会快不少
        sentences = mergeSentences(TextUtil.splitSentences(pageText.toString()));
        scroll.scrollTo(0, 0);
        seekProgress.setProgress((int) (1000L * pageIndex / Math.max(1, engine.pages().size() - 1)));
        updateSpeakButton();
        animateIn();
        if (!speaking) focusFirstOnPage();
        savePosition();

        if (speaking && continuous) {
            // 朗读中翻页后，从新页继续（由 onSpeakFinished 触发，这里不打断）
        }
    }

    // ---------------------------------------------------------------- 连续流

    private boolean flowActive() {
        return style != null && style.continuousScroll && engine != null && !engine.pages().isEmpty();
    }

    /** 连续流：从 anchorPage 起渲染一批页的正文，后面靠滚动追加，不整页替换 */
    private void renderFlow(int anchorPage, boolean silent) {
        int totalPages = engine.pages().size();
        if (anchorPage < 0) anchorPage = 0;
        if (anchorPage >= totalPages) anchorPage = totalPages - 1;
        pageIndex = anchorPage;
        flowStartPage = anchorPage;
        flowEndPage = Math.min(totalPages - 1, anchorPage + FLOW_BATCH_PAGES - 1);
        buildFlowContent();
        scroll.scrollTo(0, 0);
        updateFlowFooter();
        updateSpeakButton();
        seekProgress.setProgress((int) (1000L * pageIndex / Math.max(1, totalPages - 1)));
        if (!speaking) focusFirstOnPage();
        savePosition();

    }

    private void buildFlowContent() {
        int marginPx = Math.round(style.marginHorizontalDp * getResources().getDisplayMetrics().density);
        contentArea.setTranslationX(0);
        contentArea.setPadding(marginPx, dp(6), marginPx, dp(10));
        contentArea.removeAllViews();
        flowPageChildIndex.clear();
        flowPageY.clear();
        flowPageSentenceStart.clear();
        List<String> all = new ArrayList<>();
        int textPxExact = Math.round(style.textSizeSp * getResources().getDisplayMetrics().scaledDensity);
        Typeface tf = typeface();
        for (int p = flowStartPage; p <= flowEndPage; p++) {
            flowPageChildIndex.add(contentArea.getChildCount());
            flowPageSentenceStart.add(all.size());
            StringBuilder sb = new StringBuilder();
            LayoutEngine.Page pg = engine.pages().get(p);
            renderRange(pg.startPara, Math.min(pg.endPara, engine.paraCount() - 1),
                    textPxExact, tf, sb);
            all.addAll(mergeSentences(TextUtil.splitSentences(sb.toString())));
        }
        sentences = all;
        contentArea.post(this::collectFlowGeometry);
    }

    private void appendFlowBatch() {
        if (engine == null || flowStartPage < 0) return;
        int totalPages = engine.pages().size();
        if (flowEndPage >= totalPages - 1) return;
        trimFlowIfNeeded();
        int from = flowEndPage + 1;
        int to = Math.min(totalPages - 1, from + FLOW_BATCH_PAGES - 1);
        int textPxExact = Math.round(style.textSizeSp * getResources().getDisplayMetrics().scaledDensity);
        Typeface tf = typeface();
        List<String> all = new ArrayList<>(sentences);
        for (int p = from; p <= to; p++) {
            flowPageChildIndex.add(contentArea.getChildCount());
            flowPageSentenceStart.add(all.size());
            StringBuilder sb = new StringBuilder();
            LayoutEngine.Page pg = engine.pages().get(p);
            renderRange(pg.startPara, Math.min(pg.endPara, engine.paraCount() - 1),
                    textPxExact, tf, sb);
            all.addAll(mergeSentences(TextUtil.splitSentences(sb.toString())));
        }
        sentences = all;
        flowEndPage = to;
        contentArea.post(this::collectFlowGeometry);
    }

    /** 渲染的页数太多时，把最前面一批（已经读过去的）移掉，并保持当前位置不跳 */
    private void trimFlowIfNeeded() {
        if (flowPageChildIndex.size() < FLOW_MAX_PAGES) return;
        int removeChildren = Math.min(flowPageChildIndex.get(FLOW_BATCH_PAGES), contentArea.getChildCount());
        if (removeChildren <= 0) return;
        View firstKept = contentArea.getChildAt(removeChildren);
        int removedHeight = firstKept == null ? 0 : firstKept.getTop();
        int sy = scroll.getScrollY();
        contentArea.removeViews(0, removeChildren);
        flowPageChildIndex.subList(0, Math.min(FLOW_BATCH_PAGES, flowPageChildIndex.size())).clear();
        for (int i = 0; i < flowPageChildIndex.size(); i++) {
            flowPageChildIndex.set(i, Math.max(0, flowPageChildIndex.get(i) - removeChildren));
        }
        if (!flowPageY.isEmpty()) {
            flowPageY.subList(0, Math.min(FLOW_BATCH_PAGES, flowPageY.size())).clear();
        }
        if (!flowPageSentenceStart.isEmpty()) {
            flowPageSentenceStart.subList(0, Math.min(FLOW_BATCH_PAGES, flowPageSentenceStart.size())).clear();
        }
        // 句子表跟着前面那批一起删掉，索引整体前移
        int removedSentences = FLOW_BATCH_PAGES < flowPageSentenceStart.size() ? flowPageSentenceStart.get(0) : 0;
        if (removedSentences > 0 && removedSentences <= sentences.size()) {
            sentences = new ArrayList<>(sentences.subList(removedSentences, sentences.size()));
            for (int i = 0; i < flowPageSentenceStart.size(); i++) {
                flowPageSentenceStart.set(i, Math.max(0, flowPageSentenceStart.get(i) - removedSentences));
            }
        }
        flowStartPage += FLOW_BATCH_PAGES;
        scroll.scrollTo(0, Math.max(0, sy - removedHeight));
        contentArea.post(this::collectFlowGeometry);
    }

    private void collectFlowGeometry() {
        flowPageY.clear();
        for (int idx : flowPageChildIndex) {
            View v = (idx >= 0 && idx < contentArea.getChildCount()) ? contentArea.getChildAt(idx) : null;
            flowPageY.add(v == null ? 0 : v.getTop());
        }
        syncPageFromScroll();
    }

    private void onFlowScrolled() {
        if (!flowActive() || flowStartPage < 0) return;
        syncPageFromScroll();
        if (flowAppending) return;
        int sy = scroll.getScrollY();
        int vh = scroll.getHeight();
        int ch = contentArea.getHeight();
        if (sy + vh + dp(320) >= ch && flowEndPage < engine.pages().size() - 1) {
            flowAppending = true;
            appendFlowBatch();
            flowAppending = false;
        }
    }

    private void syncPageFromScroll() {
        if (!flowActive() || engine == null || flowPageY.isEmpty()) return;
        int sy = scroll.getScrollY() + dp(12);
        int idx = 0;
        for (int i = 0; i < flowPageY.size(); i++) {
            if (flowPageY.get(i) <= sy) idx = i;
        }
        int newPage = Math.min(engine.pages().size() - 1, flowStartPage + idx);
        if (newPage != pageIndex) {
            pageIndex = newPage;
            if (barVisible) {
                updateFlowFooter();
                seekProgress.setProgress((int) (1000L * pageIndex
                        / Math.max(1, engine.pages().size() - 1)));
            }
            savePosition();
        }
    }

    private void updateFlowFooter() {
        if (engine == null) return;
        int total = engine.pages().size();
        LayoutEngine.Page pg = engine.pages().get(Math.max(0, Math.min(pageIndex, total - 1)));
        String chapter = pg.chapterTitle == null ? "" : pg.chapterTitle;
        if (style.showHeader) {
            tvHeader.setText(book.title + (chapter.isEmpty() ? "" : "  ·  " + chapter));
        }
        int percent = engine.percentOfPage(pageIndex);
        int minutes = engine.minutesLeft(pageIndex, percent);
        if (style.showFooter) {
            String left = minutes > 0 ? "还剩约 " + minutes + " 分钟" : "快读完了";
            tvFooter.setText("第 " + (pageIndex + 1) + " / " + total + " 页   " + percent + "%   " + left);
        }
        applyChrome();
    }

    /** 连续流里"翻一页"就是滚一屏；滚到底就接着追加下一批 */
    private void flowScrollScreen(boolean forward) {
        if (!flowActive()) return;
        int vh = scroll.getHeight();
        int step = Math.max(dp(200), vh - dp(48));
        int target = scroll.getScrollY() + (forward ? step : -step);
        if (forward && target >= Math.max(0, contentArea.getHeight() - vh)
                && flowEndPage < engine.pages().size() - 1) {
            appendFlowBatch();
            final int want = target;
            contentArea.post(() -> {
                int max2 = Math.max(0, contentArea.getHeight() - scroll.getHeight());
                scroll.scrollTo(0, Math.min(want, max2));
            });
            return;
        }
        int max = Math.max(0, contentArea.getHeight() - vh);
        if (max <= 0) {
            // 内容还没铺满一屏：先追加下一批再滚，否则 scrollTo 等于没动
            if (flowEndPage < engine.pages().size() - 1) {
                appendFlowBatch();
                contentArea.post(() -> {
                    int vh2 = scroll.getHeight();
                    int max2 = Math.max(0, contentArea.getHeight() - vh2);
                    int target2 = scroll.getScrollY() + (forward ? Math.max(dp(200), vh2 - dp(48)) : -Math.max(dp(200), vh2 - dp(48)));
                    scroll.scrollTo(0, Math.max(0, Math.min(target2, max2)));
                });
            }
            return;
        }
        scroll.scrollTo(0, Math.max(0, Math.min(target, max)));
    }

    private int currentPageSentenceStart() {
        if (!flowActive() || flowPageSentenceStart.isEmpty()) return 0;
        int idx = pageIndex - flowStartPage;
        if (idx <= 0) return 0;
        if (idx >= flowPageSentenceStart.size()) idx = flowPageSentenceStart.size() - 1;
        return Math.max(0, flowPageSentenceStart.get(idx));
    }

    private List<String> mergeSentences(List<String> in) {
        List<String> out = new ArrayList<>();
        if (in == null) return out;
        StringBuilder sb = new StringBuilder();
        for (String t : in) {
            if (t == null || t.trim().isEmpty()) continue;
            String v = t.trim();
            if (sb.length() > 0 && sb.length() + v.length() > 90) {
                out.add(sb.toString());
                sb.setLength(0);
            }
            sb.append(v);
        }
        if (sb.length() > 0) out.add(sb.toString());
        return out;
    }

    private void addImageBlock(Book.Block b, int paraIndex) {
        int areaW = contentArea.getWidth();
        if (areaW <= 0) {
            areaW = readerRoot.getWidth()
                    - 2 * Math.round(style.marginHorizontalDp * getResources().getDisplayMetrics().density);
        }
        if (areaW <= 0) areaW = dp(320);
        Bitmap bmp = imageStore == null ? null : imageStore.load(b.imagePath, areaW, dp(420));
        if (bmp == null) {
            TextView tv = new TextView(this);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    Math.round(style.textSizeSp * getResources().getDisplayMetrics().scaledDensity * 0.8f));
            tv.setTextColor(style.dimColor());
            tv.setText(b.imageAlt != null && !b.imageAlt.isEmpty() ? "（插图：" + b.imageAlt + "）" : "（插图无法显示）");
            tv.setFocusable(true);
            tv.setContentDescription(b.imageAlt == null ? "插图无法显示" : "插图，" + b.imageAlt);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(style.paraSpacingDp);
            tv.setLayoutParams(lp);
            contentArea.addView(tv);
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int h = Math.max(dp(60), engine.heightOf(paraIndex) - dp(style.paraSpacingDp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
        lp.topMargin = dp(style.paraSpacingDp);
        iv.setLayoutParams(lp);
        iv.setFocusable(true);
        iv.setContentDescription(b.imageAlt != null && !b.imageAlt.trim().isEmpty()
                ? "插图，" + b.imageAlt : "插图");
        contentArea.addView(iv);
    }

    /** 翻页后把读屏焦点交给新页的第一段，连续朗读才能顺着往下走 */
    private void focusFirstOnPage() {
        if (contentArea == null || contentArea.getChildCount() == 0) return;
        View first = contentArea.getChildAt(0);
        if (first.requestFocus()) {
            first.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
        }
        if (scroll != null) {
            scroll.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED);
        }
    }

    private void animateIn() {
        if (style.pageAnim == 0) {
            contentArea.clearAnimation();
            return;
        }
        AlphaAnimation a = new AlphaAnimation(0.35f, 1f);
        a.setDuration(160);
        contentArea.startAnimation(a);
    }

    private String lastAnnouncedChapter = null;

    /** 翻页只报"换到哪一章"，不报页码；朗读进行中一律不出声，免得打断 */
    private void announceChapterIfChanged(String chapter) {
        if (speaking || splashVisible) return;
        String c = chapter == null ? "" : chapter.trim();
        if (c.equals(lastAnnouncedChapter == null ? "" : lastAnnouncedChapter)) return;
        lastAnnouncedChapter = c;
        if (!c.isEmpty()) announce("现在这一章是：" + c);
    }

    private void updateSpeakButton() {
        btnSpeak.setText(speaking ? "暂停" : "朗读");
        btnSpeak.setContentDescription(speaking
                ? "暂停朗读" : "从当前页开始朗读，会一直读到书末");
    }

    private void savePosition() {
        savePosition(false);
    }

    private void savePosition(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastTouchSave < 400) return;
        lastTouchSave = now;
        if (book == null || engine == null) return;
        int percent = engine.percentOfPage(pageIndex);
        if (!new Prefs(this).addToLibrary()) return;
        recent.touch(sourceUri.toString(), book.title, book.format, book.coverPath,
                currentPara(), percent);
    }

    private void showSplash() {
        if (book == null || splash == null) return;
        ui.postDelayed(this::hideSplash, 1600);
        splashVisible = true;
        splash.setVisibility(View.VISIBLE);
        tvSplashTitle.setText(book.title);
        tvSplashSub.setText((book.author.isEmpty() ? "" : book.author + "　") + book.format
                + (book.chapters.isEmpty() ? "" : "　" + book.chapters.size() + " 章"));
        Bitmap cover = null;
        if (book.coverPath != null && !book.coverPath.isEmpty() && imageStore != null) {
            cover = imageStore.load(book.coverPath, dp(150), dp(210));
        }
        if (cover != null) {
            ivSplashCover.setImageBitmap(cover);
            ivSplashCover.setVisibility(View.VISIBLE);
        } else {
            ivSplashCover.setVisibility(View.GONE);
        }
    }

    private void hideSplash() {
        if (!splashVisible || splash == null) return;
        splashVisible = false;
        AlphaAnimation a = new AlphaAnimation(1f, 0f);
        a.setDuration(360);
        a.setFillAfter(true);
        splash.startAnimation(a);
        splash.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- 翻页

    private void goPage(int index) {
        if (engine == null) return;
        if (flowActive()) {
            int total = engine.pages().size();
            if (index < 0) {
                announce("已经是第一页");
                return;
            }
            if (index >= total) {
                announce("已经是最后一页");
                if (speaking) stopSpeak();
                return;
            }
            boolean wasSpeakingFlow = speaking;
            if (wasSpeakingFlow) tts.stop();
            renderFlow(index, false);
            if (wasSpeakingFlow) startSpeak(currentPageSentenceStart(), true);
            return;
        }
        if (index < 0) {
            announce("已经是第一页");
            return;
        }
        if (index >= engine.pages().size()) {
            announce("已经是最后一页");
            if (speaking) stopSpeak();
            return;
        }
        boolean wasSpeaking = speaking;
        if (wasSpeaking) tts.stop();
        pageIndex = index;
        renderPage(false);
        if (wasSpeaking) startSpeak(0, true);
    }

    private void nextPage() {
        if (flowActive()) {
            flowScrollScreen(true);
            return;
        }
        goPage(pageIndex + 1);
    }

    private void prevPage() {
        if (flowActive()) {
            flowScrollScreen(false);
            return;
        }
        goPage(pageIndex - 1);
    }

    private void toggleBar() {
        setBarVisible(!barVisible);
    }

    private void hideBarQuietly() {
        barVisible = false;
        topBar.setVisibility(View.GONE);
        bottomBar.setVisibility(View.GONE);
        applyChrome();
    }

    /** 工具栏收起时把页眉、页脚（页码进度）一起藏起来：它们每滚一屏都要改字，会打断连续朗读 */
    private void applyChrome() {
        if (tvHeader == null || tvFooter == null) return;
        tvHeader.setVisibility(style.showHeader && barVisible ? View.VISIBLE : View.GONE);
        tvFooter.setVisibility(style.showFooter && barVisible ? View.VISIBLE : View.GONE);
    }

    private void setBarVisible(boolean visible) {
        barVisible = visible;
        topBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        applyChrome();
        if (visible) {
            announce("工具栏已打开，可以用目录、书签、搜索和设置");
        } else {
            announce("工具栏已收起。双指滑动翻页，点击屏幕右侧翻下一页");
        }
    }

    // ---------------------------------------------------------------- 手势

    private View.AccessibilityDelegate pagingDelegate;
    private View.AccessibilityDelegate scrollOnlyDelegate;

    /**
     * 段落上只开放"前/后滚动"两个动作：焦点停在哪一段，双指滑动都仍然翻页。
     * 这里不再加自定义动作，否则读屏逐段朗读时会被"操作菜单"干扰。
     */
    private View.AccessibilityDelegate scrollOnlyDelegate() {
        if (scrollOnlyDelegate != null) return scrollOnlyDelegate;
        scrollOnlyDelegate = new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setScrollable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    ui.post(ReaderActivity.this::nextPage);
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    ui.post(ReaderActivity.this::prevPage);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        };
        return scrollOnlyDelegate;
    }

    private View.AccessibilityDelegate pagingDelegate() {
        if (pagingDelegate != null) return pagingDelegate;
        pagingDelegate = new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setScrollable(true);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000001, "上一页"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000002, "下一页"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000003, "从这里朗读到书末"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000004, "停止朗读"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000005, "打开阅读工具栏"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01000006, "收起工具栏"));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == 0x01000003) {
                    ui.post(() -> {
                        if (speaking) stopSpeak();
                        startSpeak(flowActive() ? currentPageSentenceStart() : 0, true);
                    });
                    return true;
                }
                if (action == 0x01000004) {
                    ui.post(ReaderActivity.this::stopSpeak);
                    return true;
                }
                if (action == 0x01000005) {
                    ui.post(() -> setBarVisible(true));
                    return true;
                }
                if (action == 0x01000006) {
                    ui.post(() -> setBarVisible(false));
                    return true;
                }
                if (action == 0x01000002 || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    ui.post(ReaderActivity.this::nextPage);
                    return true;
                }
                if (action == 0x01000001 || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    ui.post(ReaderActivity.this::prevPage);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        };
        return pagingDelegate;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (swipeHandled) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                swipeHandled = false;
                singleSwipeDone = false;
                twoFingerArmed = false;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            swipeHandled = false;
            dragHorizontal = false;
            downX = ev.getX();
            downY = ev.getY();
            downTime = System.currentTimeMillis();
            singleSwipeDone = false;
            twoFingerArmed = false;
            rememberBothFingers(ev, true);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (ev.getPointerCount() >= 2) {
                rememberBothFingers(ev, false);
                twoFingerArmed = true;
            }
        } else if (action == MotionEvent.ACTION_MOVE && ev.getPointerCount() >= 2) {
            if (!twoFingerArmed) {
                rememberBothFingers(ev, true);
                twoFingerArmed = true;
            }
            float bestDx = 0, bestDy = 0;
            boolean hit = false;
            for (int i = 0; i < ev.getPointerCount() && i < 4; i++) {
                int idx = idxOfPointer(ev.getPointerId(i));
                if (idx < 0) continue;
                float dx = ev.getX(i) - fingerStartX[idx];
                float dy = ev.getY(i) - fingerStartY[idx];
                if (Math.abs(dx) > Math.abs(bestDx)) bestDx = dx;
                if (Math.abs(dy) > Math.abs(bestDy)) bestDy = dy;
                if (Math.abs(dx) > dp(30) || Math.abs(dy) > dp(34)) hit = true;
            }
            if (hit) {
                twoFingerArmed = false;
                singleSwipeDone = true;
                swipeHandled = true;
                if (Math.abs(bestDx) >= Math.abs(bestDy)) {
                    ui.post(bestDx < 0 ? this::nextPage : this::prevPage);
                } else {
                    ui.post(bestDy < 0 ? this::nextPage : this::prevPage);
                }
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            // 单指横移满 24dp 立即翻页，不等抬手、不看速度、不看竖向分量：
            // 手指一动就翻，跟翻手机屏幕一样跟手。
            float dx = ev.getX() - downX;
            if (!singleSwipeDone && Math.abs(dx) >= dp(24)) {
                singleSwipeDone = true;
                swipeHandled = true;
                ui.post(dx < 0 ? this::nextPage : this::prevPage);
                return true;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            twoFingerArmed = false;
            float dx = ev.getX() - downX;
            // MOVE 事件稀的机器，抬手时补一次
            if (!singleSwipeDone && Math.abs(dx) >= dp(24)) {
                singleSwipeDone = true;
                swipeHandled = false;
                ui.post(dx < 0 ? this::nextPage : this::prevPage);
                return true;
            } else if (isTap(ev) && !singleSwipeDone) {
                handleTap(ev.getX());
            }
            singleSwipeDone = false;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            twoFingerArmed = false;
            singleSwipeDone = false;
            swipeHandled = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 单指横向滑动是否够格算翻页。两种模式用同一套宽松标准：
     * 横移满 32dp、且横向明显大于竖向（0.6 倍）就算。斜着滑一点也认，
     * 只有竖着往上推（横向很小）才交给滚动。
     */
    private boolean isHorizontalSwipe(float dx, float dy) {
        // 只看横向位移，不看竖向：竖向滚动时手的横向漂移很少超过 32dp，
        // 而斜着滑、手抖、滑得慢这些情况都可能让竖向分量偏大，再叠一个比例条件就会漏判。
        return Math.abs(dx) >= dp(32);
    }

    private void rememberBothFingers(MotionEvent ev, boolean reset) {
        if (reset) {
            for (int i = 0; i < 4; i++) fingerId[i] = -1;
        }
        for (int i = 0; i < ev.getPointerCount() && i < 4; i++) {
            int pid = ev.getPointerId(i);
            int slot = idxOfPointer(pid);
            if (slot < 0) slot = freeSlot();
            if (slot < 0) continue;
            fingerId[slot] = pid;
            fingerStartX[slot] = ev.getX(i);
            fingerStartY[slot] = ev.getY(i);
        }
    }

    private int idxOfPointer(int pointerId) {
        for (int i = 0; i < 4; i++) {
            if (fingerId[i] == pointerId) return i;
        }
        return -1;
    }

    private int freeSlot() {
        for (int i = 0; i < 4; i++) {
            if (fingerId[i] < 0) return i;
        }
        return -1;
    }

    private boolean isTouchExplorationOn() {
        android.view.accessibility.AccessibilityManager am =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        return am != null && am.isTouchExplorationEnabled();
    }

    private boolean isTap(MotionEvent ev) {
        float dx = Math.abs(ev.getX() - downX);
        float dy = Math.abs(ev.getY() - downY);
        return dx < dp(14) && dy < dp(14) && System.currentTimeMillis() - downTime < 420;
    }

    private void handleTap(float x) {
        int w = readerRoot.getWidth();
        if (w <= 0) return;
        if (barVisible) {
            if (x > w / 3f && x < w * 2 / 3f) {
                setBarVisible(false);
            } else if (x >= w * 2 / 3f) {
                nextPage();
            } else {
                prevPage();
            }
            return;
        }
        if (x < w / 3f) {
            prevPage();
        } else if (x > w * 2 / 3f) {
            nextPage();
        } else {
            if (speaking && style.autoSpeak) {
                // 自动朗读时工具栏留在隐藏状态，避免误触把朗读打断
                return;
            }
            setBarVisible(true);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (style.volumeTurn && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                nextPage();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                prevPage();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------- 目录 / 书签 / 搜索

    private void showToc() {
        if (book == null || engine == null) return;
        final List<String> titles = new ArrayList<>();
        final List<Integer> pages = new ArrayList<>();
        for (int ci = 0; ci < book.chapters.size(); ci++) {
            int para = book.chapterStartBlock(ci);
            int pg = engine.pageOfPara(para);
            pages.add(pg);
            int curChapter = engine.pages().isEmpty() ? -1
                    : engine.pages().get(Math.max(0, Math.min(pageIndex, engine.pages().size() - 1))).chapterIndex;
            String mark = ci == curChapter ? "　当前章" : "";
            titles.add(book.chapterTitle(ci) + "　（第 " + (pg + 1) + " 页）" + mark);
        }
        if (titles.isEmpty()) {
            announce("这本书没有目录信息");
            return;
        }
        ListView lv = new ListView(this);
        lv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, titles));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("目录，共 " + titles.size() + " 章")
                .setView(lv)
                .setNegativeButton("关闭", null)
                .create();
        lv.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            goPage(pages.get(position));
            announce(book.chapterTitle(position));
        });
        dialog.show();
    }

    private void showBookmarks() {
        if (book == null || engine == null) return;
        final List<RecentStore.Bookmark> marks = recent.bookmarks(sourceUri.toString());
        final List<String> lines = new ArrayList<>();
        for (RecentStore.Bookmark b : marks) {
            lines.add((b.chapter.isEmpty() ? "第 " + (b.page + 1) + " 页" : b.chapter)
                    + (b.text.isEmpty() ? "" : "\n" + b.text));
        }
        ListView lv = new ListView(this);
        lv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, lines));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(marks.isEmpty() ? "还没有书签" : "书签，共 " + marks.size() + " 条")
                .setView(lv)
                .setNegativeButton("关闭", null)
                .setNeutralButton("批量删除", (d, w) -> bulkDeleteBookmarks(marks))
                .setPositiveButton("加书签", (d, w) -> addBookmarkAtCurrent());
        AlertDialog dialog = builder.create();
        lv.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            goPage(marks.get(position).page);
            announce("已跳到书签");
        });
        lv.setOnItemLongClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            new AlertDialog.Builder(this)
                    .setTitle("删除这条书签")
                    .setMessage("删除后书里的内容不会变")
                    .setPositiveButton("删除", (d2, w2) -> {
                        recent.removeBookmark(sourceUri.toString(), marks.get(position).para);
                        announce("已删除书签");
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
        dialog.show();
    }

    /** 书签批量删除：勾选要删的，一次删掉 */
    private void bulkDeleteBookmarks(final List<RecentStore.Bookmark> marks) {
        if (marks.isEmpty()) {
            announce("还没有书签");
            return;
        }
        final String[] labels = new String[marks.size()];
        for (int i = 0; i < marks.size(); i++) {
            RecentStore.Bookmark b = marks.get(i);
            labels[i] = (b.chapter.isEmpty() ? "第 " + (b.page + 1) + " 页" : b.chapter)
                    + (b.text.isEmpty() ? "" : "：" + b.text);
        }
        final boolean[] checked = new boolean[marks.size()];
        new AlertDialog.Builder(this)
                .setTitle("勾选要删除的书签")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("删除选中", (d, w) -> {
                    int n = 0;
                    for (int i = 0; i < marks.size(); i++) {
                        if (checked[i]) {
                            recent.removeBookmark(sourceUri.toString(), marks.get(i).para);
                            n++;
                        }
                    }
                    announce(n == 0 ? "没有勾选任何书签" : "已删除 " + n + " 条书签");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addBookmarkAtCurrent() {
        if (book == null || engine == null) return;
        LayoutEngine.Page page = engine.pages().get(pageIndex);
        String snippet = "";
        for (int i = page.startPara; i <= Math.min(page.endPara, engine.paraCount() - 1); i++) {
            Book.Block b = engine.para(i);
            if (!b.isImage() && b.text != null && !b.text.trim().isEmpty()) {
                snippet = b.text.trim();
                break;
            }
        }
        boolean added = recent.addBookmark(sourceUri.toString(), page.startPara, pageIndex,
                page.chapterTitle, snippet);
        announce(added ? "已添加书签" : "这一页已经有书签了");
    }

    private void showSearch() {
        if (book == null || engine == null) return;
        EditText input = new EditText(this);
        input.setHint("输入要搜索的文字");
        input.setContentDescription("输入要搜索的文字");
        new AlertDialog.Builder(this)
                .setTitle("在书里搜索")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> doSearch(input.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doSearch(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            announce("请输入要搜索的文字");
            return;
        }
        final String kw = keyword.trim();
        final List<String> hits = new ArrayList<>();
        final List<Integer> hitPages = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < engine.paraCount() && count < 200; i++) {
            Book.Block b = engine.para(i);
            if (b.isImage() || b.text == null) continue;
            int idx = b.text.indexOf(kw);
            if (idx < 0) continue;
            int from = Math.max(0, idx - 12);
            int to = Math.min(b.text.length(), idx + kw.length() + 12);
            String ctx = (from > 0 ? "…" : "") + b.text.substring(from, to) + (to < b.text.length() ? "…" : "");
            hits.add("第 " + (engine.pageOfPara(i) + 1) + " 页　" + ctx);
            hitPages.add(engine.pageOfPara(i));
            count++;
        }
        if (hits.isEmpty()) {
            announce("没有找到「" + kw + "」");
            return;
        }
        final List<String> list = hits;
        ListView lv = new ListView(this);
        lv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, list));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("找到 " + list.size() + " 处")
                .setView(lv)
                .setNegativeButton("关闭", null)
                .create();
        lv.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            goPage(hitPages.get(position));
        });
        dialog.show();
        announce("找到 " + list.size() + " 处，点一条可以跳过去");
    }

    // ---------------------------------------------------------------- 朗读

    private void startSpeak(int sentenceIndex, boolean cont) {
        if (sentences == null || sentences.isEmpty()) {
            if (cont && engine != null && pageIndex < engine.pages().size() - 1) {
                pageIndex++;
                renderPage(true);
                startSpeak(0, true);
                return;
            }
            announce("这一页没有可以朗读的文字");
            return;
        }
        if (!tts.isReady()) {
            pendingSpeak = sentenceIndex;
            pendingContinuous = cont;
            announce("语音引擎正在准备，准备好会自动开始");
            return;
        }
        continuous = cont;
        speaking = true;
        updateSpeakButton();
        tts.setSpeed(style.ttsSpeed);
        tts.speak(sentences, Math.max(0, sentenceIndex), style.ttsSpeed);
    }

    private void stopSpeak() {
        continuous = false;
        speaking = false;
        pendingSpeak = -1;
        tts.stop();
        updateSpeakButton();
    }

    private void onSpeakFinished() {
        speaking = false;
        updateSpeakButton();
        if (flowActive() && engine != null) {
            if (!continuous) return;
            if (flowEndPage < engine.pages().size() - 1) {
                int from = sentences.size();
                appendFlowBatch();
                if (from < sentences.size()) {
                    startSpeak(from, true);
                } else {
                    continuous = false;
                }
            } else {
                continuous = false;
                announce("这本书已经读到结尾了");
            }
            return;
        }
        if (continuous && engine != null && pageIndex < engine.pages().size() - 1) {
            pageIndex++;
            renderPage(true);
            startSpeak(0, true);
        } else {
            boolean was = continuous;
            continuous = false;
            speaking = false;
            updateSpeakButton();
            if (was) announce("这本书已经读到结尾了");
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyTheme();
        readerRoot.post(() -> {
            if (book != null && readerRoot.getWidth() > 0) buildLayout(true);
        });
    }

    @Override
    public void onBackPressed() {
        if (splashVisible) {
            hideSplash();
            return;
        }
        if (!barVisible && book != null) {
            setBarVisible(true);
            announce("工具栏已经出来。再按一次返回就退出这本书");
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (style.keepScreenOn) {
            readerRoot.setKeepScreenOn(true);
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        savePosition(true);
        if (speaking) {
            continuous = false;
            speaking = false;
            tts.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tts != null) tts.shutdown();
        if (imageStore != null) imageStore.clearMemory();
    }

    private void announce(String text) {
        if (text == null || readerRoot == null) return;
        readerRoot.announceForAccessibility(text);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}

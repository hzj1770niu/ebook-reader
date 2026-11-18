package com.zz.reader.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.zz.reader.pro.core.ImageStore;

import java.util.ArrayList;
import java.util.List;

/** 书库：选文件、最近阅读（含封面与进度） */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 2001;

    private ListView list;
    private TextView empty;
    private RecentStore store;
    private ImageStore imageStore;
    private final List<RecentStore.Item> items = new ArrayList<>();
    private BookAdapter adapter;
    private CategoryStore cats;
    private LinearLayout actionBar;
    private String filterCat = "";
    private boolean selecting = false;
    private final java.util.LinkedHashSet<String> sel = new java.util.LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        com.zz.reader.pro.core.AppCtx.init(this);
        store = new RecentStore(this);
        imageStore = new ImageStore(this);

        list = findViewById(R.id.listBooks);
        empty = findViewById(R.id.tvEmpty);
        Button open = findViewById(R.id.btnOpen);

        open.setOnClickListener(v -> pickFile());

        findViewById(R.id.btnSettings).setOnClickListener(v -> showSettings());

        cats = new CategoryStore(this);
        actionBar = findViewById(R.id.actionBar);
        findViewById(R.id.btnCategory).setOnClickListener(v -> pickCategory());
        findViewById(R.id.btnMultiDelete).setOnClickListener(v -> confirmDeleteSelected());
        findViewById(R.id.btnMultiMove).setOnClickListener(v -> pickMoveTarget());
        findViewById(R.id.btnMultiAll).setOnClickListener(v -> selectAll());
        findViewById(R.id.btnMultiCancel).setOnClickListener(v -> exitMulti());

        adapter = new BookAdapter();
        list.setAdapter(adapter);
        reload();
        boolean firstRun = showFirstRunNotice();
        if (!firstRun) {
            // 等界面画出来再查，避免拖慢启动
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> UpdateChecker.autoCheck(this), 1500);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data != null) openBook(data, 0);
    }

    private void reload() {
        items.clear();
        for (RecentStore.Item it : store.list()) {
            if (filterCat.isEmpty() || cats.categoryOf(it.uri).equals(filterCat)) items.add(it);
        }
        adapter.notifyDataSetChanged();
        boolean isEmpty = items.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/epub+zip",
                "application/x-mobipocket-ebook",
                "application/octet-stream",
                "text/plain",
                "text/html",
                "application/rtf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/x-fictionbook+xml",
                "application/pdf"
        });
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "这台手机没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    private void openBook(Uri uri, int startPara) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.setData(uri);
        i.putExtra("uri", uri);
        i.putExtra(ReaderActivity.EXTRA_START_PARA, startPara);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 书库设置：退出软件挪到这里，另外放版本和支持格式的说明 */
    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad, pad, dp(6));

        TextView info = new TextView(this);
        info.setTextSize(16);
        info.setText("电子书阅读器 " + BuildConfig.VERSION_NAME + "\n\n"
                + "支持格式：EPUB、Kindle 的 MOBI / AZW3 / AZW / PRC、PDF（文字版、扫描版）、"
                + "TXT、FB2、HTML、RTF、DOCX。\n\n"
                + "阅读进度、书签、设置都保存在本机，退出不会丢。");
        box.addView(info);

        TextView ocrNote = new TextView(this);
        ocrNote.setTextSize(14);
        ocrNote.setFocusable(true);
        ocrNote.setPadding(0, dp(10), 0, dp(4));
        ocrNote.setText("扫描版 PDF 和图片里的字，是在手机本地一个字一个字认出来的，不联网、不上传。"
                + "认字用的是开源的 PP-OCRv5 模型（PaddleOCR 第五版，中文公开模型里准确率靠前的一套）。"
                + "为了认得更准，另外加了几道处理：整页颠倒的先转正、倾斜的行先摆平、"
                + "两栏三栏按一栏读完再读另一栏、整页放大到 1280 像素再找字，小字也能认出来。");
        box.addView(ocrNote);

        Button about = new Button(this);
        about.setText("关于软件");
        about.setMinHeight(dp(52));
        about.setOnClickListener(v -> showAbout());
        box.addView(about);

        Button check = new Button(this);
        check.setText("检查更新");
        check.setMinHeight(dp(52));
        check.setContentDescription("检查更新，联网查有没有新版本");
        check.setOnClickListener(v -> UpdateChecker.check(this));
        box.addView(check);

        Button ocrInfo = new Button(this);
        ocrInfo.setText("识别说明");
        ocrInfo.setMinHeight(dp(52));
        ocrInfo.setContentDescription("识别说明：扫描版 PDF 用的是什么模型，打开书时后台在做什么");
        ocrInfo.setOnClickListener(v -> showOcrInfo());
        box.addView(ocrInfo);

        Button log = new Button(this);
        log.setText("更新日志");
        log.setMinHeight(dp(52));
        log.setOnClickListener(v -> showChangelog());
        box.addView(log);

        Button quit = new Button(this);
        quit.setText("退出软件");
        quit.setMinHeight(dp(52));
        quit.setContentDescription("退出软件，会关闭电子书阅读器");
        quit.setOnClickListener(v -> confirmQuit());
        box.addView(quit);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 第一次运行弹一次使用声明；清除数据后再运行会再弹 */
    private boolean showFirstRunNotice() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("app_notice", MODE_PRIVATE);
            if (sp.getBoolean("shown", false)) return false;
            boolean oldAck = getSharedPreferences("reader_prefs_pro", MODE_PRIVATE)
                    .getBoolean("first_run_ack", false);
            if (oldAck) {
                sp.edit().putBoolean("shown", true).apply();
                return false;
            }
            sp.edit().putBoolean("shown", true).apply();
        } catch (Throwable ignore) {
            return false;
        }
        new AlertDialog.Builder(this)
                .setTitle("使用声明")
                .setMessage("本应用由【站在树上的牛】个人制作使用，禁止出售、向他人收取任何费用，"
                        + "出于方便使用的目的对外分发。\n\n"
                        + "本应用联网仅用于检查软件更新，所有数据仅保存在本机，不会上传到任何服务器。")
                .setPositiveButton("确定", null)
                .setCancelable(true)
                .show();
        return true;
    }

    /** 关于软件：作者与两条声明 */
    private void showAbout() {
        String text = "电子书阅读器 " + BuildConfig.VERSION_NAME + "\n\n"
                + "本应用由【站在树上的牛】个人制作使用，禁止出售、向他人收取任何费用，"
                + "出于方便使用的目的对外分发。\n\n"
                + "本应用联网仅用于检查软件更新，所有数据仅保存在本机，不会上传到任何服务器。\n\n"
                + "支持格式：EPUB、Kindle 的 MOBI / AZW3 / AZW / PRC、PDF（文字版、扫描版）、"
                + "TXT、FB2、HTML、RTF、DOCX。\n\n"
                + "扫描版 PDF 的识别在手机本地完成，用的是开源 PP-OCRv5 模型（PaddleOCR 第五版）。";
        new AlertDialog.Builder(this)
                .setTitle("关于软件")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 识别说明：用的是什么模型、打开书时后台干什么 */
    private void showOcrInfo() {
        String text = "扫描版 PDF 的文字识别，在手机本地完成，不联网、不上传。\n\n"
                + "用的是开源模型 PP-OCRv5，也就是 PaddleOCR 的第五代，一共三个：\n"
                + "一、方向分类：判断整页是不是颠倒或横躺，颠倒的先转正再识别。\n"
                + "二、文字检测：把整页里每一行文字框出来，倾斜的行先摆正再切图。\n"
                + "三、文字识别：逐行转成文字，按左栏读完再读右栏的顺序拼回正文。\n\n"
                + "为了认得更准，检测时把整页放大到长边 1280 再算；两栏三栏自动判断栏数；"
                + "文字层本身坏掉的页（字体没有对应关系的）也会改用识别结果，并用识别结果校对。\n\n"
                + "打开一本书的时候：\n"
                + "一、先立刻把书打开，只读目录和已有文字，不在这里做识别，书是秒开的。\n"
                + "二、扫描页先放一句“第 N 页正在本地识别”，这些页现在就能翻，不会卡住。\n"
                + "三、屏幕亮着的时候，后台一页一页往下识别，手机不卡的时候才做，优先级给得很低。\n"
                + "四、每认完一页就把文字填回那一页，你翻回去看就是识别后的正文，位置不会跳。\n"
                + "五、有语音进度：“打开好了，正在后台识别，共 N 页”“第 N 页识别好了”“都做完了”。\n"
                + "六、识别结果按页存下来，下次打开同一本书不用重认。";
        new AlertDialog.Builder(this)
                .setTitle("识别说明")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 更新日志：从 4.0（正式更名为"电子书阅读器"）开始记 */
    private void showChangelog() {
        String text = "4.0　首个正式版\n"
                + "软件名改为电子书阅读器，换新图标；加入 PDF 支持，文字版直接读文字层，扫描版在手机本地识别。\n\n"
                + "4.1\n"
                + "识别模型换成 PP-OCRv5；加入方向分类，颠倒的页先转正；倾斜的文字行先摆正再识别；"
                + "两栏三栏按左栏读完再读右栏；文字层坏掉的页自动改用识别结果。\n\n"
                + "4.2\n"
                + "修扫描版 PDF 打不开、翻不了页；修每页页号错位；新增文字层校对开关。\n\n"
                + "4.3\n"
                + "打开与识别彻底分开：书秒开，识别在后台一页一页做，做完自动替换进正文；"
                + "识别进度用语音提示，失败会说出原因。\n\n"
                + "4.8\n"
                + "启动时自动检查一次更新，发现新版本才弹窗；设置里加开关，可以关掉自动检查。\n\n"
                + "5.1\n"
                + "识别整页放大到 1280 像素再找字，小字更准；设置里加了一段识别的说明，写清用的是开源 PP-OCRv5。\n\n"
                + "5.1\n"
                + "设置里加识别说明，讲清用的是开源 PP-OCRv5 模型，以及打开书时后台在做什么；"
                + "检测时把整页放大到长边 1280 再算，小字认得更准。\n\n"
                + "5.0\n"
                + "书库支持长按进入多选，可批量删除、批量移到分类、新建分类；书签可以批量删除；"
                + "首次使用声明只弹一次，按钮改为“确定”。\n\n"
                + "4.9\n"
                + "更新清单改用新的地址并加了不走缓存的请求头，刚发布的新版本可以马上被检查到。\n\n"
                + "4.8\n"
                + "启动时自动检查一次更新（六小时内不重复查），发现新版本弹窗说明；"
                + "设置里新增“启动时自动检查更新”开关，关掉就彻底不联网查。\n\n"
                + "4.7\n"
                + "检查更新改为读取发布仓库里的版本清单，能在软件里直接看到版本号、更新说明和下载地址。\n\n"
                + "4.6\n"
                + "第一次打开软件时弹一次作者与声明，读屏会念出来。\n\n"
                + "5.1\n"
                + "识别整页放大到 1280 像素再找字，小字更准；设置里加了一段识别的说明，写清用的是开源 PP-OCRv5。\n\n"
                + "5.1\n"
                + "设置里加识别说明，讲清用的是开源 PP-OCRv5 模型，以及打开书时后台在做什么；"
                + "检测时把整页放大到长边 1280 再算，小字认得更准。\n\n"
                + "5.0\n"
                + "书库支持长按进入多选，可批量删除、批量移到分类、新建分类；书签可以批量删除；"
                + "首次使用声明只弹一次，按钮改为“确定”。\n\n"
                + "4.9\n"
                + "更新清单改用新的地址并加了不走缓存的请求头，刚发布的新版本可以马上被检查到。\n\n"
                + "4.8\n"
                + "启动时自动检查一次更新，发现新版本才弹窗；设置里加开关，可以关掉自动检查。\n\n"
                + "5.1\n"
                + "识别整页放大到 1280 像素再找字，小字更准；设置里加了一段识别的说明，写清用的是开源 PP-OCRv5。\n\n"
                + "5.1\n"
                + "设置里加识别说明，讲清用的是开源 PP-OCRv5 模型，以及打开书时后台在做什么；"
                + "检测时把整页放大到长边 1280 再算，小字认得更准。\n\n"
                + "5.0\n"
                + "书库支持长按进入多选，可批量删除、批量移到分类、新建分类；书签可以批量删除；"
                + "首次使用声明只弹一次，按钮改为“确定”。\n\n"
                + "4.9\n"
                + "更新清单改用新的地址并加了不走缓存的请求头，刚发布的新版本可以马上被检查到。\n\n"
                + "4.8\n"
                + "启动时自动检查一次更新（六小时内不重复查），发现新版本弹窗说明；"
                + "设置里新增“启动时自动检查更新”开关，关掉就彻底不联网查。\n\n"
                + "4.7\n"
                + "检查更新改为读取发布仓库里的版本清单，能在软件里直接看到版本号、更新说明和下载地址。\n\n"
                + "4.6\n"
                + "第一次运行弹一次使用声明，清除数据后再运行会再弹一次。\n\n"
                + "4.5\n"
                + "设置里加检查更新与关于软件；关于里写明作者与分发、联网的两条声明；"
                + "检查更新只查 GitHub 的发布清单，不收集任何数据。\n\n"
                + "4.4\n"
                + "退出软件从书库底部挪进设置；Kindle 的 MOBI / AZW3 改为按书里自带的目录分章，"
                + "目录读不到时才退回按“第 x 章”切；新增这份更新日志。";
        new AlertDialog.Builder(this)
                .setTitle("更新日志")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 退出软件：先确认一次，确认后结束所有界面并结束进程 */
    private void confirmQuit() {
        new AlertDialog.Builder(this)
                .setTitle("退出电子书阅读器")
                .setMessage("确定退出电子书阅读器吗？阅读进度已经保存。")
                .setPositiveButton("退出", (d, w) -> {
                    finishAffinity();
                    android.os.Process.killProcess(android.os.Process.myPid());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemove(RecentStore.Item it) {
        new AlertDialog.Builder(this)
                .setTitle("移除这本书记录")
                .setMessage("只是从最近列表里移除，手机里的原文件不会被删。要移除《" + it.title + "》吗？")
                .setPositiveButton("移除", (d, w) -> {
                    store.remove(it.uri);
                    reload();
                    findViewById(R.id.tvAppTitle).announceForAccessibility("已移除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            openBook(uri, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    // ---------------------------------------------------------------- 分类与多选

    private void updateCategoryButton() {
        Button b = findViewById(R.id.btnCategory);
        b.setText(filterCat.isEmpty() ? "全部分类" : "分类：" + filterCat);
        b.setContentDescription(filterCat.isEmpty() ? "当前显示全部书籍，点开可以按分类筛选"
                : "当前只显示分类 " + filterCat + "，点开可以换");
    }

    private void pickCategory() {
        final List<String> cs = cats.list();
        String[] opts = new String[cs.size() + 3];
        opts[0] = "全部书籍";
        for (int i = 0; i < cs.size(); i++) opts[i + 1] = "分类：" + cs.get(i);
        opts[cs.size() + 1] = "新建分类…";
        opts[cs.size() + 2] = "管理分类…";
        new AlertDialog.Builder(this)
                .setTitle("分类")
                .setItems(opts, (d, w) -> {
                    if (w == 0) {
                        filterCat = "";
                        updateCategoryButton();
                        reload();
                        Toast.makeText(this, "显示全部书籍", Toast.LENGTH_SHORT).show();
                    } else if (w == opts.length - 2) {
                        askNewCategory(null);
                    } else if (w == opts.length - 1) {
                        manageCategories();
                    } else {
                        filterCat = cs.get(w - 1);
                        updateCategoryButton();
                        reload();
                        Toast.makeText(this, items.isEmpty() ? "分类 " + filterCat + " 里还没有书"
                                : "分类 " + filterCat + "，共 " + items.size() + " 本",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 新建分类；建完如果有待移动的选中书就移过去 */
    private void askNewCategory(Runnable after) {
        EditText et = new EditText(this);
        et.setHint("分类名称");
        et.setContentDescription("输入新分类的名称");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(6), pad, 0);
        box.addView(et);
        new AlertDialog.Builder(this)
                .setTitle("新建分类")
                .setView(box)
                .setPositiveButton("创建", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称是空的，没建", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    cats.add(name);
                    if (selecting && !sel.isEmpty()) {
                        final int moved = sel.size();
                        for (String uri : sel) cats.setCategory(uri, name);
                        exitMulti();
                        reload();
                        Toast.makeText(this, "已新建分类 " + name + "，并把 " + moved + " 本移了进去",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(this, "已新建分类 " + name, Toast.LENGTH_SHORT).show();
                    if (after != null) after.run();
                    else {
                        filterCat = name;
                        updateCategoryButton();
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void manageCategories() {
        final List<String> cs = cats.list();
        if (cs.isEmpty()) {
            Toast.makeText(this, "还没有分类，先新建一个", Toast.LENGTH_SHORT).show();
            askNewCategory(null);
            return;
        }
        String[] opts = new String[cs.size()];
        for (int i = 0; i < cs.size(); i++) opts[i] = cs.get(i);
        new AlertDialog.Builder(this)
                .setTitle("管理分类，点一个可以删除")
                .setItems(opts, (d, w) -> {
                    final String name = cs.get(w);
                    new AlertDialog.Builder(this)
                            .setTitle("删除分类 " + name)
                            .setMessage("分类会删掉，里面的书不会被删，会回到未分类。")
                            .setPositiveButton("删除", (dd, ww) -> {
                                cats.removeCategory(name);
                                if (filterCat.equals(name)) filterCat = "";
                                updateCategoryButton();
                                reload();
                                Toast.makeText(this, "已删除分类 " + name, Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void enterMulti(String uri) {
        selecting = true;
        sel.clear();
        sel.add(uri);
        actionBar.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已进入多选，选了 1 本。点按可以加选或取消，下边有删除和分类",
                Toast.LENGTH_SHORT).show();
    }

    private void toggleSelect(String uri) {
        if (sel.contains(uri)) sel.remove(uri);
        else sel.add(uri);
        if (sel.isEmpty()) {
            exitMulti();
            return;
        }
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已选 " + sel.size() + " 本", Toast.LENGTH_SHORT).show();
    }

    private void exitMulti() {
        selecting = false;
        sel.clear();
        actionBar.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已退出多选", Toast.LENGTH_SHORT).show();
    }

    private void selectAll() {
        sel.clear();
        for (RecentStore.Item it : items) sel.add(it.uri);
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "已全选 " + sel.size() + " 本", Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteSelected() {
        if (sel.isEmpty()) {
            Toast.makeText(this, "还没有选中书", Toast.LENGTH_SHORT).show();
            return;
        }
        final int n = sel.size();
        new AlertDialog.Builder(this)
                .setTitle("删除选中的 " + n + " 本")
                .setMessage("只是从书架移除记录，手机里的书文件不会被删。")
                .setPositiveButton("删除", (d, w) -> {
                    for (String uri : sel) {
                        store.remove(uri);
                        cats.setCategory(uri, "");
                    }
                    exitMulti();
                    reload();
                    Toast.makeText(this, "已从书架移除 " + n + " 本", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void pickMoveTarget() {
        if (sel.isEmpty()) {
            Toast.makeText(this, "还没有选中书", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> cs = cats.list();
        String[] opts = new String[cs.size() + 2];
        for (int i = 0; i < cs.size(); i++) opts[i] = cs.get(i);
        opts[cs.size()] = "新建分类…";
        opts[cs.size() + 1] = "移到未分类";
        new AlertDialog.Builder(this)
                .setTitle("把选中的 " + sel.size() + " 本移到")
                .setItems(opts, (d, w) -> {
                    if (w == opts.length - 2) {
                        askNewCategory(null);
                        return;
                    }
                    if (w == opts.length - 1) {
                        for (String uri : sel) cats.setCategory(uri, "");
                        exitMulti();
                        reload();
                        Toast.makeText(this, "已移到未分类", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final String name = cs.get(w);
                    for (String uri : sel) cats.setCategory(uri, name);
                    exitMulti();
                    reload();
                    Toast.makeText(this, "已移到分类 " + name + "，共 " + cs.size() + " 个分类可用",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class BookAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView iv;
            TextView tv;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                iv = (ImageView) row.getChildAt(0);
                tv = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(dp(10), dp(10), dp(10), dp(10));
                iv = new ImageView(MainActivity.this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(76));
                lp.rightMargin = dp(14);
                iv.setLayoutParams(lp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setFocusable(false);
                iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                row.addView(iv);
                tv = new TextView(MainActivity.this);
                tv.setTextSize(17);
                tv.setFocusable(false);
                tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tv);
            }
            RecentStore.Item it = items.get(position);
            String cat = cats.categoryOf(it.uri);
            String line2 = it.format + (it.percent > 0 ? "　已读 " + it.percent + "%" : "")
                    + (cat.isEmpty() ? "" : "　分类：" + cat);
            boolean picked = selecting && sel.contains(it.uri);
            tv.setText((picked ? "已选中　" : "") + it.title + "\n" + line2);

            Bitmap bmp = null;
            if (it.cover != null && !it.cover.isEmpty()) {
                bmp = imageStore.load(it.cover, dp(56) * 2, dp(76) * 2);
            }
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                iv.setVisibility(View.VISIBLE);
            } else {
                iv.setImageDrawable(null);
                iv.setVisibility(View.GONE);
            }
            row.setClickable(true);
            row.setOnClickListener(v -> {
                if (selecting) {
                    toggleSelect(it.uri);
                } else {
                    openBook(Uri.parse(it.uri), it.para);
                }
            });
            row.setOnLongClickListener(v -> {
                if (selecting) {
                    toggleSelect(it.uri);
                } else {
                    enterMulti(it.uri);
                }
                return true;
            });
            row.setContentDescription(it.title + "，" + it.format + " 格式"
                    + (it.percent > 0 ? "，已读 " + it.percent + "%" : "")
                    + (cat.isEmpty() ? "" : "，分类 " + cat)
                    + (picked ? "，已选中" : "")
                    + (selecting ? "。点按选择或取消" : "。长按可以多选删除或移分类"));
            return row;
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}

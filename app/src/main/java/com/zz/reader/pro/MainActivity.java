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
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
        findViewById(R.id.btnSort).setOnClickListener(v -> pickSort());
        findViewById(R.id.btnRemote).setOnClickListener(v -> showRemote());
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
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) data = stream;
        }
        if (data != null) openBook(data, 0);
    }

    /** 书库里的即时反馈，交给读屏念一句 */
    private void announce(String text) {
        if (text == null) return;
        try {
            getWindow().getDecorView().announceForAccessibility(text);
        } catch (Throwable ignore) {
        }
    }

    private void reload() {
        items.clear();
        for (RecentStore.Item it : store.list()) {
            if (filterCat.isEmpty() || cats.categoryOf(it.uri).equals(filterCat)) items.add(it);
        }
        final int mode = new Prefs(this).sortMode();
        final java.text.Collator collator = java.text.Collator.getInstance(java.util.Locale.CHINA);
        collator.setStrength(java.text.Collator.PRIMARY);
        if (mode == 1) {
            java.util.Collections.sort(items, (a, b) -> collator.compare(a.title, b.title));
        } else if (mode == 2) {
            java.util.Collections.sort(items, (a, b) -> Long.compare(a.time, b.time));
        } else {
            java.util.Collections.sort(items, (a, b) -> Long.compare(b.time, a.time));
        }
        updateSortButton();
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

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(19);
        title.getPaint().setFakeBoldText(true);
        title.setFocusable(true);
        box.addView(title);
        final Button back = new Button(this);
        back.setText("← 返回");
        back.setMinHeight(dp(48));
        box.addView(back);

        Button about = new Button(this);
        about.setText("关于软件");
        about.setMinHeight(dp(52));
        about.setOnClickListener(v -> showAbout());
        box.addView(about);

        final Prefs prefs = new Prefs(this);

        CheckBox auto = new CheckBox(this);
        auto.setText("启动时自动检查更新");
        auto.setChecked(prefs.autoCheckUpdate());
        auto.setMinHeight(dp(52));
        auto.setOnCheckedChangeListener((b, on) -> {
            prefs.setAutoCheckUpdate(on);
            Toast.makeText(this, on ? "以后启动会自动检查更新" : "以后启动不再自动检查更新",
                    Toast.LENGTH_SHORT).show();
        });
        box.addView(auto);

        CheckBox addLib = new CheckBox(this);
        addLib.setText("打开文档时加入书库");
        addLib.setChecked(prefs.addToLibrary());
        addLib.setMinHeight(dp(52));
        addLib.setOnCheckedChangeListener((b, on) -> {
            prefs.setAddToLibrary(on);
            Toast.makeText(this, on ? "打开文档会加入书库" : "打开文档只当查看器，不加入书库",
                    Toast.LENGTH_SHORT).show();
        });
        box.addView(addLib);

        TextView addLibNote = new TextView(this);
        addLibNote.setTextSize(13);
        addLibNote.setText("关掉以后，从文件管理器打开文档只当查看器用，不写进书库，也不记阅读进度。");
        addLibNote.setFocusable(true);
        addLibNote.setPadding(0, 0, 0, dp(8));
        box.addView(addLibNote);

        Button clearCache = new Button(this);
        clearCache.setText("清除缓存");
        clearCache.setMinHeight(dp(52));
        clearCache.setOnClickListener(v -> confirmClearCache());
        box.addView(clearCache);

        TextView clearNote = new TextView(this);
        clearNote.setTextSize(13);
        clearNote.setText("从网络书库读的书、从文件管理器打开时留下的副本都放在缓存里，清掉不影响书库里的书。");
        clearNote.setFocusable(true);
        clearNote.setPadding(0, 0, 0, dp(8));
        box.addView(clearNote);

        Button check = new Button(this);
        check.setText("检查更新");
        check.setMinHeight(dp(52));
        check.setOnClickListener(v -> UpdateChecker.check(this));
        box.addView(check);

        Button ocrInfo = new Button(this);
        ocrInfo.setText("软件简介");
        ocrInfo.setMinHeight(dp(52));
        ocrInfo.setOnClickListener(v -> showIntro());
        box.addView(ocrInfo);

        Button log = new Button(this);
        log.setText("更新日志");
        log.setMinHeight(dp(52));
        log.setOnClickListener(v -> showChangelog());
        box.addView(log);

        Button quit = new Button(this);
        quit.setText("退出软件");
        quit.setMinHeight(dp(52));
        quit.setOnClickListener(v -> confirmQuit());
        box.addView(quit);

        AlertDialog settingsDialog = new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("关闭", null)
                .create();
        back.setOnClickListener(v -> settingsDialog.dismiss());
        settingsDialog.show();
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

    /** 只留当前这一版的更新日志 */
    private static final String CHANGELOG_NOW = "6.1\n"
            + "PDF 正文里的插图现在能显示：解析时抽出每页内嵌图片，按页顺序插在文字后面\n"
            + "插图是一个独立焦点，念“第 N 页插图”，换页、朗读、段落导航都不受影响\n"
            + "整页扫描图不当插图，避免每页一张大图\n"
            + "顺便确认：EPUB、HTML、FB2、DOCX、MOBI、AZW3 的插图一直是正常显示的";

    /** 关于软件：作者与两条声明 */
    private void showAbout() {
        String text = "电子书阅读器 " + BuildConfig.VERSION_NAME + "\n\n"
                + "本应用由【站在树上的牛】个人制作使用，禁止出售、向他人收取任何费用，"
                + "出于方便使用的目的对外分发。\n\n"
                + "本应用联网仅用于检查软件更新，所有数据仅保存在本机，不会上传到任何服务器。";
        new AlertDialog.Builder(this)
                .setTitle("关于软件")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 软件简介：支持格式、怎么读、怎么听、识别与网络书库，都收在这一处 */
    private void showIntro() {
        String text = "支持格式\n"
                + "EPUB、Kindle 的 MOBI / AZW3 / AZW / PRC、PDF（文字版与扫描版）、"
                + "TXT、FB2、HTML、RTF、DOCX。TXT 自动识别编码，不乱码。\n\n"
                + "阅读\n"
                + "左右点按可以翻页，也可以单指滑动、双指滑动；分页与连续滚动在阅读界面的设置里切。\n\n"
                + "朗读\n"
                + "可以用读屏的连续朗读，也可以调系统语音引擎，阅读工具栏里有朗读按钮。\n\n"
                + "扫描版 PDF\n"
                + "文字在手机本地识别，不联网、不上传，用开源的 PP-OCRv5 模型。"
                + "颠倒的页先转正，倾斜的行先摆平，两栏三栏按栏拼回。"
                + "打开书时不做识别，书先秒开，后台一页一页补文字，结果按页存下来，下次不用重认。\n\n"
                + "网络书库\n"
                + "支持 SMB 与标准 WebDAV。读回来的文件放在缓存里，设置里可以清除缓存。";
        new AlertDialog.Builder(this)
                .setTitle("软件简介")
                .setMessage(text)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 更新日志：从 4.0（正式更名为"电子书阅读器"）开始记 */
    private void showChangelog() {
        UpdateChecker.showChangelog(this);
    }

    /** 退出软件：界面先撤掉回桌面，后台再释放识别占的内存，最后结束进程 */
    private void quitNow() {
        for (android.app.Dialog dlg : openDialogs) {
            try { dlg.dismiss(); } catch (Throwable ignore) {}
        }
        openDialogs.clear();
        finishAffinity();
        new Thread(() -> {
            try { Thread.sleep(250); } catch (InterruptedException ignore) {}
            try { com.zz.reader.pro.core.OcrEngine.shutdown(); } catch (Throwable ignore) {}
            android.os.Process.killProcess(android.os.Process.myPid());
        }, "quit").start();
    }

    /** 退出软件：先确认一次，确认后结束所有界面并结束进程 */
    private final java.util.List<android.app.Dialog> openDialogs = new java.util.ArrayList<>();

    private void confirmQuit() {
        AlertDialog quitDialog = new AlertDialog.Builder(this)
                .setTitle("退出电子书阅读器")
                .setMessage("确定退出电子书阅读器吗？阅读进度已经保存。")
                .setPositiveButton("退出", (d, w) -> quitNow())
                .setNegativeButton("取消", null)
                .create();
        openDialogs.add(quitDialog);
        quitDialog.show();
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

    private void updateSortButton() {
        Button b = findViewById(R.id.btnSort);
        int mode = new Prefs(this).sortMode();
        b.setText(mode == 1 ? "排序：名称" : (mode == 2 ? "排序：加入" : "排序：最近"));
    }

    private void pickSort() {
        String[] opts = {"按最近打开", "按名称", "按加入先后"};
        int cur = new Prefs(this).sortMode();
        new AlertDialog.Builder(this)
                .setTitle("排序方式")
                .setSingleChoiceItems(opts, cur, (d, w) -> {
                    new Prefs(this).setSortMode(w);
                    d.dismiss();
                    reload();
                    Toast.makeText(this, "已改为" + opts[w], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 网络书库

    private void showRemote() {
        final List<RemoteStore.Server> servers = new RemoteStore(this).list();
        if (servers.isEmpty()) {
            addServer();
            return;
        }
        String[] labels = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) labels[i] = servers.get(i).label();
        new AlertDialog.Builder(this)
                .setTitle("网络书库")
                .setItems(labels, (d, w) -> openServer(servers.get(w)))
                .setNeutralButton("添加", (d, w) -> addServer())
                .setNegativeButton("删除", (d, w) -> removeServer(servers))
                .setPositiveButton("关闭", null)
                .show();
    }

    private void removeServer(final List<RemoteStore.Server> servers) {
        String[] labels = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) labels[i] = servers.get(i).label();
        new AlertDialog.Builder(this)
                .setTitle("删除哪个")
                .setItems(labels, (d, w) -> {
                    new RemoteStore(this).remove(w);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openServer(RemoteStore.Server s) {
        Intent i = new Intent(this, RemoteBrowseActivity.class);
        i.putExtra("type", s.type);
        i.putExtra("host", s.host);
        i.putExtra("user", s.user);
        i.putExtra("pass", s.pass);
        startActivity(i);
    }

    private void addServer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, dp(4));

        final RadioGroup rg = new RadioGroup(this);
        RadioButton smb = new RadioButton(this);
        smb.setId(1001);
        smb.setText("SMB（Windows 共享、NAS）");
        RadioButton dav = new RadioButton(this);
        dav.setId(1002);
        dav.setText("WebDAV");
        rg.addView(smb);
        rg.addView(dav);
        rg.check(1001);
        box.addView(rg);

        final EditText host = new EditText(this);
        host.setHint("SMB：192.168.1.10/共享名　WebDAV：https://域名/目录");
        host.setSingleLine(false);
        box.addView(host);

        final EditText user = new EditText(this);
        user.setHint("用户名，可留空");
        user.setSingleLine(true);
        box.addView(user);

        final EditText pass = new EditText(this);
        pass.setHint("密码，可留空");
        pass.setSingleLine(true);
        pass.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(pass);

        TextView tip = new TextView(this);
        tip.setTextSize(13);
        tip.setText("地址按服务器本来的样子填。SMB 填到共享名，WebDAV 填到目录，结尾的斜杠可加可不加。");
        tip.setFocusable(true);
        box.addView(tip);

        new AlertDialog.Builder(this)
                .setTitle("添加服务器")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String h = host.getText().toString().trim();
                    if (h.isEmpty()) {
                        Toast.makeText(this, "地址没填", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    RemoteStore.Server s = new RemoteStore.Server();
                    s.type = rg.getCheckedRadioButtonId() == 1002 ? 1 : 0;
                    s.host = h;
                    s.user = user.getText().toString().trim();
                    s.pass = pass.getText().toString();
                    new RemoteStore(this).add(s);
                    openServer(s);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearCache() {
        final long size = CacheStore.size(this);
        new AlertDialog.Builder(this)
                .setTitle("清除缓存")
                .setMessage("现在缓存占 " + CacheStore.human(size) + "。清掉以后网络书库里的书下次打开要重新下载，书库里记录的不会丢。")
                .setPositiveButton("清除", (d, w) -> {
                    long freed = CacheStore.clear(this);
                    Toast.makeText(this, "已清除 " + CacheStore.human(freed), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------------------------------------------------------- 分类与多选

    private void updateCategoryButton() {
        Button b = findViewById(R.id.btnCategory);
        b.setText(filterCat.isEmpty() ? "全部分类" : "分类：" + filterCat);
        b.setContentDescription(filterCat.isEmpty() ? "分类" : "分类 " + filterCat);
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

    /** 单本移分类，给转子操作用 */
    private void moveOne(final RecentStore.Item it) {
        final List<String> cs = cats.list();
        String[] opts = new String[cs.size() + 2];
        for (int i = 0; i < cs.size(); i++) opts[i] = cs.get(i);
        opts[cs.size()] = "新建分类…";
        opts[cs.size() + 1] = "移到未分类";
        new AlertDialog.Builder(this)
                .setTitle("把《" + it.title + "》移到")
                .setItems(opts, (d, w) -> {
                    if (w == opts.length - 2) {
                        askNewCategory(null);
                        return;
                    }
                    if (w == opts.length - 1) {
                        cats.setCategory(it.uri, "");
                        reload();
                        Toast.makeText(this, "已移到未分类", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    cats.setCategory(it.uri, cs.get(w));
                    reload();
                    Toast.makeText(this, "已移到分类 " + cs.get(w), Toast.LENGTH_SHORT).show();
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
            final RecentStore.Item rowItem = it;
            A11y.attach(row, null,
                    A11y.act(A11y.MOVE_CATEGORY, "移到分类", () -> moveOne(rowItem)),
                    A11y.act(A11y.DELETE_BOOK, "从书库删除", () -> confirmRemove(rowItem)));

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
                    + (picked ? "，已选中" : ""));
            return row;
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}

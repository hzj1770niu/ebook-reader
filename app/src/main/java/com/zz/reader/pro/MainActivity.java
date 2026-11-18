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

        adapter = new BookAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            RecentStore.Item it = items.get(position);
            openBook(Uri.parse(it.uri), it.para);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmRemove(items.get(position));
            return true;
        });

        reload();
        showFirstRunNotice();
        handleIntent(getIntent());
        showFirstRunNoticeIfNeeded();
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
        items.addAll(store.list());
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

    /** 第一次打开软件时把作者与声明说一遍，读屏会直接念出来 */
    private void showFirstRunNoticeIfNeeded() {
        android.content.SharedPreferences sp =
                getSharedPreferences("reader_prefs_pro", MODE_PRIVATE);
        if (sp.getBoolean("first_run_ack", false)) return;
        new AlertDialog.Builder(this)
                .setTitle("关于软件")
                .setMessage("本应用由【站在树上的牛】个人制作使用，禁止出售、向他人收取任何费用，"
                        + "出于方便使用的目的对外分发。\n\n"
                        + "本应用联网仅用于检查软件更新，所有数据仅保存在本机，不会上传到任何服务器。")
                .setCancelable(false)
                .setPositiveButton("我知道了", (d, w) ->
                        sp.edit().putBoolean("first_run_ack", true).apply())
                .show();
    }

    /** 第一次运行弹一次使用声明；清除数据后再运行会再弹 */
    private void showFirstRunNotice() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("app_notice", MODE_PRIVATE);
            if (sp.getBoolean("shown", false)) return;
            sp.edit().putBoolean("shown", true).apply();
        } catch (Throwable ignore) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("使用声明")
                .setMessage("本应用由【站在树上的牛】个人制作使用，禁止出售、向他人收取任何费用，"
                        + "出于方便使用的目的对外分发。\n\n"
                        + "本应用联网仅用于检查软件更新，所有数据仅保存在本机，不会上传到任何服务器。")
                .setPositiveButton("我已知晓", null)
                .setCancelable(true)
                .show();
    }

    /** 关于软件：作者与两条声明 */
    private void showAbout() {
        String text = "电子书阅读器 " + BuildConfig.VERSION_NAME + "\n\n"
                + "本应用由【站在树上的牛】个人制作使用，禁止出售、向他人收取任何费用，"
                + "出于方便使用的目的对外分发。\n\n"
                + "本应用联网仅用于检查软件更新，所有数据仅保存在本机，不会上传到任何服务器。\n\n"
                + "支持格式：EPUB、Kindle 的 MOBI / AZW3 / AZW / PRC、PDF（文字版、扫描版）、"
                + "TXT、FB2、HTML、RTF、DOCX。";
        new AlertDialog.Builder(this)
                .setTitle("关于软件")
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
                + "4.7\n"
                + "检查更新改为读取发布仓库里的版本清单，能在软件里直接看到版本号、更新说明和下载地址。\n\n"
                + "4.6\n"
                + "第一次打开软件时弹一次作者与声明，读屏会念出来。\n\n"
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
            String line2 = it.format + (it.percent > 0 ? "　已读 " + it.percent + "%" : "");
            tv.setText(it.title + "\n" + line2);

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
            row.setOnClickListener(v -> openBook(Uri.parse(it.uri), it.para));
            row.setContentDescription(it.title + "，" + it.format + " 格式"
                    + (it.percent > 0 ? "，已读 " + it.percent + "%" : "")
                    + "。长按可以移除这条记录");
            return row;
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}

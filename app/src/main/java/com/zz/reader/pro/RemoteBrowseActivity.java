package com.zz.reader.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 网络书库浏览：SMB 与 WebDAV 都走标准协议 */
public class RemoteBrowseActivity extends Activity {

    private static class Row {
        String name;
        String url;
        boolean dir;
    }

    private RemoteStore.Server server;
    private String currentUrl;
    private final List<Row> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView tvPath;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_remote);
        tvPath = findViewById(R.id.tvPath);
        ListView list = findViewById(R.id.list);

        Intent in = getIntent();
        server = new RemoteStore.Server();
        server.type = in.getIntExtra("type", 0);
        server.host = in.getStringExtra("host");
        server.user = in.getStringExtra("user");
        server.pass = in.getStringExtra("pass");
        currentUrl = in.getStringExtra("url");
        if (currentUrl == null || currentUrl.trim().isEmpty()) {
            currentUrl = server.type == 0 ? SmbBrowser.norm(server.host) : WebDavBrowser.norm(server.host);
        }

        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1,
                new ArrayList<String>()) {
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < 0 || pos >= rows.size()) return;
            Row r = rows.get(pos);
            if (r.dir) {
                load(r.url);
            } else {
                fetch(r);
            }
        });

        findViewById(R.id.btnUp).setOnClickListener(v -> {
            String parent = parentOf(currentUrl);
            if (parent == null || parent.equals(currentUrl)) {
                finish();
            } else {
                load(parent);
            }
        });

        load(currentUrl);
    }

    @Override
    public void onBackPressed() {
        String parent = parentOf(currentUrl);
        if (parent != null && !parent.equals(currentUrl)) {
            load(parent);
        } else {
            super.onBackPressed();
        }
    }

    private String parentOf(String url) {
        String t = url;
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        int schemeEnd = t.indexOf("://");
        int i = t.lastIndexOf('/');
        if (i <= schemeEnd + 2) return null;
        return t.substring(0, i + 1);
    }

    private String show(String url) {
        String t = url;
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.replace("smb://", "").replace("http://", "").replace("https://", "");
    }

    private void load(final String url) {
        if (busy) {
            Toast.makeText(this, "还在读上一层，等一下", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        currentUrl = url;
        tvPath.setText(show(url));
        adapter.clear();
        adapter.add("正在读取…");
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            String err = null;
            List<Row> got = new ArrayList<>();
            try {
                if (server.type == 0) {
                    for (SmbBrowser.Item it : SmbBrowser.list(server, url)) {
                        Row r = new Row();
                        r.name = it.name;
                        r.url = it.url;
                        r.dir = it.dir;
                        got.add(r);
                    }
                } else {
                    for (WebDavBrowser.Item it : WebDavBrowser.list(server, url)) {
                        Row r = new Row();
                        r.name = it.name;
                        r.url = it.url;
                        r.dir = it.dir;
                        got.add(r);
                    }
                }
            } catch (Throwable e) {
                err = server.type == 0 ? SmbBrowser.reason(e) : String.valueOf(e.getMessage());
                if (err == null || err.trim().isEmpty()) err = e.toString();
            }
            final String fErr = err;
            final List<Row> fGot = got;
            runOnUiThread(() -> {
                busy = false;
                rows.clear();
                rows.addAll(fGot);
                adapter.clear();
                for (Row r : fGot) adapter.add(r.dir ? "文件夹 " + r.name : r.name);
                if (fGot.isEmpty() && fErr == null) adapter.add("这个目录是空的");
                adapter.notifyDataSetChanged();
                if (fErr != null) {
                    new AlertDialog.Builder(RemoteBrowseActivity.this)
                            .setTitle("连不上")
                            .setMessage(fErr)
                            .setPositiveButton("关闭", null)
                            .show();
                }
            });
        }).start();
    }

    private void fetch(final Row r) {
        if (busy) {
            Toast.makeText(this, "还在忙，等一下", Toast.LENGTH_SHORT).show();
            return;
        }
        busy = true;
        Toast.makeText(this, "正在下载 " + r.name, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File out = null;
            String err = null;
            try {
                String safe = r.name.replace('/', '_').replace('\\', '_');
                out = new File(CacheStore.dir(this),
                        Integer.toHexString(r.url.hashCode()) + "_" + safe);
                if (server.type == 0) {
                    SmbBrowser.download(server, r.url, out);
                } else {
                    WebDavBrowser.download(server, r.url, out);
                }
            } catch (Throwable e) {
                err = server.type == 0 ? SmbBrowser.reason(e) : String.valueOf(e.getMessage());
                if (err == null || err.trim().isEmpty()) err = e.toString();
                out = null;
            }
            final File f = out;
            final String fErr = err;
            runOnUiThread(() -> {
                busy = false;
                if (f == null) {
                    new AlertDialog.Builder(RemoteBrowseActivity.this)
                            .setTitle("下载失败")
                            .setMessage(fErr == null ? "未知原因" : fErr)
                            .setPositiveButton("关闭", null)
                            .show();
                    return;
                }
                Intent i = new Intent(RemoteBrowseActivity.this, ReaderActivity.class);
                i.setData(Uri.fromFile(f));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            });
        }).start();
    }
}

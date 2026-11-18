package com.zz.reader.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新：只去下载一份版本清单（纯 JSON），比对版本号。
 * 有新版本就把下载地址交给用户自己点，不在后台偷偷安装，也不上传任何本机信息。
 */
public class UpdateChecker {

    /** 版本清单地址（放在发布仓库的 main 分支上） */
    private static final String MANIFEST =
            "https://raw.githubusercontent.com/hzj1770niu/ebook-reader/main/update_pro.json";

    public static void check(final Activity act) {
        if (act == null || act.isFinishing()) return;
        Toast.makeText(act, "正在检查更新", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            int newCode = -1;
            String newName = "";
            String notes = "";
            String url = "";
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(MANIFEST).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "EbookReader/" + Build.VERSION.SDK_INT);
                int code = c.getResponseCode();
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    JSONObject o = new JSONObject(sb.toString());
                    newCode = o.optInt("versionCode", -1);
                    newName = o.optString("versionName", "");
                    notes = o.optString("changelog", "");
                    url = o.optString("apkUrl", "");
                } else {
                    error = "服务器返回 " + code;
                }
                c.disconnect();
            } catch (Throwable e) {
                String m = e.getMessage();
                error = (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
            }

            final String err = error;
            final int nc = newCode;
            final String nn = newName, nt = notes, nu = url;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                if (err != null) {
                    new AlertDialog.Builder(act)
                            .setTitle("检查更新")
                            .setMessage("没能连上更新服务器：" + err
                                    + "。检查更新只走网络一次，没网时这个功能不可用。")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                if (nc < 0) {
                    new AlertDialog.Builder(act)
                            .setTitle("检查更新")
                            .setMessage("服务器上没有读到版本清单，先不检查了。")
                            .setPositiveButton("好", null)
                            .show();
                    return;
                }
                if (nc <= BuildConfig.VERSION_CODE) {
                    new AlertDialog.Builder(act)
                            .setTitle("已经是最新版本")
                            .setMessage("当前 " + BuildConfig.VERSION_NAME
                                    + "，服务器上是 " + nn + "。")
                            .setPositiveButton("好", null)
                            .show();
                    return;
                }
                String body = nt == null ? "" : nt.trim();
                if (body.isEmpty()) body = "新版本 " + nn + "。";
                if (body.length() > 900) body = body.substring(0, 900) + "……";
                final String link = nu;
                new AlertDialog.Builder(act)
                        .setTitle("发现新版本 " + nn)
                        .setMessage("当前 " + BuildConfig.VERSION_NAME + "。\n\n" + body)
                        .setPositiveButton("去下载", (d, w) -> openLink(act, link))
                        .setNegativeButton("以后再说", null)
                        .show();
                });
        }).start();
    }

    private static void openLink(Activity act, String url) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(act, "清单里没有下载地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(act, "打不开下载页：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

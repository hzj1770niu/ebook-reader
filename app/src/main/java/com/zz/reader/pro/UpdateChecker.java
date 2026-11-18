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
 * 检查更新：只读一份版本清单（纯 JSON），比对版本号。
 * 清单有好几个来源，挨个试，谁通就用谁；一个都不通也给一个能打开的发布页入口。
 * 不下载、不安装、不上传任何本机信息。
 */
public class UpdateChecker {

    /** 发布页，清单全不通的时候让用户自己去翻 */
    private static final String RELEASE_PAGE = "https://github.com/hzj1770niu/ebook-reader/releases";

    /**
     * 版本清单的多个来源，按顺序尝试：
     * 先是国内速度比较稳的 jsDelivr 几个镜像，再是 GitHub 原地址，最后是两三个反代。
     */
    private static final String[] MANIFESTS = {
            // 反代优先（受限网络下也能通），再是 GitHub 原址，最后是镜像兜底
            "https://ghproxy.net/https://raw.githubusercontent.com/hzj1770niu/ebook-reader/main/version.json",
            "https://gh-proxy.com/https://raw.githubusercontent.com/hzj1770niu/ebook-reader/main/version.json",
            "https://raw.githubusercontent.com/hzj1770niu/ebook-reader/main/version.json",
            "https://cdn.jsdelivr.net/gh/hzj1770niu/ebook-reader@main/version.json",
            "https://fastly.jsdelivr.net/gh/hzj1770niu/ebook-reader@main/version.json",
            "https://gcore.jsdelivr.net/gh/hzj1770niu/ebook-reader@main/version.json",
    };

    /** 启动时自动检查：开关关了就跳过；六小时内查过也跳过；失败不打扰 */
    public static void autoCheck(final Activity act) {
        if (act == null || act.isFinishing()) return;
        try {
            Prefs p = new Prefs(act);
            if (!p.autoCheckUpdate()) return;
            long last = p.lastUpdateCheck();
            long now = System.currentTimeMillis();
            if (last > 0 && now - last < 6L * 60L * 60L * 1000L) return;
            p.setLastUpdateCheck(now);
        } catch (Throwable ignore) {
            return;
        }
        check(act, true);
    }

    public static void check(final Activity act) {
        check(act, false);
    }

    /** silent=true 用于启动时自动检查：连不上就什么都不说，不打扰 */
    public static void check(final Activity act, final boolean silent) {
        if (act == null || act.isFinishing()) return;
        if (!silent) Toast.makeText(act, "正在检查更新", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            JSONObject got = null;
            String lastError = null;
            for (String src : MANIFESTS) {
                try {
                    JSONObject o = fetch(src);
                    if (o != null && o.optInt("versionCode", -1) > 0) {
                        got = o;
                        break;
                    }
                    lastError = "清单里没有版本号";
                } catch (Throwable e) {
                    String m = e.getMessage();
                    lastError = (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
                }
            }

            final JSONObject o = got;
            final String err = lastError;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                if (o == null) {
                    if (silent) return;
                    new AlertDialog.Builder(act)
                            .setTitle("检查更新")
                            .setMessage("没能连上更新服务器，稍后再试一次。")
                            .setPositiveButton("知道了", null)
                            .setNeutralButton("去发布页", (d, w) -> openLink(act, RELEASE_PAGE))
                            .show();
                    return;
                }
                int nc = o.optInt("versionCode", -1);
                String nn = o.optString("versionName", "");
                String notes = o.optString("changelog", "");
                if (nc <= BuildConfig.VERSION_CODE) {
                    if (silent) return;
                    new AlertDialog.Builder(act)
                            .setTitle("已经是最新版本")
                            .setMessage("当前 " + BuildConfig.VERSION_NAME + "，服务器上是 " + nn + "。")
                            .setPositiveButton("好", null)
                            .setNeutralButton("发布页", (d, w) -> openLink(act, RELEASE_PAGE))
                            .show();
                    return;
                }
                String body = onlyNewer(notes, BuildConfig.VERSION_NAME);
                if (body.isEmpty()) body = notes == null ? "" : notes.trim();
                if (body.isEmpty()) body = "新版本 " + nn + "。";
                if (body.length() > 900) body = body.substring(0, 900) + "……";
                final String link = o.optString("apkUrl", "");
                new AlertDialog.Builder(act)
                        .setTitle("发现新版本 " + nn)
                        .setMessage("当前 " + BuildConfig.VERSION_NAME + "。\n\n" + body)
                        .setPositiveButton("去下载", (d, w) -> openLink(act, link))
                        .setNegativeButton("以后再说", null)
                        .setNeutralButton("发布页", (d, w) -> openLink(act, RELEASE_PAGE))
                        .show();
            });
        }).start();
    }

    /** 取一份清单；超时短一点，一个源卡住别拖太久 */
    private static JSONObject fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "EbookReader/" + Build.VERSION.SDK_INT);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Cache-Control", "no-cache, no-store");
            c.setRequestProperty("Pragma", "no-cache");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONObject(sb.toString());
        } finally {
            c.disconnect();
        }
    }

    /** 版本号按数字段比较，4.10 大于 4.9 */
    static int compare(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        while (x.startsWith("v") || x.startsWith("V")) x = x.substring(1);
        while (y.startsWith("v") || y.startsWith("V")) y = y.substring(1);
        String[] p = x.split("\\.");
        String[] q = y.split("\\.");
        int n = Math.max(p.length, q.length);
        for (int i = 0; i < n; i++) {
            int pa = i < p.length ? num(p[i]) : 0;
            int qb = i < q.length ? num(q[i]) : 0;
            if (pa != qb) return pa < qb ? -1 : 1;
        }
        return 0;
    }

    private static int num(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else break;
        }
        if (sb.length() == 0) return 0;
        try {
            return Integer.parseInt(sb.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 只取比当前版本高的那些段的更新日志 */
    static String onlyNewer(String changelog, String currentVersion) {
        if (changelog == null) return "";
        StringBuilder out = new StringBuilder();
        boolean inNewer = false;
        for (String raw : changelog.split("\n")) {
            String t = raw.trim();
            if (t.matches("v?[0-9]+(\\.[0-9]+)*")) {
                String ver = t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
                inNewer = compare(ver, currentVersion) > 0;
                if (inNewer) {
                    if (out.length() > 0) out.append("\n");
                    out.append(t);
                }
                continue;
            }
            if (inNewer && !t.isEmpty()) out.append("\n").append(raw.trim());
        }
        return out.toString().trim();
    }

    /** 取某一版自己的那一段 */
    static String sectionOf(String changelog, String version) {
        if (changelog == null) return "";
        String want = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        StringBuilder out = new StringBuilder();
        boolean in = false;
        for (String raw : changelog.split("\n")) {
            String t = raw.trim();
            if (t.matches("v?[0-9]+(\\.[0-9]+)*")) {
                String ver = t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
                in = ver.equals(want);
                if (in) out.append(t);
                continue;
            }
            if (in && !t.isEmpty()) out.append("\n").append(t);
        }
        return out.toString().trim();
    }

    /** 更新日志：按当前版本过滤，只写比当前版本高的；已经最新就写当前这一版 */
    public static void showChangelog(final Activity act) {
        if (act == null || act.isFinishing()) return;
        new Thread(() -> {
            String cl = null;
            for (String src : MANIFESTS) {
                try {
                    JSONObject o = fetch(src);
                    if (o != null && o.optString("changelog", "").trim().length() > 0) {
                        cl = o.optString("changelog", "");
                        break;
                    }
                } catch (Throwable ignore) {
                }
            }
            final String all = cl;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                String text;
                if (all == null || all.trim().isEmpty()) {
                    text = "没能读到更新日志，检查一下网络再试。";
                } else {
                    text = onlyNewer(all, BuildConfig.VERSION_NAME);
                    if (text.isEmpty()) text = sectionOf(all, BuildConfig.VERSION_NAME);
                    if (text.isEmpty()) text = all;
                }
                new AlertDialog.Builder(act)
                        .setTitle("更新日志")
                        .setMessage(text)
                        .setPositiveButton("关闭", null)
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
            Toast.makeText(act, "打不开链接：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

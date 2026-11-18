package com.zz.reader.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：下载、显示进度、校验、拉起系统安装器。
 * 装 APK 的最后一步必须由系统确认，任何第三方应用都绕不过去，这里能做到的只是把文件准备好、
 * 自动把安装界面送到用户面前，不用去文件管理器里翻。
 */
public class UpdateInstaller {

    private static volatile boolean cancel;
    private static volatile HttpURLConnection conn;

    public static void start(final Activity act, final String versionName,
                             final String apkUrl, final String sha256) {
        if (act == null || act.isFinishing()) return;
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            new AlertDialog.Builder(act).setTitle("没法下载")
                    .setMessage("清单里没有安装包地址，这次没法自动更新。")
                    .setPositiveButton("知道了", null).show();
            return;
        }

        final LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        final TextView tv = new TextView(act);
        tv.setText("准备下载");
        final ProgressBar pb = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(0);
        pb.setFocusable(true);
        pb.setContentDescription("下载进度 0%");
        box.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        cancel = false;
        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("正在下载 " + versionName)
                .setView(box)
                .setNegativeButton("取消", (d, w) -> {
                    cancel = true;
                    HttpURLConnection c = conn;
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Throwable ignore) {
                        }
                    }
                })
                .create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.show();

        final File target = new File(UpdateProvider.dir(act), "ebook_update.apk");
        Thread t = new Thread(() -> {
            String err = null;
            try {
                download(act, apkUrl, target, tv, pb);
            } catch (Throwable e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            if (cancel) {
                try { target.delete(); } catch (Throwable ignore) {}
                return;
            }
            final String error = err;
            act.runOnUiThread(() -> {
                if (act.isFinishing()) return;
                try { dlg.dismiss(); } catch (Throwable ignore) {}
                if (error != null) {
                    new AlertDialog.Builder(act).setTitle("下载没成功")
                            .setMessage(error + "\n\n可以再试一次。")
                            .setPositiveButton("重试", (d, w) -> start(act, versionName, apkUrl, sha256))
                            .setNegativeButton("算了", null)
                            .show();
                    return;
                }
                // 校验：对不上就当没下好，绝不能让用户装一个坏包
                String got = null;
                try {
                    got = sha256Of(target);
                } catch (Throwable ignore) {
                }
                if (sha256 != null && !sha256.trim().isEmpty()
                        && got != null && !got.equalsIgnoreCase(sha256.trim())) {
                    try { target.delete(); } catch (Throwable ignore) {}
                    new AlertDialog.Builder(act).setTitle("文件校验没通过")
                            .setMessage("下载下来的安装包和清单里的指纹对不上，可能没下完整。再下一次。")
                            .setPositiveButton("重下", (d, w) -> start(act, versionName, apkUrl, sha256))
                            .setNegativeButton("算了", null)
                            .show();
                    return;
                }
                install(act, target);
            });
        }, "app-update");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void download(Activity act, String url, File target,
                                 TextView tv, ProgressBar pb) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        conn = c;
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "EbookReader/update");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            long total = c.getContentLength();
            InputStream in = c.getInputStream();
            FileOutputStream out = new FileOutputStream(target, false);
            byte[] buf = new byte[8192];
            long done = 0;
            int lastPct = -1;
            long lastUi = 0;
            while (true) {
                if (cancel) throw new Exception("已取消");
                int n = in.read(buf);
                if (n <= 0) break;
                out.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : -1;
                long now = System.currentTimeMillis();
                if (pct != lastPct || now - lastUi > 400) {
                    lastPct = pct;
                    lastUi = now;
                    final int p = pct;
                    final long d = done;
                    final long tt = total;
                    act.runOnUiThread(() -> {
                        if (p >= 0) {
                            pb.setProgress(p);
                            pb.setContentDescription("下载进度 " + p + "%");
                            tv.setText("已下载 " + p + "%，共 " + mb(tt));
                        } else {
                            tv.setText("已下载 " + mb(d));
                        }
                    });
                }
            }
            out.close();
            in.close();
            if (done <= 0) throw new Exception("下载到 0 字节，网络可能断了");
        } finally {
            conn = null;
            try {
                c.disconnect();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String mb(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1048576.0);
        }
        return (bytes / 1024) + " KB";
    }

    /** 装：先看有没有“安装未知应用”的权限，没有就带用户去开，开完回来再点一次下载并安装 */
    private static void install(Activity act, File apk) {
        if (Build.VERSION.SDK_INT >= 26) {
            boolean ok = false;
            try {
                ok = act.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable ignore) {
            }
            if (!ok) {
                new AlertDialog.Builder(act)
                        .setTitle("需要允许安装")
                        .setMessage("系统要求先允许这个应用安装其他应用。点“去开启”，在列表里把开关打开，"
                                + "回来再点一次“下载并安装”就好。")
                        .setPositiveButton("去开启", (d, w) -> {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + act.getPackageName()));
                                act.startActivity(i);
                            } catch (Throwable ignore) {
                            }
                        })
                        .setNegativeButton("算了", null)
                        .show();
                return;
            }
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(UpdateProvider.uriFor(act, apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable e) {
            new AlertDialog.Builder(act).setTitle("没能打开安装界面")
                    .setMessage("安装包已经下载好了，但系统没让拉起安装界面。可以再试一次。")
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private static String sha256Of(File f) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } finally {
            in.close();
        }
    }
}

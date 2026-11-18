package com.zz.reader.pro;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/** 标准 SMB 协议（SMB2 / SMB3 优先，回退 SMB1），基于 jcifs-ng */
public class SmbBrowser {

    public static class Item {
        public String name;
        public String url;
        public boolean dir;
    }

    private static CIFSContext ctx(RemoteStore.Server s) throws Exception {
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.minVersion", "SMB202");
        p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        p.setProperty("jcifs.smb.client.responseTimeout", "30000");
        p.setProperty("jcifs.smb.client.connTimeout", "30000");
        p.setProperty("jcifs.smb.client.soTimeout", "60000");
        p.setProperty("jcifs.netbios.cachePolicy", "120");
        p.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
        PropertyConfiguration cfg = new PropertyConfiguration(p);
        CIFSContext base = new BaseContext(cfg);
        String user = s.user == null ? "" : s.user.trim();
        if (user.isEmpty()) return base.withAnonymousCredentials();
        String domain = null;
        if (user.indexOf('\\') > 0) {
            domain = user.substring(0, user.indexOf('\\'));
            user = user.substring(user.indexOf('\\') + 1);
        }
        return base.withCredentials(new NtlmPasswordAuthenticator(domain, user,
                s.pass == null ? "" : s.pass));
    }

    /** 把用户填的地址规范成 smb://host/share/path/ 形式 */
    public static String norm(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) return "";
        if (!h.startsWith("smb://")) h = "smb://" + h;
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h + "/";
    }

    public static List<Item> list(RemoteStore.Server s, String url) throws Exception {
        String target = norm(url);
        SmbFile dir = new SmbFile(target, ctx(s));
        if (!dir.exists()) throw new Exception("路径不存在：" + target);
        if (!dir.isDirectory()) throw new Exception("这不是一个目录：" + target);
        SmbFile[] files = dir.listFiles();
        List<Item> out = new ArrayList<>();
        if (files != null) {
            for (SmbFile f : files) {
                String n = f.getName();
                if (n.equals(".") || n.equals("..")) continue;
                Item it = new Item();
                it.dir = f.isDirectory();
                it.name = it.dir && n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
                it.url = target + n;
                if (!it.url.endsWith("/") && it.dir) it.url = it.url + "/";
                out.add(it);
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    public static void download(RemoteStore.Server s, String url, File out) throws Exception {
        SmbFile f = new SmbFile(url, ctx(s));
        if (!f.exists()) throw new Exception("文件不存在：" + url);
        File tmp = new File(out.getAbsolutePath() + ".part");
        try (InputStream in = f.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new Exception("写入缓存失败");
    }

    /** 把异常翻成人话，但保留原始原因，不笼统说密码错 */
    public static String reason(Throwable e) {
        Throwable t = e;
        StringBuilder detail = new StringBuilder();
        while (t != null) {
            String m = t.getMessage();
            String cn = t.getClass().getSimpleName();
            if (m != null && m.length() > 0) {
                if (detail.length() > 0) detail.append(" / ");
                detail.append(cn).append(": ").append(m);
            }
            t = t.getCause();
            if (t == null || detail.length() > 200) break;
        }
        String d = detail.toString();
        String low = d.toLowerCase();
        if (low.contains("logon failure") || low.contains("status_logon_failure")
                || low.contains("access denied") || low.contains("status_access_denied")) {
            return "服务器拒绝了登录。用户名或密码不对，也可能账号没有这个共享的权限。" + brief(d);
        }
        if (low.contains("unknownhost") || low.contains("unresolved") || low.contains("no route")) {
            return "连不上这台机器。检查地址、端口，以及是不是同一个局域网。" + brief(d);
        }
        if (low.contains("connection refused") || low.contains("connect")) {
            return "连接被拒绝。看服务器有没有开 SMB 服务，防火墙有没有放行 445 端口。" + brief(d);
        }
        if (low.contains("not exist") || low.contains("status_object_name_not_found")) {
            return "路径不存在。检查共享名和目录名有没有写错。" + brief(d);
        }
        if (low.contains("smb1") || low.contains("dialect")) {
            return "协议协商失败。服务器可能只开了 SMB1 或者禁了 SMB2/3。" + brief(d);
        }
        return d.isEmpty() ? e.toString() : d;
    }

    private static String brief(String d) {
        return d.isEmpty() ? "" : "（" + (d.length() > 120 ? d.substring(0, 120) + "…" : d) + "）";
    }
}

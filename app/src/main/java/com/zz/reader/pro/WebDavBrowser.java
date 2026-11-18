package com.zz.reader.pro;

import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 标准 WebDAV：PROPFIND + Basic 认证，207 Multi-Status 解析，跟随重定向 */
public class WebDavBrowser {

    public static class Item {
        public String name;
        public String url;
        public boolean dir;
    }

    private static final String BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<D:propfind xmlns:D=\"DAV:\"><D:prop>"
            + "<D:displayname/><D:getcontentlength/><D:getcontenttype/><D:resourcetype/>"
            + "</D:prop></D:propfind>";

    /** 地址规范化：缺协议默认 http；结尾补斜杠 */
    public static String norm(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty()) return "";
        if (!h.startsWith("http://") && !h.startsWith("https://")) h = "http://" + h;
        if (!h.endsWith("/")) h = h + "/";
        return h;
    }

    private static String auth(RemoteStore.Server s) {
        String u = s.user == null ? "" : s.user;
        if (u.trim().isEmpty()) return null;
        String raw = u + ":" + (s.pass == null ? "" : s.pass);
        return "Basic " + Base64.encodeToString(raw.getBytes(), Base64.NO_WRAP);
    }

    private static HttpURLConnection open(RemoteStore.Server s, String url, String method) throws Exception {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        String a = auth(s);
        if (a != null) c.setRequestProperty("Authorization", a);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("User-Agent", "EbookReader/Android");
        return c;
    }

    public static List<Item> list(RemoteStore.Server s, String url) throws Exception {
        String target = norm(url);
        List<Item> out = new ArrayList<>();
        int redirects = 0;
        while (true) {
            HttpURLConnection c = open(s, target, "PROPFIND");
            c.setRequestProperty("Depth", "1");
            c.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
            c.setDoOutput(true);
            byte[] body = BODY.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            try {
                c.getOutputStream().write(body);
                c.getOutputStream().flush();
            } catch (Exception ignore) {
            }
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || redirects++ > 3) throw new Exception("重定向异常（HTTP " + code + "）");
                target = new URL(new URL(target), loc).toString();
                continue;
            }
            if (code == 401) throw new Exception("认证失败（HTTP 401）：用户名或密码不对，或者服务器要求别的认证方式");
            if (code == 403) throw new Exception("被拒绝（HTTP 403）：账号没有这个目录的权限");
            if (code == 404) throw new Exception("路径不存在（HTTP 404）：检查地址里的目录名");
            if (code == 405) throw new Exception("服务器不允许 PROPFIND（HTTP 405）：这个地址可能不是 WebDAV 服务");
            if (code != 207 && code != 200) throw new Exception("服务器返回 HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                parse(in, target, out);
            } finally {
                c.disconnect();
            }
            break;
        }
        Collections.sort(out, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    private static void parse(InputStream in, String base, List<Item> out) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(in, "UTF-8");
        String href = null;
        boolean collection = false;
        String display = null;
        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if ("response".equals(tag)) {
                    href = null;
                    collection = false;
                    display = null;
                } else if ("href".equals(tag)) {
                    href = p.nextText().trim();
                } else if ("collection".equals(tag)) {
                    collection = true;
                } else if ("displayname".equals(tag)) {
                    display = p.nextText().trim();
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if ("response".equals(p.getName()) && href != null) {
                    Item it = new Item();
                    it.dir = collection;
                    String abs = absolute(base, href);
                    String name = display;
                    if (name == null || name.trim().isEmpty()) name = lastSegment(href);
                    if (!name.isEmpty()) {
                        // 跳过目录自己
                        String selfPath = new URI(base).getPath();
                        String thisPath = new URI(abs).getPath();
                        if (selfPath != null && thisPath != null
                                && trimSlash(selfPath).equals(trimSlash(thisPath))) {
                            name = null;
                        }
                    }
                    if (name != null && !name.isEmpty()) {
                        it.name = name;
                        it.url = it.dir && !abs.endsWith("/") ? abs + "/" : abs;
                        out.add(it);
                    }
                }
            }
            ev = p.next();
        }
    }

    private static String absolute(String base, String href) {
        try {
            URI h = new URI(href);
            if (h.isAbsolute()) return h.toString();
            URI b = new URI(base);
            return b.resolve(h).toString();
        } catch (Exception e) {
            return href;
        }
    }

    private static String lastSegment(String href) {
        String h = href;
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        int i = h.lastIndexOf('/');
        String s = i >= 0 ? h.substring(i + 1) : h;
        try {
            s = URLDecoder.decode(s, "UTF-8");
        } catch (Exception ignore) {
        }
        return s;
    }

    private static String trimSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static void download(RemoteStore.Server s, String url, File out) throws Exception {
        int redirects = 0;
        String target = url;
        while (true) {
            HttpURLConnection c = open(s, target, "GET");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || redirects++ > 3) throw new Exception("重定向异常（HTTP " + code + "）");
                target = new URL(new URL(target), loc).toString();
                continue;
            }
            if (code == 401) throw new Exception("认证失败（HTTP 401）：用户名或密码不对");
            if (code == 404) throw new Exception("文件不存在（HTTP 404）");
            if (code < 200 || code >= 300) throw new Exception("下载失败，服务器返回 HTTP " + code);
            File tmp = new File(out.getAbsolutePath() + ".part");
            try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) throw new Exception("写入缓存失败");
            return;
        }
    }
}

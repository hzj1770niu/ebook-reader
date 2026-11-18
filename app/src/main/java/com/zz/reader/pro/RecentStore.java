package com.zz.reader.pro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 最近阅读记录 + 每本书的书签，都存本地 */
public class RecentStore {

    public static class Item {
        public String uri = "";
        public String title = "";
        public String format = "";
        public String cover = "";
        public int para = 0;
        public int percent = 0;
        public long time = 0;
    }

    public static class Bookmark {
        public int para;
        public int page;
        public String chapter = "";
        public String text = "";
        public long time;
    }

    private final SharedPreferences sp;

    public RecentStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("recent_pro", Context.MODE_PRIVATE);
    }

    public List<Item> list() {
        List<Item> out = new ArrayList<>();
        String all = sp.getString("items", "");
        if (all == null || all.isEmpty()) return out;
        for (String line : all.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 4) continue;
            Item it = new Item();
            it.uri = f[0];
            it.title = f[1];
            it.format = f[2];
            it.cover = f[3];
            it.para = parseInt(f, 4);
            it.percent = parseInt(f, 5);
            it.time = parseLong(f, 6);
            out.add(it);
        }
        return out;
    }

    public void touch(String uri, String title, String format, String cover, int para, int percent) {
        List<Item> items = list();
        Item target = null;
        for (Item it : items) {
            if (it.uri.equals(uri)) {
                target = it;
                break;
            }
        }
        if (target == null) {
            target = new Item();
            target.uri = uri;
        } else {
            items.remove(target);
        }
        target.title = safe(title);
        target.format = safe(format);
        if (cover != null && !cover.isEmpty()) target.cover = safe(cover);
        target.para = para;
        target.percent = percent;
        target.time = System.currentTimeMillis();
        items.add(0, target);
        save(items);
    }

    public void remove(String uri) {
        List<Item> items = list();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).uri.equals(uri)) {
                items.remove(i);
                break;
            }
        }
        save(items);
    }

    private void save(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Item it : items) {
            if (count++ >= 30) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(safe(it.uri)).append('\t')
                    .append(safe(it.title)).append('\t')
                    .append(safe(it.format)).append('\t')
                    .append(safe(it.cover)).append('\t')
                    .append(it.para).append('\t')
                    .append(it.percent).append('\t')
                    .append(it.time);
        }
        sp.edit().putString("items", sb.toString()).apply();
    }

    // ---------------------------------------------------------------- 书签

    public List<Bookmark> bookmarks(String uri) {
        List<Bookmark> out = new ArrayList<>();
        String all = sp.getString("bm:" + uri, "");
        if (all == null || all.isEmpty()) return out;
        for (String line : all.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 4) continue;
            Bookmark b = new Bookmark();
            b.para = parseInt(f, 0);
            b.page = parseInt(f, 1);
            b.chapter = f[2];
            b.text = f[3];
            b.time = parseLong(f, 4);
            out.add(b);
        }
        return out;
    }

    public boolean addBookmark(String uri, int para, int page, String chapter, String text) {
        List<Bookmark> list = bookmarks(uri);
        for (Bookmark b : list) {
            if (b.para == para) return false;
        }
        Bookmark b = new Bookmark();
        b.para = para;
        b.page = page;
        b.chapter = safe(chapter).replace('\t', ' ');
        String t = text == null ? "" : text.replace('\n', ' ').replace('\t', ' ');
        if (t.length() > 80) t = t.substring(0, 80) + "…";
        b.text = t;
        b.time = System.currentTimeMillis();
        list.add(b);
        saveBookmarks(uri, list);
        return true;
    }

    public void removeBookmark(String uri, int para) {
        List<Bookmark> list = bookmarks(uri);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).para == para) {
                list.remove(i);
                break;
            }
        }
        saveBookmarks(uri, list);
    }

    private void saveBookmarks(String uri, List<Bookmark> list) {
        StringBuilder sb = new StringBuilder();
        for (Bookmark b : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(b.para).append('\t')
                    .append(b.page).append('\t')
                    .append(safe(b.chapter)).append('\t')
                    .append(safe(b.text)).append('\t')
                    .append(b.time);
        }
        sp.edit().putString("bm:" + uri, sb.toString()).apply();
    }

    private static int parseInt(String[] f, int idx) {
        if (idx >= f.length) return 0;
        try {
            return Integer.parseInt(f[idx]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long parseLong(String[] f, int idx) {
        if (idx >= f.length) return 0;
        try {
            return Long.parseLong(f[idx]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').trim();
    }
}

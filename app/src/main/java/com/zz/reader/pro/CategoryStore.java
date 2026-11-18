package com.zz.reader.pro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 书库分类：分类名列表 + 每本书归属哪个分类，都存本机 */
public class CategoryStore {

    private final SharedPreferences sp;

    public CategoryStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("categories_pro", Context.MODE_PRIVATE);
    }

    /** 分类名，按创建顺序 */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        String all = sp.getString("cats", "");
        if (all == null || all.isEmpty()) return out;
        for (String s : all.split("\n")) {
            if (!s.trim().isEmpty() && !out.contains(s.trim())) out.add(s.trim());
        }
        return out;
    }

    public boolean exists(String name) {
        return list().contains(name);
    }

    public void add(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || exists(n)) return;
        List<String> l = list();
        l.add(n);
        save(l);
    }

    /** 删除分类：书不删，只是回到"未分类" */
    public void removeCategory(String name) {
        List<String> l = list();
        l.remove(name);
        save(l);
        String map = sp.getString("map", "");
        StringBuilder sb = new StringBuilder();
        for (String line : map.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 2) continue;
            if (f[1].equals(name)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(f[0]).append('\t').append(f[1]);
        }
        sp.edit().putString("map", sb.toString()).apply();
    }

    /** 这本书属于哪个分类，没设过返回空串 */
    public String categoryOf(String uri) {
        String map = sp.getString("map", "");
        for (String line : map.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length >= 2 && f[0].equals(uri)) return f[1];
        }
        return "";
    }

    public void setCategory(String uri, String name) {
        String map = sp.getString("map", "");
        StringBuilder sb = new StringBuilder();
        for (String line : map.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length >= 2 && f[0].equals(uri)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        if (name != null && !name.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(uri).append('\t').append(name);
        }
        sp.edit().putString("map", sb.toString()).apply();
    }

    private void save(List<String> cats) {
        StringBuilder sb = new StringBuilder();
        for (String s : cats) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        sp.edit().putString("cats", sb.toString()).apply();
    }
}

package com.zz.reader.pro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 网络书库的服务器配置，存在本机 SharedPreferences */
public class RemoteStore {

    public static class Server {
        public int type;      // 0 = SMB, 1 = WebDAV
        public String host;   // SMB: 192.168.1.10/share ；WebDAV: https://dav.example.com/dav/
        public String user;
        public String pass;

        public String typeName() {
            return type == 0 ? "SMB" : "WebDAV";
        }

        public String label() {
            String h = host == null ? "" : host;
            h = h.replace("smb://", "").replace("http://", "").replace("https://", "");
            while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
            return typeName() + " " + h;
        }
    }

    private final SharedPreferences sp;

    public RemoteStore(Context c) {
        sp = c.getSharedPreferences("remote_pro", Context.MODE_PRIVATE);
    }

    public List<Server> list() {
        List<Server> out = new ArrayList<>();
        String all = sp.getString("servers", "");
        if (all == null || all.isEmpty()) return out;
        for (String line : all.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 4) continue;
            Server s = new Server();
            try {
                s.type = Integer.parseInt(f[0]);
            } catch (Exception e) {
                s.type = 0;
            }
            s.host = f[1];
            s.user = f[2];
            s.pass = f[3];
            out.add(s);
        }
        return out;
    }

    public void add(Server s) {
        List<Server> cur = list();
        cur.add(s);
        save(cur);
    }

    public void remove(int index) {
        List<Server> cur = list();
        if (index >= 0 && index < cur.size()) {
            cur.remove(index);
            save(cur);
        }
    }

    private void save(List<Server> list) {
        StringBuilder sb = new StringBuilder();
        for (Server s : list) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(s.type).append('\t')
                    .append(clean(s.host)).append('\t')
                    .append(clean(s.user)).append('\t')
                    .append(clean(s.pass));
        }
        sp.edit().putString("servers", sb.toString()).apply();
    }

    private static String clean(String v) {
        if (v == null) return "";
        return v.replace("\t", " ").replace("\n", " ");
    }
}

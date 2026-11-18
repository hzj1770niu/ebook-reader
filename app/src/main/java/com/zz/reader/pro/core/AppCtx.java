package com.zz.reader.pro.core;

import android.content.Context;

/** 全局应用上下文，给解析器取 assets 用 */
public class AppCtx {

    private static Context ctx;

    public static void init(Context c) {
        if (ctx == null && c != null) ctx = c.getApplicationContext();
    }

    public static Context get() {
        return ctx;
    }
}

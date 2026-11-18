package com.zz.reader.pro;

import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** 给控件挂“转子操作”：读屏转到“操作”时能直接选，不用先找工具栏按钮 */
public class A11y {

    public static final int SPEAK = 0x01020001;
    public static final int ADD_BOOKMARK = 0x01020002;
    public static final int NEXT_PAGE = 0x01020003;
    public static final int PREV_PAGE = 0x01020004;
    public static final int DELETE_BOOK = 0x01020005;
    public static final int MOVE_CATEGORY = 0x01020006;
    public static final int DELETE_BOOKMARK = 0x01020007;

    public static class Act {
        int id;
        String label;
        Runnable run;
    }

    public static Act act(int id, String label, Runnable run) {
        Act a = new Act();
        a.id = id;
        a.label = label;
        a.run = run;
        return a;
    }

    private static final WeakHashMap<View, List<Act>> MAP = new WeakHashMap<>();

    private static class Del extends View.AccessibilityDelegate {
        private final View.AccessibilityDelegate base;

        Del(View.AccessibilityDelegate base) {
            this.base = base;
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            if (base != null) {
                base.onInitializeAccessibilityNodeInfo(host, info);
            } else {
                super.onInitializeAccessibilityNodeInfo(host, info);
            }
            List<Act> acts = MAP.get(host);
            if (acts == null) return;
            for (Act a : acts) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(a.id, a.label));
            }
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            List<Act> acts = MAP.get(host);
            if (acts != null) {
                for (Act a : acts) {
                    if (a.id == action) {
                        a.run.run();
                        return true;
                    }
                }
            }
            if (base != null) return base.performAccessibilityAction(host, action, args);
            return super.performAccessibilityAction(host, action, args);
        }
    }

    /** base 是原来那个 delegate，可以是 null；会串起来不覆盖原行为 */
    public static void attach(View v, View.AccessibilityDelegate base, Act... acts) {
        if (v == null || acts == null || acts.length == 0) return;
        List<Act> list = new ArrayList<>();
        for (Act a : acts) if (a != null) list.add(a);
        MAP.put(v, list);
        v.setAccessibilityDelegate(new Del(base));
    }
}

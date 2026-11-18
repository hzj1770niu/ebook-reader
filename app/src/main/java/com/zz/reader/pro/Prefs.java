package com.zz.reader.pro;

import android.content.Context;
import android.content.SharedPreferences;

import com.zz.reader.pro.core.PageStyle;

import java.util.ArrayList;
import java.util.List;

/** 全部阅读设置在本地保存，退出应用、换书都沿用 */
public class Prefs {

    private static final String NAME = "reader_prefs_pro";

    private final SharedPreferences sp;

    private static SharedPreferences sp0(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    private final Context ctx;

    public Prefs(Context c) {
        ctx = c;
        // 3.1 起主线改成连续滚动：升级时强制切一次，页末哨兵默认关（它只在分页模式里用）
        if (!sp0(c).getBoolean("flow_forced", false)) {
            sp0(c).edit().putBoolean("continuous", true)
                    .putBoolean("auto_turn_focus", false)
                    .putBoolean("flow_forced", true).apply();
        }
        // 3.8：主线回到"一屏一屏整页翻"，老用户也切一次（读屏连读需要时可在设置里手动打开连续滚动）
        if (!sp0(c).getBoolean("paged_forced", false)) {
            sp0(c).edit().putBoolean("continuous", false)
                    .putBoolean("paged_forced", true).apply();
        }
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public PageStyle style() {
        PageStyle s = new PageStyle();
        s.textSizeSp = get("font_size", 20);
        s.lineMult = sp.getInt("line_mult", 155) / 100f;
        s.paraSpacingDp = get("para_spacing", 10);
        s.indentChars = get("indent", 2);
        s.marginHorizontalDp = get("margin_h", 18);
        s.fontFamily = get("font_family", PageStyle.FONT_SANS);
        s.bold = sp.getBoolean("bold", false);
        s.justify = sp.getBoolean("justify", true);
        s.theme = get("theme", PageStyle.THEME_WHITE);
        s.showHeader = sp.getBoolean("show_header", true);
        s.showFooter = sp.getBoolean("show_footer", true);
        s.continuousScroll = sp.getBoolean("continuous", false);
        s.autoTurnOnFocus = sp.getBoolean("auto_turn_focus", false);
        s.pageAnim = get("page_anim", 1);
        s.volumeTurn = sp.getBoolean("volume_turn", true);
        s.autoSpeak = sp.getBoolean("auto_speak", false);
        s.keepScreenOn = sp.getBoolean("keep_screen_on", false);
        s.showProgressBar = sp.getBoolean("show_progress", true);
        s.ttsSpeed = sp.getInt("tts_speed", 100) / 100f;
        s.ocrProof = sp.getBoolean("ocr_proof", false);
        return s;
    }

    public void save(PageStyle s) {
        sp.edit()
                .putInt("font_size", s.textSizeSp)
                .putInt("line_mult", Math.round(s.lineMult * 100))
                .putInt("para_spacing", s.paraSpacingDp)
                .putInt("indent", s.indentChars)
                .putInt("margin_h", s.marginHorizontalDp)
                .putInt("font_family", s.fontFamily)
                .putBoolean("bold", s.bold)
                .putBoolean("justify", s.justify)
                .putInt("theme", s.theme)
                .putBoolean("show_header", s.showHeader)
                .putBoolean("show_footer", s.showFooter)
                .putBoolean("continuous", s.continuousScroll)
                .putBoolean("auto_turn_focus", s.autoTurnOnFocus)
                .putBoolean("auto_turn_focus", s.autoTurnOnFocus)
                .putInt("page_anim", s.pageAnim)
                .putBoolean("volume_turn", s.volumeTurn)
                .putBoolean("auto_speak", s.autoSpeak)
                .putBoolean("keep_screen_on", s.keepScreenOn)
                .putBoolean("show_progress", s.showProgressBar)
                .putInt("tts_speed", Math.round(s.ttsSpeed * 100))
                .putBoolean("ocr_proof", s.ocrProof)
                .apply();
    }

    /** 读屏在用时，默认走连续流（读屏可以一路连续听下去）；关掉读屏默认走分页 */
    private boolean touchExplorationOn() {
        try {
            android.view.accessibility.AccessibilityManager am =
                    (android.view.accessibility.AccessibilityManager)
                            ctx.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
            return am != null && am.isTouchExplorationEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private int get(String key, int def) {
        return sp.getInt(key, def);
    }

    public String encodingChoice() {
        return sp.getString("encoding", "自动检测");
    }

    public boolean proofOcr() {
        return sp.getBoolean("ocr_proof", false);
    }

    public void setEncodingChoice(String v) {
        sp.edit().putString("encoding", v).apply();
    }

    /** 每本书自己的编码记忆；没设过就自动检测 */
    public String encodingFor(String bookKey) {
        return sp.getString("enc:" + bookKey, "自动检测");
    }

    public void setEncodingFor(String bookKey, String v) {
        sp.edit().putString("enc:" + bookKey, v).apply();
    }

    public static final String[] ENCODINGS =
            {"自动检测", "UTF-8", "GB18030", "GBK", "BIG5", "UTF-16LE", "UTF-16BE"};

    public List<String> themes() {
        List<String> l = new ArrayList<>();
        l.add("白底黑字");
        l.add("护眼米黄");
        l.add("浅绿护眼");
        l.add("深色");
        l.add("纯黑");
        l.add("黑底黄字高对比");
        return l;
    }
}

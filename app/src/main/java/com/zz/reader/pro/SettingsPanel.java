package com.zz.reader.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zz.reader.pro.core.PageStyle;

/**
 * 阅读设置面板（对应 Kindle 的 Aa 面板）：文字、布局、外观、朗读、文件五组。
 * 所有控件都是带文字的原生按钮，读屏能念出名字和当前值。
 */
public class SettingsPanel {

    public interface Callback {
        /** 只影响配色这类不改排版的变化 */
        void onThemeChanged();

        /** 影响排版，需要重新分页 */
        void onLayoutChanged();

        /** 当前这本书的 TXT 编码 */
        String encoding();

        /** 换编码后会按新编码重新读一遍文件 */
        void setEncoding(String v);
    }

    private final Activity act;
    private final PageStyle s;
    private final Prefs prefs;
    private final Callback cb;

    private SettingsPanel(Activity act, PageStyle s, Prefs prefs, Callback cb) {
        this.act = act;
        this.s = s;
        this.prefs = prefs;
        this.cb = cb;
    }

    public static void show(Activity act, PageStyle style, Prefs prefs, Callback cb) {
        new SettingsPanel(act, style, prefs, cb).build().show();
    }

    private AlertDialog build() {
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, dp(8));

        // ---- 文字
        header(root, "文字");
        numberRow(root, "字号", () -> s.textSizeSp + " 号", 14, 40, 1,
                () -> s.textSizeSp, v -> s.textSizeSp = v);
        numberRow(root, "行距", () -> String.format(java.util.Locale.CHINA, "%.1f 倍", s.lineMult),
                10, 24, 1, () -> Math.round(s.lineMult * 10), v -> s.lineMult = v / 10f);
        numberRow(root, "段落间距", () -> s.paraSpacingDp + " 点", 0, 24, 2,
                () -> s.paraSpacingDp, v -> s.paraSpacingDp = v);
        numberRow(root, "首行缩进", () -> s.indentChars == 0 ? "不缩进" : s.indentChars + " 个字",
                0, 2, 1, () -> s.indentChars, v -> s.indentChars = v);
        switchRow(root, "字体", () -> fontName(),
                () -> s.fontFamily = (s.fontFamily + 1) % 3);
        switchRow(root, "字重", () -> s.bold ? "加粗" : "正常",
                () -> s.bold = !s.bold);

        // ---- 布局
        header(root, "布局");
        numberRow(root, "左右页边距", () -> s.marginHorizontalDp + " 点", 6, 40, 2,
                () -> s.marginHorizontalDp, v -> s.marginHorizontalDp = v);
        switchRow(root, "对齐方式", () -> s.justify ? "两端对齐" : "左对齐",
                () -> s.justify = !s.justify);
        note(root, "提示：连续滚动是给读屏连续朗读用的。打开后正文会一直往上滚，手动左右滑动翻页会受影响，只用手指翻页请保持关闭。"
                + "既想左右滑动翻页、又想一路读下去，点工具栏里的朗读按钮，用软件自己的朗读。");
        switchRow(root, "连续滚动（让读屏连续朗读一路读下去）",
                () -> s.continuousScroll ? "开启" : "关闭",
                () -> s.continuousScroll = !s.continuousScroll);
        switchRow(root, "页眉（书名章节）", () -> s.showHeader ? "显示" : "隐藏",
                () -> s.showHeader = !s.showHeader);
        switchRow(root, "页脚（页码进度）", () -> s.showFooter ? "显示" : "隐藏",
                () -> s.showFooter = !s.showFooter);
        switchRow(root, "顶部进度细线", () -> s.showProgressBar ? "显示" : "隐藏",
                () -> s.showProgressBar = !s.showProgressBar);

        // ---- 外观
        header(root, "外观");
        switchRow(root, "主题配色", () -> themeName(), () -> {
            s.theme = (s.theme + 1) % 6;
            prefs.save(s);
            cb.onThemeChanged();
        });
        switchRow(root, "读屏念到页末自动翻页",
                () -> s.autoTurnOnFocus ? "开启" : "关闭",
                () -> s.autoTurnOnFocus = !s.autoTurnOnFocus);
        switchRow(root, "翻页动画", () -> s.pageAnim == 0 ? "无动画" : "淡入",
                () -> s.pageAnim = s.pageAnim == 0 ? 1 : 0);

        // ---- 朗读
        header(root, "朗读");
        numberRow(root, "朗读语速", () -> String.format(java.util.Locale.CHINA, "%.1f 倍", s.ttsSpeed),
                5, 30, 1, () -> Math.round(s.ttsSpeed * 10), v -> s.ttsSpeed = v / 10f);
        switchRow(root, "打开书自动朗读（同时隐藏工具栏）", () -> s.autoSpeak ? "开启" : "关闭",
                () -> s.autoSpeak = !s.autoSpeak);
        switchRow(root, "音量键翻页", () -> s.volumeTurn ? "开启" : "关闭",
                () -> s.volumeTurn = !s.volumeTurn);
        switchRow(root, "阅读时屏幕常亮", () -> s.keepScreenOn ? "开启" : "关闭",
                () -> s.keepScreenOn = !s.keepScreenOn);

        // ---- 文件
        header(root, "文件");
        switchRow(root, "文字层校对（PDF 每页重新识别）", () -> s.ocrProof ? "开启" : "关闭",
                () -> s.ocrProof = !s.ocrProof);
        note(root, "提示：文字版 PDF 的文字层有时本身就带错字，打开校对以后每页都用本地识别重跑一遍，"
                + "以识别结果为准，错字会少，但第一次打开会慢不少（每页一两秒）。");
        switchRow(root, "TXT 文字编码", cb::encoding, () -> {
            String[] all = Prefs.ENCODINGS;
            String cur = cb.encoding();
            int idx = 0;
            for (int i = 0; i < all.length; i++) {
                if (all[i].equals(cur)) {
                    idx = i;
                    break;
                }
            }
            cb.setEncoding(all[(idx + 1) % all.length]);
        });

        ScrollView sv = new ScrollView(act);
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final PageStyle snapshot = s.copy();
        return new AlertDialog.Builder(act)
                .setTitle("阅读设置")
                .setView(sv)
                .setPositiveButton("完成", (d, w) -> cb.onLayoutChanged())
                .setNegativeButton("取消", (d, w) -> {
                    s.copyFrom(snapshot);
                    prefs.save(s);
                    cb.onThemeChanged();
                    cb.onLayoutChanged();
                })
                .create();
    }

    private String fontName() {
        if (s.fontFamily == PageStyle.FONT_SERIF) return "衬线（宋体风格）";
        if (s.fontFamily == PageStyle.FONT_MONO) return "等宽";
        return "无衬线（黑体风格）";
    }

    private String themeName() {
        switch (s.theme) {
            case PageStyle.THEME_SEPIA:
                return "护眼米黄";
            case PageStyle.THEME_GREEN:
                return "浅绿护眼";
            case PageStyle.THEME_DARK:
                return "深色";
            case PageStyle.THEME_BLACK:
                return "纯黑";
            case PageStyle.THEME_CONTRAST:
                return "黑底黄字高对比";
            default:
                return "白底黑字";
        }
    }

    /** 一行说明文字，读屏能读到 */
    private void note(LinearLayout parent, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setFocusable(true);
        tv.setContentDescription(text);
        tv.setPadding(0, dp(8), 0, dp(4));
        tv.setAlpha(0.8f);
        parent.addView(tv);
    }

    private void header(LinearLayout parent, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(17);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setFocusable(true);
        tv.setContentDescription(text + "，这一组设置");
        tv.setPadding(0, dp(14), 0, dp(4));
        parent.addView(tv);
    }

    /** 数值型设置：一行里放标签、减号、加号，点按一次改一档 */
    private void numberRow(LinearLayout parent, String label, ValueText valueText,
                           final int min, final int max, final int step,
                           IntGetter getter, IntSetter setter) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final TextView tv = new TextView(act);
        tv.setTextSize(15);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        tv.setText(label + "：" + valueText.get());
        tv.setContentDescription(label + "，当前 " + valueText.get());

        Button minus = new Button(act);
        minus.setText("－");
        minus.setTextSize(16);
        minus.setMinWidth(dp(62));
        minus.setMinHeight(dp(52));
        minus.setContentDescription("把" + label + "调小");

        Button plus = new Button(act);
        plus.setText("＋");
        plus.setTextSize(16);
        plus.setMinWidth(dp(62));
        plus.setMinHeight(dp(52));
        plus.setContentDescription("把" + label + "调大");

        minus.setOnClickListener(v -> {
            int nv = Math.max(min, getter.get() - step);
            setter.set(nv);
            prefs.save(s);
            tv.setText(label + "：" + valueText.get());
            tv.setContentDescription(label + "，当前 " + valueText.get());
            tv.announceForAccessibility(label + " " + valueText.get());
        });
        plus.setOnClickListener(v -> {
            int nv = Math.min(max, getter.get() + step);
            setter.set(nv);
            prefs.save(s);
            tv.setText(label + "：" + valueText.get());
            tv.setContentDescription(label + "，当前 " + valueText.get());
            tv.announceForAccessibility(label + " " + valueText.get());
        });

        row.addView(tv);
        row.addView(minus);
        row.addView(plus);
        parent.addView(row);
    }

    /** 开关或循环切换型设置：整行一个按钮，点一下换到下一个值 */
    private void switchRow(LinearLayout parent, String label, ValueText valueText, final Runnable action) {
        Button btn = new Button(act);
        btn.setAllCaps(false);
        btn.setTextSize(15);
        btn.setMinHeight(dp(56));
        btn.setText(label + "：" + valueText.get());
        btn.setContentDescription(label + "，当前 " + valueText.get() + "，点按切换");
        btn.setOnClickListener(v -> {
            action.run();
            btn.setText(label + "：" + valueText.get());
            btn.setContentDescription(label + "，当前 " + valueText.get() + "，点按切换");
            btn.announceForAccessibility(label + " " + valueText.get());
        });
        parent.addView(btn);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                act.getResources().getDisplayMetrics()));
    }

    private interface ValueText {
        String get();
    }

    private interface IntGetter {
        int get();
    }

    private interface IntSetter {
        void set(int v);
    }
}

package com.zz.reader.pro.core;

import android.graphics.Color;

/** 一套排版样式：和阅读器里"文字/布局/主题"三组设置一一对应 */
public class PageStyle {

    public static final int THEME_WHITE = 0;
    public static final int THEME_SEPIA = 1;
    public static final int THEME_GREEN = 2;
    public static final int THEME_DARK = 3;
    public static final int THEME_BLACK = 4;
    public static final int THEME_CONTRAST = 5;

    public static final int FONT_SANS = 0;
    public static final int FONT_SERIF = 1;
    public static final int FONT_MONO = 2;

    public int textSizeSp = 20;
    public float lineMult = 1.55f;
    public int paraSpacingDp = 10;
    public int indentChars = 2;
    public int marginHorizontalDp = 18;
    public int marginTopDp = 8;
    public int fontFamily = FONT_SANS;
    public boolean bold = false;
    public boolean justify = true;
    public int theme = THEME_WHITE;

    public boolean showHeader = true;
    public boolean showFooter = true;
    public boolean continuousScroll = false;
    /** 读屏念到页末（哨兵节点）时自动翻下一页 */
    public boolean autoTurnOnFocus = true;
    public int pageAnim = 1;          // 0 无动画，1 淡入，2 横移
    public boolean volumeTurn = true;
    public boolean autoSpeak = false;
    public boolean keepScreenOn = false;
    public boolean showProgressBar = true;
    public float ttsSpeed = 1.0f;
    /** 文字层校对：文字版 PDF 每页都用本地识别再跑一遍，以识别结果为准 */
    public boolean ocrProof = false;

    /** 复制一份同样式的快照，设置面板点"取消"时用来自个还原 */
    public void copyFrom(PageStyle o) {
        textSizeSp = o.textSizeSp;
        lineMult = o.lineMult;
        paraSpacingDp = o.paraSpacingDp;
        indentChars = o.indentChars;
        marginHorizontalDp = o.marginHorizontalDp;
        marginTopDp = o.marginTopDp;
        fontFamily = o.fontFamily;
        bold = o.bold;
        justify = o.justify;
        theme = o.theme;
        showHeader = o.showHeader;
        showFooter = o.showFooter;
        continuousScroll = o.continuousScroll;
        autoTurnOnFocus = o.autoTurnOnFocus;
        pageAnim = o.pageAnim;
        volumeTurn = o.volumeTurn;
        autoSpeak = o.autoSpeak;
        keepScreenOn = o.keepScreenOn;
        showProgressBar = o.showProgressBar;
        ttsSpeed = o.ttsSpeed;
        ocrProof = o.ocrProof;
    }

    public PageStyle copy() {
        PageStyle p = new PageStyle();
        p.copyFrom(this);
        return p;
    }

    public int bgColor() {
        switch (theme) {
            case THEME_SEPIA:
                return 0xFFF5EDDC;
            case THEME_GREEN:
                return 0xFFE4EFDD;
            case THEME_DARK:
                return 0xFF1F1F1F;
            case THEME_BLACK:
                return 0xFF000000;
            case THEME_CONTRAST:
                return 0xFF000000;
            default:
                return 0xFFFFFFFF;
        }
    }

    public int fgColor() {
        switch (theme) {
            case THEME_SEPIA:
                return 0xFF3E3327;
            case THEME_GREEN:
                return 0xFF23321F;
            case THEME_DARK:
                return 0xFFCFCFCF;
            case THEME_BLACK:
                return 0xFFE6E6E6;
            case THEME_CONTRAST:
                return 0xFFFFE600;
            default:
                return 0xFF151515;
        }
    }

    /** 页眉页脚这类次要信息用稍微淡一点的前景色 */
    public int dimColor() {
        int fg = fgColor();
        return Color.argb(0x9E, Color.red(fg), Color.green(fg), Color.blue(fg));
    }
}

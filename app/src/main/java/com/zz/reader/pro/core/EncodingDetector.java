package com.zz.reader.pro.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * 编码识别器。
 *
 * 目标：TXT 只要文件本身没有损坏，就不会显示乱码。
 * 策略：
 *  1. BOM 优先（UTF-8 / UTF-16LE / UTF-16BE）。
 *  2. UTF-8 严格校验：只要全文件解码不出现非法序列，就判定 UTF-8。
 *  3. 双字节中文区段统计：GB18030/GBK 与 BIG5 的合法双字节区段不同，
 *     用真实命中率 + 非法字节数打分，取分数高者。
 *  4. 兜底 GB18030（它是 GBK 的超集，覆盖绝大多数简体中文旧书）。
 */
public final class EncodingDetector {

    public static final String UTF8 = "UTF-8";
    public static final String GB18030 = "GB18030";
    public static final String BIG5 = "BIG5";
    public static final String UTF16LE = "UTF-16LE";
    public static final String UTF16BE = "UTF-16BE";

    /** 可供用户手动指定的编码列表 */
    public static final String[] CHOICES = {"自动检测", UTF8, GB18030, "GBK", BIG5, UTF16LE, UTF16BE};

    private EncodingDetector() {
    }

    public static String detect(byte[] data) {
        if (data == null || data.length == 0) return UTF8;

        // 1. BOM
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return UTF8;
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return UTF16LE;
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return UTF16BE;
        }

        // 2. UTF-8 严格校验（抽样，避免大文件耗时）
        int utf8Score = utf8Confidence(data);
        if (utf8Score >= 99) {
            return UTF8;
        }

        // 3. 双字节区段打分：默认走 GB18030（简体中文书的主流）。
        // 繁体 BIG5 与 GBK 的双字节区段高度重叠，自动判断容易误判成乱码，
        // 所以繁体书交给用户在设置里手动选一次 BIG5，比乱猜更可靠。
        int gb = gbScore(data);
        int big5 = big5Score(data);
        if (gb == 0 && big5 == 0) {
            return utf8Score > 70 ? UTF8 : GB18030;
        }
        // 只有 GB 明显不合法、而 BIG5 几乎全合法时，才自动判为 BIG5
        if (gb < 85 && big5 >= 95) {
            return BIG5;
        }
        return GB18030;
    }

    /** 返回 0~100，100 表示整段样本都是合法 UTF-8 且含多字节字符 */
    private static int utf8Confidence(byte[] data) {
        int i = 0;
        int n = data.length;
        int multiByte = 0;
        int ascii = 0;
        while (i < n) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                ascii++;
                i++;
                continue;
            }
            if ((b & 0xE0) == 0xC0) {
                if (i + 1 >= n || (data[i + 1] & 0xC0) != 0x80) return 60;
                multiByte++;
                i += 2;
            } else if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= n || (data[i + 1] & 0xC0) != 0x80 || (data[i + 2] & 0xC0) != 0x80) return 60;
                multiByte++;
                i += 3;
            } else if ((b & 0xF8) == 0xF0) {
                if (i + 3 >= n || (data[i + 1] & 0xC0) != 0x80 || (data[i + 2] & 0xC0) != 0x80
                        || (data[i + 3] & 0xC0) != 0x80) return 60;
                multiByte++;
                i += 4;
            } else {
                return 60;
            }
        }
        if (multiByte == 0 && ascii == 0) return 50;
        // 纯 ASCII 也是合法 UTF-8
        return 100;
    }

    /** GBK/GB18030 双字节合法率打分 */
    private static int gbScore(byte[] data) {
        int hit = 0;
        int bad = 0;
        int n = Math.min(data.length, 1 << 20);
        int i = 0;
        while (i < n) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            if (b == 0x80 || b == 0xFF) {
                bad++;
                i++;
                continue;
            }
            if (i + 1 >= n) break;
            int b2 = data[i + 1] & 0xFF;
            if ((b2 >= 0x40 && b2 <= 0x7E) || (b2 >= 0x80 && b2 <= 0xFE)) {
                hit++;
                i += 2;
            } else {
                bad++;
                i++;
            }
        }
        if (hit + bad == 0) return 0;
        return (int) (100L * hit / (hit + bad));
    }

    /** BIG5 双字节合法率打分 */
    private static int big5Score(byte[] data) {
        int hit = 0;
        int bad = 0;
        int n = Math.min(data.length, 1 << 20);
        int i = 0;
        while (i < n) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                i++;
                continue;
            }
            if (b < 0xA1 || b > 0xF9) {
                bad++;
                i++;
                continue;
            }
            if (i + 1 >= n) break;
            int b2 = data[i + 1] & 0xFF;
            if ((b2 >= 0x40 && b2 <= 0x7E) || (b2 >= 0xA1 && b2 <= 0xFE)) {
                hit++;
                i += 2;
            } else {
                bad++;
                i++;
            }
        }
        if (hit + bad == 0) return 0;
        return (int) (100L * hit / (hit + bad));
    }

    /** 按指定编码严格解码；失败返回 null */
    public static String decodeStrict(byte[] data, String charsetName) {
        try {
            Charset cs = Charset.forName(charsetName);
            CharsetDecoder dec = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String s = dec.decode(ByteBuffer.wrap(data)).toString();
            return stripBom(s);
        } catch (CharacterCodingException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String decodeLenient(byte[] data, String charsetName) {
        try {
            String s = new String(data, Charset.forName(charsetName));
            return stripBom(s);
        } catch (Exception e) {
            return stripBom(new String(data, Charset.forName(GB18030)));
        }
    }

    public static String stripBom(String s) {
        if (s == null) return "";
        if (s.startsWith("\uFEFF")) s = s.substring(1);
        return s;
    }

    /**
     * 统计替换字符数量，用来判断一次解码是不是"看起来乱码"。
     */
    public static int garbageCount(String s) {
        if (s == null) return 0;
        int count = 0;
        int n = Math.min(s.length(), 200000);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\uFFFD') count++;
        }
        return count;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }
}

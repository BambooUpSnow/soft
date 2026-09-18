package com.signlanguage.app.ble;

/**
 * GB2312(GBK) -> Unicode 解码器。
 *
 * <p>安卓从 API 24 起移除了内置的 GB2312/GBK Charset，{@code new String(bytes, "GB2312")}
 * 会抛 UnsupportedEncodingException，因此这里自带一张 94x94 区位表
 * （见 {@link Gb2312Table}，由 tools/gen_gb2312_table.py 生成）。
 *
 * <p>算法：逐字节扫描。
 * <ul>
 *   <li>0x00-0x7F：ASCII 直通（协议里的 "Gesture: "、"HR=" 等英文前缀都在这一段）</li>
 *   <li>0x81-0xFE 且下一字节 0x40-0xFE：双字节 GBK 汉字，查表</li>
 *   <li>其他：当成半角/非法字符，跳过或按原样输出</li>
 * </ul>
 */
public final class Gb2312Decoder {

    /** GB2312 区位表里 0xA1A1 是 U+3000 全角空格 */
    private static final int UNDEFINED = 0xFFFF;

    private Gb2312Decoder() {
    }

    public static String decode(byte[] data, int offset, int length) {
        if (data == null || length <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(length);
        int end = offset + length;
        int i = offset;
        while (i < end) {
            int b = data[i] & 0xFF;
            if (b < 0x80) {
                sb.append((char) b);
                i++;
                continue;
            }
            if (b < 0x81) {
                // 0x80 不是合法首字节
                i++;
                continue;
            }
            if (i + 1 >= end) {
                // 双字节被截断：丢掉，等下一个数据块会重新补齐（正常不会发生，
                // 因为调用方按行缓冲）
                i++;
                continue;
            }
            int b2 = data[i + 1] & 0xFF;
            if (b2 < 0x40 || b2 == 0x7F) {
                // 非法第二字节，丢掉首字节
                i++;
                continue;
            }
            int code = lookup(b, b2);
            if (code > 0 && code != UNDEFINED) {
                sb.append((char) code);
                i += 2;
            } else {
                // 表里没有：用占位符，保证不会把双字节错当成两个ASCII
                sb.append('\uFFFD');
                i += 2;
            }
        }
        return sb.toString();
    }

    /**
     * 双字节 -> 区/位 -> 查表。
     *
     * <p>GB2312 的区号和位号，就是编码字节减去 0xA0：
     * <pre>
     *   区号 row  = b1 - 0xA0   （0xA1-0xFE -> 1-94）
     *   位号 cell = b2 - 0xA0   （0xA1-0xFE -> 1-94）
     * </pre>
     * 所以 "啊"(0xB0A1) 是第 16 区第 1 位。
     *
     * <p>b2 落在 0x40-0x7E 时属于 GBK 扩展区（位号 95-126），本项目的固件用不到，
     * 这里按越过 94 处理，让查表返回空。
     */
    private static int lookup(int b1, int b2) {
        if (b2 >= 0xA1 && b2 <= 0xFE) {
            return Gb2312Table.lookup(b1 - 0xA0, b2 - 0xA0);
        }
        return 0;
    }

    /** UTF-8 解码（备用：如果以后把固件改成发 UTF-8，在设置里切换即可） */
    public static String decodeUtf8(byte[] data, int offset, int length) {
        return new String(data, offset, length, java.nio.charset.StandardCharsets.UTF_8);
    }
}

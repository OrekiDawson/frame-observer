package com.oreki.frameobserver.util;

/**
 * 极简 JSON 序列化器。
 *
 * <p>为什么手写而不引第三方库：
 * <ul>
 *   <li>本工具要输出 <b>JSONL</b>（每行一个 JSON），对「单行、无换行、字段顺序稳定」有硬要求，
 *       常见库默认会美化换行；</li>
 *   <li>不引依赖可以让离线命令行构建只依赖 android.jar；</li>
 *   <li>字段全部由本类控制，便于保证 {@code null} 一律输出为 JSON {@code null} 而不是字符串
 *       {@code "null"} —— 下游按 schema 解析时这两者语义完全不同。</li>
 * </ul>
 *
 * <p>注意：本类不是通用 JSON 库，只做「对象 + 标量字段」这一种形状。数组请用
 * {@link #putRaw(String, String)} 传入手工拼好的片段（本工程里只有控件树的 children 用到）。
 */
public final class Json {

    private final StringBuilder sb;
    private boolean first = true;

    private Json() {
        this.sb = new StringBuilder(512);
        sb.append('{');
    }

    public static Json obj() {
        return new Json();
    }

    public Json put(String key, String value) {
        key(key);
        if (value == null) {
            sb.append("null");
        } else {
            quote(value);
        }
        return this;
    }

    public Json put(String key, long value) {
        key(key);
        sb.append(value);
        return this;
    }

    public Json put(String key, int value) {
        key(key);
        sb.append(value);
        return this;
    }

    public Json put(String key, boolean value) {
        key(key);
        sb.append(value);
        return this;
    }

    /** 浮点统一保留 3 位小数：既避免 0.30000000000000004 这种噪声，也控制日志体积。 */
    public Json put(String key, double value) {
        key(key);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            sb.append("null");
        } else {
            sb.append(round3(value));
        }
        return this;
    }

    /** 直接塞入已构造好的 JSON 片段（对象或数组）。调用方负责片段自身合法。 */
    public Json putRaw(String key, String rawJson) {
        key(key);
        sb.append(rawJson == null ? "null" : rawJson);
        return this;
    }

    public String end() {
        sb.append('}');
        return sb.toString();
    }

    @Override
    public String toString() {
        return end();
    }

    private void key(String key) {
        if (!first) {
            sb.append(',');
        }
        first = false;
        quote(key);
        sb.append(':');
    }

    private void quote(String s) {
        sb.append('"');
        final int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                default:
                    // 控制字符必须转义，否则 JSONL 会被解析器拒绝；中文保持原样（UTF-8 输出）。
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static String round3(double v) {
        long scaled = Math.round(v * 1000.0);
        return (scaled / 1000L) + "." + String.format("%03d", Math.abs(scaled % 1000L));
    }
}

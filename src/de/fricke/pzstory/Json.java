package de.fricke.pzstory;

/**
 * Minimal JSON writer. Deliberately dependency-free: the mod JAR stays a few
 * kilobytes and there is no shading, no version conflict with whatever the
 * game or another Java mod already has on the classpath.
 *
 * Not a general-purpose library. It writes objects and arrays, escapes
 * strings correctly, and refuses to emit NaN/Infinity (which are not legal
 * JSON and would poison a prompt).
 */
public final class Json {

    private final StringBuilder sb = new StringBuilder(4096);
    private boolean needComma = false;

    public Json obj() {
        comma();
        sb.append('{');
        needComma = false;
        return this;
    }

    public Json endObj() {
        sb.append('}');
        needComma = true;
        return this;
    }

    public Json arr() {
        comma();
        sb.append('[');
        needComma = false;
        return this;
    }

    public Json endArr() {
        sb.append(']');
        needComma = true;
        return this;
    }

    /** Opens a named object: "key":{ */
    public Json objKey(String key) {
        key(key);
        sb.append('{');
        needComma = false;
        return this;
    }

    /** Opens a named array: "key":[ */
    public Json arrKey(String key) {
        key(key);
        sb.append('[');
        needComma = false;
        return this;
    }

    public Json put(String key, String value) {
        if (value == null) return this;
        key(key);
        str(value);
        needComma = true;
        return this;
    }

    public Json put(String key, boolean value) {
        key(key);
        sb.append(value);
        needComma = true;
        return this;
    }

    public Json put(String key, long value) {
        key(key);
        sb.append(value);
        needComma = true;
        return this;
    }

    /** Rounds to 2 decimals: prompts do not need float noise. */
    public Json put(String key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return this;
        key(key);
        sb.append(Math.round(value * 100.0) / 100.0);
        needComma = true;
        return this;
    }

    /** Bare array element. */
    public Json val(String value) {
        comma();
        str(value);
        needComma = true;
        return this;
    }

    private void key(String k) {
        comma();
        str(k);
        sb.append(':');
    }

    private void comma() {
        if (needComma) sb.append(',');
        needComma = false;
    }

    private void str(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    /** Writes back a parsed structure. Used to keep a trimmed snapshot. */
    public static String of(Object o) {
        StringBuilder b = new StringBuilder(4096);
        write(b, o);
        return b.toString();
    }

    private static void write(StringBuilder b, Object o) {
        if (o == null) { b.append("null"); return; }
        if (o instanceof String s) { b.append(new Json().quoted(s)); return; }
        if (o instanceof Boolean || o instanceof Number) {
            String t = String.valueOf(o);
            b.append(t.endsWith(".0") ? t.substring(0, t.length() - 2) : t);
            return;
        }
        if (o instanceof java.util.Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(new Json().quoted(String.valueOf(e.getKey()))).append(':');
                write(b, e.getValue());
            }
            b.append('}');
            return;
        }
        if (o instanceof java.util.List<?> l) {
            b.append('[');
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) b.append(',');
                write(b, l.get(i));
            }
            b.append(']');
            return;
        }
        b.append(new Json().quoted(String.valueOf(o)));
    }

    private String quoted(String s) {
        StringBuilder t = new StringBuilder(s.length() + 2);
        StringBuilder save = new StringBuilder(this.sb);
        this.sb.setLength(0);
        str(s);
        t.append(this.sb);
        this.sb.setLength(0);
        this.sb.append(save);
        return t.toString();
    }
}

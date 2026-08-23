package de.fricke.pzstory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader - the counterpart to {@link Json}.
 *
 * Dependency-free on purpose: the mod JAR stays tiny and cannot collide with
 * whatever JSON library the game or another Java mod already has loaded.
 *
 * Produces Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Double, Boolean, null.
 * Accepts a leading UTF-8 BOM, because Notepad on Windows writes one and the
 * player editing profiles.json by hand should not be punished for that.
 */
public final class JsonParse {

    private final String s;
    private int i;
    /**
     * Nesting depth, guarded.
     *
     * value() -> object()/array() -> value() recurses with no natural bound,
     * so a document nested a few thousand levels deep overflows the Java stack
     * and takes the game down with it. We parse three things we do not fully
     * control - the campaign store on disk, profiles.json, and whatever a
     * provider streams back - and a StackOverflowError in any of them is a
     * crash rather than a caught failure. Real JSON is never deep.
     */
    private int depth;
    private static final int MAX_DEPTH = 200;
    private static final int MAX_INPUT_CHARS = 32 * 1024 * 1024;

    private JsonParse(String s) {
        this.s = s;
        this.i = 0;
    }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("null json");
        if (text.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException(
                    "json exceeds " + MAX_INPUT_CHARS + " characters");
        }
        // Strip UTF-8 BOM if Notepad left one.
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        JsonParse p = new JsonParse(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) {
            throw new IllegalStateException("trailing characters at offset " + p.i);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalStateException("expected a JSON object");
        return (Map<String, Object>) v;
    }

    private Object value() {
        if (i >= s.length()) throw new IllegalStateException("unexpected end of input");
        char c = s.charAt(i);
        switch (c) {
            case '{': case '[': {
                if (++depth > MAX_DEPTH) {
                    throw new IllegalStateException(
                            "json nested deeper than " + MAX_DEPTH + " levels");
                }
                Object v = (c == '{') ? object() : array();
                depth--;
                return v;
            }
            case '"': return string();
            case 't': expect("true");  return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null");  return null;
            default:  return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;                       // {
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            if (peek() != ':') throw new IllegalStateException("expected ':' at offset " + i);
            i++;
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw new IllegalStateException("expected ',' or '}' at offset " + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;                       // [
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return l; }
            throw new IllegalStateException("expected ',' or ']' at offset " + i);
        }
    }

    private String string() {
        if (peek() != '"') throw new IllegalStateException("expected string at offset " + i);
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new IllegalStateException("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c < 0x20) {
                throw new IllegalStateException(
                        "unescaped control character at offset " + (i - 1));
            }
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) throw new IllegalStateException("unterminated escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"'  -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/'  -> sb.append('/');
                case 'b'  -> sb.append('\b');
                case 'f'  -> sb.append('\f');
                case 'n'  -> sb.append('\n');
                case 'r'  -> sb.append('\r');
                case 't'  -> sb.append('\t');
                case 'u'  -> {
                    if (i + 4 > s.length()) {
                        throw new IllegalStateException("incomplete unicode escape at offset " + i);
                    }
                    int code;
                    try {
                        code = Integer.parseInt(s.substring(i, i + 4), 16);
                    } catch (NumberFormatException badHex) {
                        throw new IllegalStateException("bad unicode escape at offset " + i);
                    }
                    sb.append((char) code);
                    i += 4;
                }
                default -> throw new IllegalStateException("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = i;
        if (peek() == '-') i++;

        if (i >= s.length()) throw badNumber(start);
        if (s.charAt(i) == '0') {
            i++;
            if (i < s.length() && digit(s.charAt(i))) throw badNumber(start);
        } else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') {
            while (i < s.length() && digit(s.charAt(i))) i++;
        } else {
            throw badNumber(start);
        }

        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            if (i >= s.length() || !digit(s.charAt(i))) throw badNumber(start);
            while (i < s.length() && digit(s.charAt(i))) i++;
        }

        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            if (i >= s.length() || !digit(s.charAt(i))) throw badNumber(start);
            while (i < s.length() && digit(s.charAt(i))) i++;
        }

        Double value;
        try {
            value = Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException malformed) {
            throw badNumber(start);
        }
        if (!Double.isFinite(value)) {
            throw new IllegalStateException("number is not finite at offset " + start);
        }
        return value;
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static IllegalStateException badNumber(int offset) {
        return new IllegalStateException("invalid number at offset " + offset);
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, i)) throw new IllegalStateException("expected " + lit + " at offset " + i);
        i += lit.length();
    }

    private char peek() {
        if (i >= s.length()) throw new IllegalStateException("unexpected end of input");
        return s.charAt(i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    // ------------------------------------------------------------ accessors

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o, String key) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof Map) {
            return (Map<String, Object>) m.get(key);
        }
        return null;
    }

    public static String str(Object o, String key, String def) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof String v) return v;
        return def;
    }

    public static int num(Object o, String key, int def) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof Double v) return (int) (double) v;
        return def;
    }
}

package com.madan.urlshortener.agent.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full JSON reader/writer for the agent layer (nested objects and arrays).
 *
 * The service's own {@code http/JsonUtil} deliberately parses only FLAT objects, which is all the
 * REST API needs. The agent needs more: Claude responses and tool arguments are nested JSON.
 * Still dependency-free so the project keeps compiling with just the JDK; in production: Jackson.
 *
 * Objects become {@code Map<String, Object>}, arrays {@code List<Object>}, numbers {@code Long}
 * (whole) or {@code Double}, plus String, Boolean and null.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, list.get(i));
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != text.length()) {
            throw new IllegalArgumentException("Unexpected trailing characters at position " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object readValue() {
            skipWhitespace();
            if (pos >= s.length()) throw error("Unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object readNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String num = s.substring(start, pos);
            if (num.isEmpty()) throw error("Unexpected character '" + s.charAt(start) + "'");
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }

        Object readLiteral(String word, Object value) {
            if (!s.startsWith(word, pos)) throw error("Invalid literal");
            pos += word.length();
            return value;
        }

        char peek() {
            if (pos >= s.length()) throw error("Unexpected end of input");
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) {
            if (next() != c) throw error("Expected '" + c + "'");
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }
    }
}

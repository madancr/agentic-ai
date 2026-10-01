package com.madan.urlshortener.http;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON helpers.
 *
 * This project deliberately avoids pulling in Jackson/Gson so the whole
 * thing compiles and runs with nothing but the JDK (javac/java). It only
 * needs to handle flat objects with string/number/boolean values, which is
 * all this API's request/response bodies ever contain -- it is NOT a
 * general-purpose JSON library.
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    /** Parses a flat JSON object into a String-valued map (numbers/booleans kept as their literal text). */
    public static Map<String, String> parseFlatObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null) {
            return result;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        if (trimmed.startsWith("{")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("}")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        int i = 0;
        int n = trimmed.length();
        while (i < n) {
            // skip whitespace / commas
            while (i < n && (Character.isWhitespace(trimmed.charAt(i)) || trimmed.charAt(i) == ',')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            if (trimmed.charAt(i) != '"') {
                throw new IllegalArgumentException("Expected '\"' to start a key at position " + i);
            }
            int[] keyEnd = new int[1];
            String key = readJsonString(trimmed, i, keyEnd);
            i = keyEnd[0];

            while (i < n && (Character.isWhitespace(trimmed.charAt(i)) || trimmed.charAt(i) == ':')) {
                i++;
            }

            String value;
            if (i < n && trimmed.charAt(i) == '"') {
                int[] valEnd = new int[1];
                value = readJsonString(trimmed, i, valEnd);
                i = valEnd[0];
            } else {
                int start = i;
                while (i < n && trimmed.charAt(i) != ',' && trimmed.charAt(i) != '}') {
                    i++;
                }
                value = trimmed.substring(start, i).trim();
            }

            result.put(key, value);
        }
        return result;
    }

    private static String readJsonString(String s, int startQuoteIndex, int[] endIndexOut) {
        StringBuilder sb = new StringBuilder();
        int i = startQuoteIndex + 1;
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(next);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        endIndexOut[0] = i + 1; // position just past the closing quote
        return sb.toString();
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Builds a JSON object string from a map. Supports nested Map (-> object)
     * and List (-> array) values, in addition to String/Number/Boolean/null,
     * which is enough for the analytics payload (daily breakdown object,
     * top-referrers array) without pulling in a real JSON library.
     */
    public static String toJson(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, fields);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(e.getKey()))).append("\":");
                writeValue(sb, e.getValue());
            }
            sb.append("}");
        } else if (v instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append("]");
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else {
            sb.append('"').append(escape(String.valueOf(v))).append('"');
        }
    }
}

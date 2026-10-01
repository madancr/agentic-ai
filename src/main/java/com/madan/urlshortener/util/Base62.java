package com.madan.urlshortener.util;

/**
 * Encodes non-negative longs into Base62 strings ([0-9a-zA-Z]).
 *
 * Base62 is preferred over Base64 for short URLs because it avoids
 * characters ('+', '/', '=') that are not URL-safe or that visually
 * resemble other characters, keeping generated codes clean and
 * copy-paste friendly.
 */
public final class Base62 {

    private static final String ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length();

    private Base62() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Base62 encoding requires a non-negative value: " + value);
        }
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        StringBuilder sb = new StringBuilder();
        long n = value;
        while (n > 0) {
            int remainder = (int) (n % BASE);
            sb.append(ALPHABET.charAt(remainder));
            n /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = ALPHABET.indexOf(code.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base62 character: " + code.charAt(i));
            }
            result = result * BASE + digit;
        }
        return result;
    }
}

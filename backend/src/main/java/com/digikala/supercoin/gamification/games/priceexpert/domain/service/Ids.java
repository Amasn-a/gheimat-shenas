package com.digikala.supercoin.gamification.games.priceexpert.domain.service;

import java.security.SecureRandom;

/** 128-bit random public ids (handoff security requirement 4), Crockford-style base32, 26 characters. */
public final class Ids {
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();

    private Ids() {}

    public static String newId() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(26);
        int buffer = 0, bits = 0;
        for (byte value : b) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(ALPHABET[(buffer >> (bits - 5)) & 31]); bits -= 5; }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer << (5 - bits)) & 31]);
        return sb.toString();
    }

    public static boolean looksValid(String id) {
        if (id == null || id.length() != 26) return false;
        for (char c : id.toCharArray()) if ("0123456789abcdefghjkmnpqrstvwxyz".indexOf(c) < 0) return false;
        return true;
    }
}

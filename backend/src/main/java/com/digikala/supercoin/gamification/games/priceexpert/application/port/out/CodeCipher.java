package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

/** Encrypts campaign codes at rest (handoff security requirement 5). */
public interface CodeCipher {
    String encrypt(String plain);
    String decrypt(String cipherText);
}

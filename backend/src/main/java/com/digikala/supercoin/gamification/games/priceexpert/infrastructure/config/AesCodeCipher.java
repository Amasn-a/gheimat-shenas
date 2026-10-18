package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CodeCipher;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM for campaign codes at rest. The key comes from PE_CODE_KEY (base64, 32 bytes); never logged. */
public final class AesCodeCipher implements CodeCipher {
    private final SecretKeySpec key;
    private final SecureRandom rng = new SecureRandom();

    public AesCodeCipher(byte[] key) {
        if (key.length != 32) throw new IllegalArgumentException("PE_CODE_KEY must be 32 bytes");
        this.key = new SecretKeySpec(key, "AES");
    }

    @Override public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            rng.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    @Override public String decrypt(String text) {
        try {
            byte[] all = Base64.getDecoder().decode(text);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("cannot decrypt code", e); }
    }
}

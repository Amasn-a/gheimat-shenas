package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.auth;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.PlayerAuthenticator;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * DEVELOPMENT ONLY. Accepts {@code Authorization: Bearer dev.<userId>}. Stands in for the Super App login verifier
 * (R-44), whose token handover and verification contract is with the app team (D09). Production refuses it.
 */
public final class DevPlayerAuthenticator implements PlayerAuthenticator {
    private static final Pattern TOKEN = Pattern.compile("^dev\\.([A-Za-z0-9_-]{1,64})$");

    @Override public Optional<String> authenticate(String bearerToken) {
        if (bearerToken == null) return Optional.empty();
        var m = TOKEN.matcher(bearerToken);
        return m.matches() ? Optional.of("u_" + m.group(1)) : Optional.empty();
    }

    @Override public boolean isDevelopmentOnly() { return true; }
}

package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import java.util.Optional;

/**
 * Verifies the Super App login token (R-44: login confirmed; handover and verification details are with the app
 * team, D09, and are not in the supplied sources). Returns the authenticated user id, never a client-claimed one.
 */
public interface PlayerAuthenticator {
    Optional<String> authenticate(String bearerToken);

    boolean isDevelopmentOnly();
}

package com.digikala.supercoin.gamification.games.priceexpert.domain.exception;

import java.util.Map;

/**
 * A stable domain error. {@code code} is the wire error code (handoff "Server rules and security"),
 * {@code httpStatus} its HTTP mapping. {@code details} may carry ids; it never carries hidden prices.
 */
public class GameException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final Map<String, Object> details;

    public GameException(String code, int httpStatus, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details == null ? Map.of() : details;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
    public Map<String, Object> details() { return details; }

    public static GameException conflict(String code, String message) { return new GameException(code, 409, message, null); }
    public static GameException conflict(String code, String message, Map<String, Object> details) { return new GameException(code, 409, message, details); }
    public static GameException notFound() { return new GameException("NOT_FOUND", 404, "not found", null); }
    public static GameException validation(String message, Map<String, Object> details) { return new GameException("VALIDATION_FAILED", 422, message, details); }
    public static GameException unauthorized() { return new GameException("UNAUTHENTICATED", 401, "authentication required", null); }
    public static GameException forbidden() { return new GameException("FORBIDDEN", 403, "not allowed", null); }
}

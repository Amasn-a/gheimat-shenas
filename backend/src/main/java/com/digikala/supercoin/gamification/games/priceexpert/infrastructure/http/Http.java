package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http;

import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny routing layer over the JDK HTTP server (stands in for Spring MVC; handlers stay thin). */
public final class Http {
    private Http() {}

    public static final int MAX_BODY = 16 * 1024;

    public record Request(String method, String path, Map<String, String> params, Map<String, List<String>> headers,
                          byte[] body, Instant receivedAt, String query) {
        public String header(String name) {
            for (var e : headers.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue().isEmpty() ? null : e.getValue().get(0);
            return null;
        }
        public String param(String name) { return params.get(name); }
        public String bodyText() { return new String(body, StandardCharsets.UTF_8); }
        public String queryParam(String name) {
            if (query == null) return null;
            for (String kv : query.split("&")) {
                int i = kv.indexOf('=');
                String k = i < 0 ? kv : kv.substring(0, i);
                if (k.equals(name)) return i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            }
            return null;
        }
        public <T> T json(Class<T> type) {
            try { return Json.read(body.length == 0 ? "{}".getBytes(StandardCharsets.UTF_8) : body, type); }
            catch (IOException e) { throw invalidBody(e); }
            catch (IllegalArgumentException e) { throw GameException.validation("invalid request body", Map.of("body", String.valueOf(e.getMessage()))); }
        }
        private static GameException invalidBody(IOException e) {
            String msg = e instanceof com.fasterxml.jackson.core.JsonProcessingException j ? j.getOriginalMessage() : "invalid body";
            return GameException.validation("invalid request body", Map.of("body", msg));
        }
    }

    public record Response(int status, Object body, Map<String, String> headers) {
        public static Response ok(Object body) { return new Response(200, body, Map.of()); }
        public static Response status(int status, Object body) { return new Response(status, body, Map.of()); }
    }

    @FunctionalInterface public interface Handler { Response handle(Request r) throws Exception; }

    public record Route(String method, Pattern pattern, List<String> names, Handler handler) {}

    public static Route route(String method, String template, Handler h) {
        List<String> names = new java.util.ArrayList<>();
        Matcher m = Pattern.compile("\\{([a-zA-Z_]+)}").matcher(template);
        StringBuilder sb = new StringBuilder("^");
        int last = 0;
        while (m.find()) {
            sb.append(Pattern.quote(template.substring(last, m.start()))).append("([^/]+)");
            names.add(m.group(1));
            last = m.end();
        }
        sb.append(Pattern.quote(template.substring(last))).append("$");
        return new Route(method, Pattern.compile(sb.toString()), names, h);
    }

    static byte[] readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(MAX_BODY + 1);
            if (b.length > MAX_BODY) throw GameException.validation("request body too large", Map.of());
            return b;
        }
    }

    public static Map<String, Object> error(GameException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", e.code());
        m.put("message", e.getMessage());
        m.putAll(e.details());
        return m;
    }
}

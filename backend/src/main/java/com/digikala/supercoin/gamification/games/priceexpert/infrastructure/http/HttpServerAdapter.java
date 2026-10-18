package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.http;

import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

/**
 * HTTP edge. The trusted receive time of a request ("receivedAt", R-38/R-45) is stamped here, as the first action for
 * the exchange, before the body is read or any lock is taken. Queueing inside the application therefore never counts
 * against the player; time spent before the request reaches this process (reverse proxy, network) is not observable here.
 */
public final class HttpServerAdapter {
    private static final Logger LOG = Logger.getLogger(HttpServerAdapter.class.getName());

    public record StaticMount(String prefix, Path dir, boolean html) {}

    private final InstantSource clock;
    private final List<Http.Route> routes = new ArrayList<>();
    private final List<StaticMount> mounts = new ArrayList<>();
    private final String contentSecurityPolicy;
    private HttpServer server;

    public HttpServerAdapter(InstantSource clock, String contentSecurityPolicy) {
        this.clock = clock;
        this.contentSecurityPolicy = contentSecurityPolicy;
    }

    public HttpServerAdapter add(List<Http.Route> r) { routes.addAll(r); return this; }
    public HttpServerAdapter mount(StaticMount m) { mounts.add(m); return this; }

    public void start(String host, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 256);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
        server.start();
        LOG.info("listening on http://" + host + ":" + port);
    }

    public void stop() { if (server != null) server.stop(0); }

    public int port() { return server.getAddress().getPort(); }

    private void dispatch(HttpExchange ex) {
        Instant receivedAt = clock.instant();                                   // trusted ingress stamp
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            for (Http.Route r : routes) {
                if (!r.method().equals(method)) continue;
                Matcher m = r.pattern().matcher(path);
                if (!m.matches()) continue;
                Map<String, String> params = new HashMap<>();
                for (int i = 0; i < r.names().size(); i++) params.put(r.names().get(i), m.group(i + 1));
                Http.Request req = new Http.Request(method, path, params, ex.getRequestHeaders(), Http.readBody(ex), receivedAt,
                        ex.getRequestURI().getRawQuery());
                Http.Response resp;
                try {
                    resp = r.handler().handle(req);
                } catch (GameException ge) {
                    resp = Http.Response.status(ge.httpStatus(), Http.error(ge));
                }
                writeJson(ex, resp);
                return;
            }
            if ("GET".equals(method) && serveStatic(ex, path)) return;
            writeJson(ex, Http.Response.status(404, Map.of("code", "NOT_FOUND", "message", "not found")));
        } catch (GameException ge) {
            safeWrite(ex, Http.Response.status(ge.httpStatus(), Http.error(ge)));
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "request failed " + method + " " + path, t);
            safeWrite(ex, Http.Response.status(500, Map.of("code", "INTERNAL", "message", "internal error")));
        } finally {
            ex.close();
        }
    }

    private void safeWrite(HttpExchange ex, Http.Response r) {
        try { writeJson(ex, r); } catch (IOException ignored) { }
    }

    private void writeJson(HttpExchange ex, Http.Response r) throws IOException {
        byte[] body = r.body() instanceof String s ? s.getBytes(StandardCharsets.UTF_8) : Json.write(r.body()).getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Content-Type-Options", "nosniff");
        r.headers().forEach(h::set);
        ex.sendResponseHeaders(r.status(), body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private boolean serveStatic(HttpExchange ex, String path) throws IOException {
        for (StaticMount m : mounts) {
            if (!path.startsWith(m.prefix())) continue;
            String rel = path.substring(m.prefix().length());
            if (rel.isEmpty() || rel.endsWith("/")) rel = rel + "index.html";
            Path file = m.dir().resolve(rel).normalize();
            if (!file.startsWith(m.dir()) || !Files.isRegularFile(file)) continue;
            byte[] bytes = Files.readAllBytes(file);
            var h = ex.getResponseHeaders();
            String type = contentType(file.toString());
            h.set("Content-Type", type);
            h.set("Referrer-Policy", "no-referrer");
            h.set("X-Content-Type-Options", "nosniff");
            if (type.startsWith("text/html")) {
                h.set("Content-Security-Policy", contentSecurityPolicy);
                h.set("Cache-Control", "no-store");
            } else h.set("Cache-Control", m.html() ? "no-cache" : "public, max-age=86400");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
            return true;
        }
        return false;
    }

    private static String contentType(String f) {
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".js") || f.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".json")) return "application/json; charset=utf-8";
        if (f.endsWith(".svg")) return "image/svg+xml";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".woff2")) return "font/woff2";
        if (f.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}

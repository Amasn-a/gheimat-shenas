package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One wire convention: snake_case JSON (the gamification module uses @JsonNaming(SnakeCase)). Instants are ISO-8601 UTC
 * strings with milliseconds; ids are strings, so no BIGINT precision is lost in JavaScript. Unknown request fields
 * are rejected (allow-listed bodies, handoff API conventions).
 */
public final class Json {
    public static final ObjectMapper MAPPER = build();
    /**
     * Jackson 2.14 (the version available to this offline build) does not apply naming strategies to record
     * constructors (databind #2992, fixed in 2.15). Reads therefore rename snake_case keys to camelCase first and bind
     * with a strategy-free mapper. With Jackson ≥ 2.15 in supercoin this indirection can be removed.
     */
    private static final ObjectMapper READER = build().setPropertyNamingStrategy(null)
            .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false)            // 300.9 is not an integer: rejected, not truncated
            .configure(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS, false);   // "300" is not a number
    private static final com.fasterxml.jackson.databind.PropertyNamingStrategies.NamingBase SNAKE =
            (com.fasterxml.jackson.databind.PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;

    private Json() {}

    private static ObjectMapper build() {
        SimpleModule time = new SimpleModule();
        time.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override public void serialize(Instant v, JsonGenerator g, SerializerProvider p) throws IOException {
                g.writeString(java.time.format.DateTimeFormatter.ISO_INSTANT.format(v.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)));
            }
        });
        time.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override public void serialize(LocalDate v, JsonGenerator g, SerializerProvider p) throws IOException { g.writeString(v.toString()); }
        });
        time.addDeserializer(Instant.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override public Instant deserialize(com.fasterxml.jackson.core.JsonParser p, com.fasterxml.jackson.databind.DeserializationContext c) throws IOException {
                return Instant.parse(p.getValueAsString());
            }
        });
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
                .configure(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)   // conflicting payload keys
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .registerModule(time);
    }

    public static String write(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public static <T> T read(String json, Class<T> type) {
        try { return bind(MAPPER.readTree(json), type); }
        catch (IOException e) { throw new IllegalArgumentException(e instanceof com.fasterxml.jackson.core.JsonProcessingException j ? j.getOriginalMessage() : e.getMessage(), e); }
    }

    public static <T> T read(byte[] json, Class<T> type) throws IOException {
        return bind(MAPPER.readTree(json), type);
    }

    private static <T> T bind(com.fasterxml.jackson.databind.JsonNode tree, Class<T> type) throws IOException {
        if (com.fasterxml.jackson.databind.JsonNode.class.isAssignableFrom(type)) return type.cast(tree);
        if (tree == null || !tree.isObject()) throw new IllegalArgumentException("the body must be a JSON object");
        return READER.treeToValue(camel(tree, "$"), type);
    }

    /**
     * snake_case → camelCase for binding (see READER). The wire has exactly one spelling per field: a key that is not
     * the snake_case form of its camelCase name ("choice_", "minBp") is rejected, so two wire keys can never collide on
     * one field. Arrays never carry nulls in this API.
     */
    private static com.fasterxml.jackson.databind.JsonNode camel(com.fasterxml.jackson.databind.JsonNode n, String at) {
        if (n.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode out = READER.createObjectNode();
            var it = n.fields();
            while (it.hasNext()) {
                var e = it.next();
                String c = toCamel(e.getKey());
                if (!SNAKE.translate(c).equals(e.getKey())) throw new IllegalArgumentException("unexpected field name " + at + "." + e.getKey());
                out.set(c, camel(e.getValue(), at + "." + e.getKey()));
            }
            return out;
        }
        if (n.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = READER.createArrayNode();
            for (int i = 0; i < n.size(); i++) {
                if (n.get(i).isNull()) throw new IllegalArgumentException("null element at " + at + "[" + i + "]");
                out.add(camel(n.get(i), at + "[" + i + "]"));
            }
            return out;
        }
        return n;
    }

    static String toCamel(String k) {
        StringBuilder sb = new StringBuilder(k.length());
        boolean up = false;
        for (char c : k.toCharArray()) {
            if (c == '_') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }
}

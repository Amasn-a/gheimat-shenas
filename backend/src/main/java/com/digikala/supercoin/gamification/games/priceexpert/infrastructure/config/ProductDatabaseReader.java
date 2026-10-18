package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.ImportRow;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the product database export (qeymatshenas_products.json, fetched 14 Oct 2026 from Digikala's public API with the
 * R-40 price rule). in_game = 0 rows keep their exclusion reason (including products 59, 172 and 522, R-36).
 * When {@code localMediaPrefix} is set (development), image URLs point at locally served copies instead of the CDN.
 */
public final class ProductDatabaseReader {
    private ProductDatabaseReader() {}

    public static List<ImportRow> read(Path file, String localMediaPrefix) throws IOException {
        JsonNode root = Json.MAPPER.readTree(Files.readString(file));
        JsonNode products = root.has("products") ? root.get("products") : root;
        List<ImportRow> rows = new ArrayList<>();
        for (JsonNode p : products) {
            long dkp = p.path("digikala_product_id").asLong();
            boolean inGame = p.path("in_game").asInt(0) == 1;
            String reason = p.path("exclude_reason").asText("");
            // ~400 px variant so the photo arrives early in the answer window (handoff, product data and images).
            String image = localMediaPrefix != null ? localMediaPrefix + dkp + ".jpg" : p.path("image_url").asText().replace("h_800,w_800", "h_400,w_400");
            rows.add(new ImportRow(dkp, p.path("displayed_variant_id").asLong(), p.path("title").asText(), p.path("display_title").asText(),
                    image, p.path("price_toman").asLong(), null, p.path("label").asText(null), inGame,
                    inGame ? null : (reason.isBlank() ? "excluded in the product database" : reason)));
        }
        return rows;
    }
}

package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.CatalogProduct;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy.PriceRange;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

/**
 * Candidate lookups are bounded by the (catalog_version, price) index and a LIMIT; random order applies only within
 * the price range of one band. At the current pool size (989 products) this is cheap; for much larger catalogs
 * a sampled offset would replace ORDER BY random().
 */
public final class JdbcCatalogRepository implements CatalogRepository {

    @Override public Optional<CatalogProduct> product(int version, long dkp) {
        return one("SELECT * FROM pe_catalog_product WHERE catalog_version = ? AND dkp = ?", JdbcCatalogRepository::product, version, dkp);
    }

    @Override public List<Candidate> candidates(int version, PriceRange range, Collection<Long> exclude, String userId, LocalDate showDate, int limit) {
        if (range.isEmpty()) return List.of();
        return query("""
            SELECT p.*, s.last_seen_at FROM pe_catalog_product p
            LEFT JOIN pe_seen_product s ON s.user_id = ? AND s.show_date = ? AND s.dkp = p.dkp
            WHERE p.catalog_version = ? AND p.eligible AND p.price_toman BETWEEN ? AND ? AND NOT (p.dkp = ANY (?))
            ORDER BY (s.last_seen_at IS NOT NULL), s.last_seen_at ASC, random()
            LIMIT ?""",
            rs -> new Candidate(product(rs), ts(rs, "last_seen_at")),
            userId, showDate, version, range.low(), range.high(), new BigintArray(exclude), limit);
    }

    @Override public boolean anyIn(int version, List<PriceRange> ranges, Collection<Long> exclude) {
        for (PriceRange r : ranges) {
            if (r.isEmpty()) continue;
            boolean found = one("""
                SELECT 1 FROM pe_catalog_product WHERE catalog_version = ? AND eligible AND price_toman BETWEEN ? AND ?
                AND NOT (dkp = ANY (?)) LIMIT 1""", rs -> true, version, r.low(), r.high(), new BigintArray(exclude)).isPresent();
            if (found) return true;
        }
        return false;
    }

    @Override public List<CatalogProduct> referenceCandidates(int version, String userId, LocalDate showDate, int limit) {
        return query("""
            SELECT p.* FROM pe_catalog_product p
            LEFT JOIN pe_seen_product s ON s.user_id = ? AND s.show_date = ? AND s.dkp = p.dkp
            WHERE p.catalog_version = ? AND p.eligible
            ORDER BY (s.last_seen_at IS NOT NULL), random() LIMIT ?""", JdbcCatalogRepository::product, userId, showDate, version, limit);
    }

    @Override public int nextVersionNumber() {
        return one("SELECT COALESCE(MAX(version), 0) + 1 FROM pe_catalog_version", rs -> rs.getInt(1)).orElse(1);
    }

    @Override public void insertVersion(int version, String source, int productCount, int eligibleCount, Instant now) {
        update("INSERT INTO pe_catalog_version (version, status, source, product_count, eligible_count, created_at) VALUES (?, 'STAGED', ?, ?, ?, ?)",
                version, source, productCount, eligibleCount, now);
    }

    @Override public void insertProducts(int version, List<ImportRow> rows) {
        String sql = """
            INSERT INTO pe_catalog_product (catalog_version, dkp, variant_id, title, display_title, image_url, price_toman, prev_price_toman,
              category, eligible, exclude_reason) VALUES (?,?,?,?,?,?,?,?,?,?,?)""";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (ImportRow r : rows) {
                bind(ps, new Object[]{version, r.dkp(), r.variantId(), r.title(), r.displayTitle(), r.imageUrl(), r.priceToman(),
                        nz(r.prevPriceToman()), nz(r.category()), r.eligible(), nz(r.excludeReason())});
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) { throw new DataAccessException(sql, e); }
    }

    @Override public void markPublished(int version, Instant now) {
        update("UPDATE pe_catalog_version SET status = 'RETIRED' WHERE status = 'PUBLISHED' AND version <> ?", version);
        update("UPDATE pe_catalog_version SET status = 'PUBLISHED', published_at = ? WHERE version = ?", now, version);
    }

    @Override public List<CatalogVersionInfo> versions() {
        return query("SELECT * FROM pe_catalog_version ORDER BY version DESC", rs -> new CatalogVersionInfo(rs.getInt("version"),
                rs.getString("status"), rs.getString("source"), rs.getInt("product_count"), rs.getInt("eligible_count"),
                ts(rs, "created_at"), ts(rs, "published_at")));
    }

    @Override public Optional<Long> previousPublishedPrice(int beforeVersion, long dkp) {
        return one("""
            SELECT p.price_toman FROM pe_catalog_product p JOIN pe_catalog_version v ON v.version = p.catalog_version
            WHERE p.dkp = ? AND p.catalog_version < ? AND v.published_at IS NOT NULL ORDER BY p.catalog_version DESC LIMIT 1""",
            rs -> rs.getLong(1), dkp, beforeVersion);
    }

    static CatalogProduct product(ResultSet rs) throws SQLException {
        return new CatalogProduct(rs.getLong("dkp"), rs.getLong("variant_id"), rs.getString("title"), rs.getString("display_title"),
                rs.getString("image_url"), rs.getLong("price_toman"));
    }
}

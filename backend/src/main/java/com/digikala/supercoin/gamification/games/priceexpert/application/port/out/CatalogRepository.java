package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.CatalogProduct;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy.PriceRange;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CatalogRepository {
    /** A candidate with the time the account last saw it on the given show day (null = not seen that day). */
    record Candidate(CatalogProduct product, Instant lastSeenAt) {}

    record ImportRow(long dkp, long variantId, String title, String displayTitle, String imageUrl, long priceToman,
                     Long prevPriceToman, String category, boolean eligible, String excludeReason) {}

    record CatalogVersionInfo(int version, String status, String source, int productCount, int eligibleCount,
                              Instant createdAt, Instant publishedAt) {}

    Optional<CatalogProduct> product(int catalogVersion, long dkp);

    /**
     * Eligible products of the version whose price is inside the range, excluding the given dkps.
     * Unseen-today products come first in random order, then seen ones least-recently seen first; at most {@code limit}.
     */
    List<Candidate> candidates(int catalogVersion, PriceRange range, Collection<Long> excludeDkps,
                               String userId, LocalDate showDate, int limit);

    /** True when at least one eligible product lies in any of the ranges, excluding the given dkps (look-ahead). */
    boolean anyIn(int catalogVersion, List<PriceRange> ranges, Collection<Long> excludeDkps);

    /** Random eligible products for an initial reference; unseen-today first. */
    List<CatalogProduct> referenceCandidates(int catalogVersion, String userId, LocalDate showDate, int limit);

    int nextVersionNumber();
    void insertVersion(int version, String source, int productCount, int eligibleCount, Instant now);
    void insertProducts(int version, List<ImportRow> rows);
    void markPublished(int version, Instant now);
    List<CatalogVersionInfo> versions();
    /** Price of the dkp in the latest published version before {@code version}, for the price-change filter (R-42). */
    Optional<Long> previousPublishedPrice(int beforeVersion, long dkp);
}

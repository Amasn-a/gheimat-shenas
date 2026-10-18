package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CatalogRepository.ImportRow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daily catalog publication, off the gameplay path (R-42). A new version is validated and published atomically;
 * active runs keep the version they started with. Exclusion reasons are preserved; an excluded product is never
 * resurrected by import. The price-change filter applies only when its threshold is set (R-42: threshold open).
 */
public final class CatalogService {
    private final PriceExpertContext ctx;

    public CatalogService(PriceExpertContext ctx) { this.ctx = ctx; }

    public record ImportReport(int version, int products, int eligible, int rejectedRows, List<String> problems) {}

    public ImportReport importAndPublish(List<ImportRow> rows, String source, String actor) {
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            List<String> problems = new ArrayList<>();
            List<ImportRow> clean = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            int version = ctx.catalog().nextVersionNumber();
            Integer activeCfg = ctx.settings().activeConfigVersion();
            Integer thresholdBp = activeCfg == null ? null : ctx.config(activeCfg).priceChangeThresholdBp();
            for (ImportRow r : rows) {
                String why = invalid(r);
                if (why != null) { problems.add("dkp " + r.dkp() + ": " + why); continue; }
                if (!seen.add(r.dkp())) { problems.add("dkp " + r.dkp() + ": duplicate row (one entry per DKP per version)"); continue; }
                ImportRow row = r;
                Long prev = ctx.catalog().previousPublishedPrice(version, r.dkp()).orElse(r.prevPriceToman());
                if (row.eligible() && thresholdBp != null && prev != null && prev > 0) {
                    long changeBp = Math.abs(r.priceToman() - prev) * 10_000L / prev;
                    if (changeBp > thresholdBp) row = new ImportRow(r.dkp(), r.variantId(), r.title(), r.displayTitle(), r.imageUrl(), r.priceToman(),
                            prev, r.category(), false, "price changed " + changeBp / 100.0 + "% since the previous catalog (threshold " + thresholdBp / 100.0 + "%)");
                }
                if (row.prevPriceToman() == null && prev != null)
                    row = new ImportRow(row.dkp(), row.variantId(), row.title(), row.displayTitle(), row.imageUrl(), row.priceToman(), prev,
                            row.category(), row.eligible(), row.excludeReason());
                clean.add(row);
            }
            int eligible = (int) clean.stream().filter(ImportRow::eligible).count();
            if (eligible < 2) throw new IllegalStateException("catalog has fewer than two eligible products; not published");
            ctx.catalog().insertVersion(version, source, clean.size(), eligible, now);
            ctx.catalog().insertProducts(version, clean);
            ctx.catalog().markPublished(version, now);
            var s = ctx.configs().lockSettings();
            ctx.configs().saveSettings(new com.digikala.supercoin.gamification.games.priceexpert.application.port.out.ConfigRepository.Settings(
                    s.activeConfigVersion(), version, s.gameEnabled(), s.playerMessage(), s.revision() + 1, actor, now));
            ctx.audit().append(actor, "SYSTEM", "catalog.publish", "catalog:" + version, null,
                    "{\"version\":" + version + ",\"products\":" + clean.size() + ",\"eligible\":" + eligible + "}", source, null, now);
            return new ImportReport(version, clean.size(), eligible, rows.size() - clean.size(), problems);
        });
    }

    private static String invalid(ImportRow r) {
        if (r.dkp() <= 0 || r.variantId() <= 0) return "missing product or variant id";
        if (r.priceToman() <= 0) return "price must be positive";
        if (r.displayTitle() == null || r.displayTitle().isBlank()) return "missing display_title";
        if (r.displayTitle().length() > 50) return "display_title longer than 50 characters";
        if (r.title() == null || r.title().isBlank()) return "missing title";
        if (r.imageUrl() == null || !(r.imageUrl().startsWith("https://") || r.imageUrl().startsWith("/media/"))) return "image_url must be https";
        if (!r.eligible() && (r.excludeReason() == null || r.excludeReason().isBlank())) return "excluded without a reason";
        return null;
    }
}

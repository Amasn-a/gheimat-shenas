package com.digikala.supercoin.gamification.games.priceexpert.application.service;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.AdminRepository;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.ConfigRepository.Settings;
import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.ConfigRepository.StoredConfig;
import com.digikala.supercoin.gamification.games.priceexpert.domain.exception.GameException;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.*;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.BusinessCalendar;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.ConfigValidator;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.DifficultyPolicy;
import com.digikala.supercoin.gamification.games.priceexpert.domain.service.Ids;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * Standalone admin use cases (A01–A07). Every permission is enforced here, on the server: Viewers read, the single
 * Editor role writes (R-13, R-53). Every write appends to the audit with before/after; reads of player data are
 * audited too (S-10). JSON for before/after is produced by the caller-supplied serializer to keep this layer free of
 * Jackson.
 */
public final class AdminService {
    public enum Role { EDITOR, VIEWER }
    public record Admin(String username, Role role) {}

    public record FieldChange(String field, Object before, Object after) {}
    public record ConfigOverview(StoredConfig active, StoredConfig draft, List<StoredConfig> versions, Map<String, String> draftErrors,
                                 List<FieldChange> draftChanges, long activeDailyCap, Long draftDailyCap, Map<String, Object> bounds,
                                 boolean gameEnabled, String playerMessage, int settingsRevision, String draftRevision) {}
    /** {@code revision} identifies the saved content; send it back to save over, discard or publish exactly this draft. */
    public record DraftResult(int version, Map<String, String> errors, List<FieldChange> changes, long dailyCap, String revision) {}
    public record PublishResult(int activeVersion, Instant publishedAt, int dailyCap) {}
    public record AvailabilityResult(boolean enabled, String playerMessage, int runsClosed, int questionsStillOpen, int paymentsPending) {}

    private final PriceExpertContext ctx;
    private final SettlementService settlement;
    private final DeadlineService deadlines;
    private AdminRepository admins;
    private Function<Object, String> json = String::valueOf;
    private Function<Object, Map<String, Object>> flatten = o -> Map.of();

    public AdminService(PriceExpertContext ctx, SettlementService settlement, DeadlineService deadlines) {
        this.ctx = ctx;
        this.settlement = settlement;
        this.deadlines = deadlines;
    }

    /** Wiring hooks (set once at start-up). */
    public void bind(AdminRepository admins, Function<Object, String> json, Function<Object, Map<String, Object>> flatten) {
        this.admins = admins;
        this.json = json;
        this.flatten = flatten;
    }

    static void requireEditor(Admin a) { if (a.role() != Role.EDITOR) throw GameException.forbidden(); }

    /** Consequential operator actions carry a reason in the audit; enforced here, not only by the admin UI. */
    static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw GameException.validation("reason is required", Map.of("reason", "required"));
    }

    // ------------------------------------------------------------------------------------------------ bootstrap

    /** Publishes the owner-approved launch configuration as version 1 on an empty database. */
    public void ensureLaunchConfig() {
        ctx.tx().run(() -> {
            Settings s = ctx.configs().lockSettings();
            if (s.activeConfigVersion() != null) return;
            Instant now = ctx.now();
            GameConfig launch = LaunchConfig.value();
            Map<String, String> errors = ConfigValidator.validate(launch, ctx.bounds());
            if (!errors.isEmpty()) throw new IllegalStateException("launch config invalid: " + errors);
            int v = ctx.configs().insertDraft(launch, null, "system:bootstrap", now);
            ctx.configs().markPublished(v, "system:bootstrap", now);
            ctx.configs().saveSettings(new Settings(v, s.activeCatalogVersion(), s.gameEnabled(), s.playerMessage(), s.revision() + 1, "system:bootstrap", now));
            ctx.ledger().raiseCapHighWater(BusinessCalendar.dateOf(now), launch.dailyAccountCap(), now);
            ctx.audit().append("system:bootstrap", "SYSTEM", "config.publish", "config:" + v, null, json.apply(launch), "launch values R-20/R-33/R-41/R-45/R-47", null, now);
        });
    }

    // ------------------------------------------------------------------------------------------------ A01–A04 configuration

    public ConfigOverview config(Admin a) {
        return ctx.tx().inTransaction(() -> {
            Settings s = ctx.settings();
            StoredConfig active = s.activeConfigVersion() == null ? null : ctx.configs().version(s.activeConfigVersion()).orElse(null);
            StoredConfig draft = ctx.configs().versions(20).stream().filter(v -> "DRAFT".equals(v.status())).findFirst().orElse(null);
            Map<String, String> errors = draft == null ? Map.of() : ConfigValidator.validate(draft.config(), ctx.bounds());
            List<FieldChange> changes = draft == null || active == null ? List.of() : diff(active.config(), draft.config());
            return new ConfigOverview(active, draft, ctx.configs().versions(20), errors, changes,
                    active == null ? 0 : active.config().dailyAccountCapExact(), draft == null ? null : draft.config().dailyAccountCapExact(), bounds(),
                    s.gameEnabled(), s.playerMessage(), s.revision(), draft == null ? null : revision(draft.config()));
        });
    }

    /**
     * Saves the single shared draft. {@code expectedRevision} is the revision of the draft the editor loaded (null when
     * they saw none): another editor's newer save, a discard or a publish in between is a DRAFT_CHANGED conflict, so
     * nobody overwrites or publishes content they have not reviewed.
     */
    public DraftResult saveDraft(Admin a, GameConfig cfg, String expectedRevision) {
        requireEditor(a);
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Settings s = ctx.configs().lockSettings();
            StoredConfig active = ctx.configs().version(s.activeConfigVersion()).orElseThrow();
            Optional<StoredConfig> existing = currentDraft();
            requireRevision(existing.orElse(null), expectedRevision);
            int version;
            String before;
            if (existing.isPresent()) {
                version = existing.get().version();
                before = json.apply(existing.get().config());
                ctx.configs().updateDraft(version, cfg);
            } else {
                version = ctx.configs().insertDraft(cfg, active.version(), a.username(), now);
                before = json.apply(active.config());
            }
            Map<String, String> errors = ConfigValidator.validate(cfg, ctx.bounds());
            ctx.audit().append(a.username(), a.role().name(), "config.draft", "config:" + version, before, json.apply(cfg), null, null, now);
            return new DraftResult(version, errors, diff(active.config(), cfg), cfg.dailyAccountCapExact(), revision(cfg));   // a draft may be invalid: never throw here
        });
    }

    public void discardDraft(Admin a, int version, String draftRevision) {
        requireEditor(a);
        ctx.tx().run(() -> {
            ctx.configs().lockSettings();                                      // serialised with save and publish
            StoredConfig d = ctx.configs().version(version).orElseThrow(GameException::notFound);
            if (!"DRAFT".equals(d.status())) throw GameException.conflict("NOT_A_DRAFT", "only drafts can be discarded");
            requireRevision(d, draftRevision);
            ctx.configs().retire(version);
            ctx.audit().append(a.username(), a.role().name(), "config.discard", "config:" + version, json.apply(d.config()), null, null, null, ctx.now());
        });
    }

    /**
     * Publishes a validated draft. {@code expectedActiveVersion} stops two Editors overwriting each other
     * (VERSION_CONFLICT). Runs already started keep their pinned version; today's cap high-water is raised (R-47).
     */
    public PublishResult publish(Admin a, int version, int expectedActiveVersion, String draftRevision, String reason) {
        requireEditor(a);
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Settings s = ctx.configs().lockSettings();
            if (s.activeConfigVersion() == null || s.activeConfigVersion() != expectedActiveVersion)
                throw GameException.conflict("VERSION_CONFLICT", "the active version changed; reload", Map.of("active_version", s.activeConfigVersion()));
            StoredConfig draft = ctx.configs().version(version).orElseThrow(GameException::notFound);
            if (!"DRAFT".equals(draft.status())) throw GameException.conflict("NOT_A_DRAFT", "only a draft can be published");
            requireRevision(draft, draftRevision);                             // exactly the content the editor reviewed
            Map<String, String> errors = ConfigValidator.validate(draft.config(), ctx.bounds());
            if (!errors.isEmpty()) throw GameException.validation("draft has validation errors", Map.of("fields", errors));
            StoredConfig old = ctx.configs().version(s.activeConfigVersion()).orElseThrow();
            ctx.configs().markPublished(version, a.username(), now);
            ctx.configs().retire(old.version());
            ctx.configs().saveSettings(new Settings(version, s.activeCatalogVersion(), s.gameEnabled(), s.playerMessage(), s.revision() + 1, a.username(), now));
            ctx.ledger().raiseCapHighWater(BusinessCalendar.dateOf(now), draft.config().dailyAccountCap(), now);
            ctx.audit().append(a.username(), a.role().name(), "config.publish", "config:" + version, json.apply(old.config()), json.apply(draft.config()), reason,
                    "config:" + old.version(), now);                           // correlation: the version it replaced
            ctx.ledger().alert("ADMIN_PUBLISH", "publish:" + version, "{\"version\":" + version + ",\"by\":\"" + a.username() + "\"}", now);
            return new PublishResult(version, now, draft.config().dailyAccountCap());
        });
    }

    /** A04 helper: eligible pairs per band in the active catalog, computed with the exact band rule. */
    public List<Map<String, Object>> bandPairCounts(Admin a, List<GameConfig.DifficultyBand> bands) {
        int catalogVersion = ctx.tx().inTransaction(ctx::activeCatalogVersion);
        List<Long> prices = ctx.tx().inTransaction(() -> admins.activeCatalogPrices(catalogVersion));
        long[] p = prices.stream().mapToLong(Long::longValue).sorted().toArray();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < bands.size(); i++) {
            GameConfig.DifficultyBand b = bands.get(i);
            long pairs = 0;
            for (long ref : p) {
                for (DifficultyPolicy.Direction d : DifficultyPolicy.Direction.values()) {
                    DifficultyPolicy.PriceRange r = DifficultyPolicy.range(b, ref, d);
                    if (r.isEmpty()) continue;
                    pairs += countInRange(p, r.low(), r.high());
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("band_index", i);
            m.put("min_score", b.minScore());
            m.put("ordered_pairs", pairs);
            out.add(m);
        }
        return out;
    }

    private static long countInRange(long[] sorted, long lo, long hi) {
        int a = lowerBound(sorted, lo), b = lowerBound(sorted, hi == Long.MAX_VALUE ? Long.MAX_VALUE : hi + 1);
        return Math.max(0, b - a);
    }

    private static int lowerBound(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid] < key) lo = mid + 1; else hi = mid; }
        return lo;
    }

    // ------------------------------------------------------------------------------------------------ A05 operations

    /**
     * Kill switch (R-23, R-46). Off: new runs are refused; runs at a question break end now as game_off with their highest
     * checkpoint reward; open offers end (no continue after game-off) and a payment in progress resolves in the background
     * with a refund if it was charged; a released question keeps its deadline and ends at its next break.
     */
    public AvailabilityResult setAvailability(Admin a, boolean enabled, String message, String reason) {
        requireEditor(a);
        if (message != null && (message.isBlank() || message.length() > 120))
            throw GameException.validation("player message must be 1–120 characters", Map.of("player_message", "1–120"));
        Settings before = ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Settings s = ctx.configs().lockSettings();
            ctx.configs().saveSettings(new Settings(s.activeConfigVersion(), s.activeCatalogVersion(), enabled, message, s.revision() + 1, a.username(), now));
            ctx.audit().append(a.username(), a.role().name(), enabled ? "game.on" : "game.off", "game", "{\"enabled\":" + s.gameEnabled() + "}",
                    "{\"enabled\":" + enabled + ",\"message\":" + json.apply(message) + "}", reason, null, now);
            ctx.ledger().alert(enabled ? "ADMIN_GAME_ON" : "ADMIN_GAME_OFF", null, "{\"by\":\"" + a.username() + "\"}", now);
            return s;
        });
        int closed = 0, open = 0, pending = 0;
        String after = null;
        while (!enabled) {                                                     // every active run, in keyset pages (review b023340 #24)
            String from = after;
            List<String> page = ctx.tx().inTransaction(() -> ctx.runs().activeRunIds(from, 500));
            if (page.isEmpty()) break;
            after = page.get(page.size() - 1);
            for (String runId : page) {
                String state = ctx.tx().inTransaction(() -> {
                    Instant now = ctx.now();
                    Run peek = ctx.runs().findRun(runId).orElseThrow();
                    Player p = ctx.players().lockOrCreatePlayer(peek.userId, now);
                    Run run = ctx.runs().lockRun(runId).orElseThrow();
                    boolean wasPayment = run.state == RunState.PAYMENT_PENDING;
                    if (run.state != RunState.QUESTION_OPEN && !run.isFinished()) settlement.finish(run, p, FinishReason.game_off, now);
                    return run.state == RunState.QUESTION_OPEN ? "open" : wasPayment ? "payment" : "closed";
                });
                switch (state) { case "open" -> open++; case "payment" -> { pending++; closed++; } default -> closed++; }
            }
        }
        Objects.requireNonNull(before);
        return new AvailabilityResult(enabled, message, closed, open, pending);
    }

    public List<Map<String, Object>> runs(Admin a, String userId, String runId) {
        if (userId == null && runId == null) throw GameException.validation("userId or runId is required", Map.of());
        return audited(a, "admin.read.runs", userId != null ? "user:" + userId : "run:" + runId, () -> admins.runs(userId, runId, 200));
    }

    public List<Map<String, Object>> operations(Admin a, String userId, String runId, String status) {
        return audited(a, "admin.read.operations", userId != null ? "user:" + userId : runId != null ? "run:" + runId : "status:" + status,
                () -> admins.operations(userId, runId, status, 200));
    }

    public Map<String, Object> player(Admin a, String userId) {
        return audited(a, "admin.read.player", "user:" + userId, () -> {
            Map<String, Object> m = new LinkedHashMap<>(admins.player(userId));
            if (m.isEmpty()) throw GameException.notFound();
            m.put("signals", admins.abuseSignals(userId));
            m.put("reviews", admins.reviews(userId));
            return m;
        });
    }

    private <T> T audited(Admin a, String action, String target, java.util.function.Supplier<T> read) {
        return ctx.tx().inTransaction(() -> {
            T v = read.get();
            ctx.audit().append(a.username(), a.role().name(), action, target, null, null, null, null, ctx.now());
            return v;
        });
    }

    /** A review alone has no money effect (spec). */
    public Map<String, Object> review(Admin a, String userId, String runId, String outcome, String note) {
        requireEditor(a);
        if (!Set.of("no_issue", "suspicious", "needs_more_review").contains(outcome))
            throw GameException.validation("invalid outcome", Map.of("outcome", "no_issue | suspicious | needs_more_review"));
        if (note != null && note.length() > 1000) throw GameException.validation("note too long", Map.of("note", "≤ 1000"));
        return ctx.tx().inTransaction(() -> {
            String id = Ids.newId();
            Instant now = ctx.now();
            admins.insertReview(id, userId, runId, outcome, note, a.username(), now);
            ctx.audit().append(a.username(), a.role().name(), "review.create", "user:" + userId, null,
                    json.apply(Map.of("outcome", outcome, "run_id", runId == null ? "" : runId, "note", note == null ? "" : note)), null, id, now);
            return Map.of("review_id", id, "at", now.toString());
        });
    }

    /**
     * Block (R-22, R-30, R-34): the open run ends at once with reason blocked, none of its rewards are paid and it counts
     * for neither record nor streak; a payment in progress is reconciled and refunded if charged. Other runs' rewards stay.
     */
    public Map<String, Object> block(Admin a, String userId, String reason, String reviewReference) {
        requireEditor(a);
        if (reason == null || reason.isBlank()) throw GameException.validation("reason is required", Map.of("reason", "required"));
        if (reviewReference == null || reviewReference.isBlank()) throw GameException.validation("review reference is required", Map.of("review_reference", "required"));
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Player p = ctx.players().lockOrCreatePlayer(userId, now);
            if (p.blocked) throw GameException.conflict("ALREADY_BLOCKED", "player already blocked");
            ctx.players().setBlocked(userId, true, a.username(), reason, now);
            p.blocked = true;
            String endedRun = null;
            Optional<Run> active = ctx.runs().lockActiveRunOfUser(userId);
            if (active.isPresent()) {
                settlement.finish(active.get(), p, FinishReason.blocked, now);
                endedRun = active.get().runId;
            }
            ctx.audit().append(a.username(), a.role().name(), "player.block", "user:" + userId, "{\"blocked\":false}",
                    "{\"blocked\":true,\"ended_run\":" + json.apply(endedRun) + "}", reason, reviewReference, now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("blocked", true); m.put("at", now.toString()); m.put("ended_run_id", endedRun);
            return m;
        });
    }

    public Map<String, Object> unblock(Admin a, String userId, String reason) {
        requireEditor(a);
        requireReason(reason);
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            Player p = ctx.players().lockOrCreatePlayer(userId, now);
            if (!p.blocked) throw GameException.conflict("NOT_BLOCKED", "player is not blocked");
            ctx.players().setBlocked(userId, false, a.username(), null, now);
            ctx.audit().append(a.username(), a.role().name(), "player.unblock", "user:" + userId, "{\"blocked\":true}", "{\"blocked\":false}", reason, null, now);
            ctx.ledger().alert("ADMIN_UNBLOCK", null, "{\"user_id\":\"" + userId + "\",\"by\":\"" + a.username() + "\"}", now);
            return Map.of("blocked", false, "at", now.toString());
        });
    }

    /** Manual, audited, idempotent re-queue of an operation that reached NEEDS_REVIEW. */
    public Map<String, Object> retryOperation(Admin a, String operationId, String reason) {
        requireEditor(a);
        requireReason(reason);
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            CoinOperation op = ctx.ledger().lockOperation(operationId).orElseThrow(GameException::notFound);
            if (op.status != OperationStatus.NEEDS_REVIEW) return Map.of("operation_id", operationId, "status", op.status.name(), "changed", false);
            op.status = OperationStatus.UNKNOWN;                              // query first: the provider may have applied it
            op.nextAttemptAt = now;
            op.giveUpAt = now.plusSeconds(72 * 3600);
            ctx.ledger().updateOperation(op);
            ctx.audit().append(a.username(), a.role().name(), "operation.retry", "operation:" + operationId, "{\"status\":\"NEEDS_REVIEW\"}",
                    "{\"status\":\"UNKNOWN\"}", reason, null, now);
            return Map.of("operation_id", operationId, "status", "UNKNOWN", "changed", true);
        });
    }

    /** Campaign codes whose issue exhausted its retries (no code is shown to the player; the reservation stays). */
    public List<Map<String, Object>> entitlementsNeedingReview(Admin a) {
        return audited(a, "admin.read.entitlements", "status:NEEDS_REVIEW", () -> ctx.campaigns().entitlementsNeedingReview(200).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entitlement_id", e.entitlementId()); m.put("campaign_id", e.campaignId()); m.put("user_id", e.userId()); m.put("run_id", e.runId());
            m.put("attempts", e.attempts()); m.put("last_error", e.lastError()); m.put("created_at", e.createdAt());
            return m;
        }).toList());
    }

    /** Manual, audited re-queue of a code issue in NEEDS_REVIEW. The provider call carries the same entitlement id (idempotent). */
    public Map<String, Object> retryEntitlement(Admin a, String entitlementId, String reason) {
        requireEditor(a);
        requireReason(reason);
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            var e = ctx.campaigns().lockEntitlement(entitlementId).orElseThrow(GameException::notFound);
            if (!"NEEDS_REVIEW".equals(e.status())) return Map.of("entitlement_id", entitlementId, "status", e.status(), "changed", false);
            ctx.campaigns().updateEntitlement(new com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignRepository.Entitlement(
                    e.entitlementId(), e.campaignId(), e.userId(), e.runId(), "PENDING", e.amountToman(), null, null, e.providerRef(), 0, now,
                    e.lastError(), e.createdAt(), null));
            ctx.audit().append(a.username(), a.role().name(), "entitlement.retry", "entitlement:" + entitlementId, "{\"status\":\"NEEDS_REVIEW\"}",
                    "{\"status\":\"PENDING\"}", reason, null, now);
            return Map.of("entitlement_id", entitlementId, "status", "PENDING", "changed", true);
        });
    }

    public List<Map<String, Object>> alerts(Admin a) { return ctx.tx().inTransaction(() -> admins.alerts(200)); }

    public Map<String, Object> monitoring(Admin a) {
        return ctx.tx().inTransaction(() -> {
            Map<String, Object> m = new LinkedHashMap<>(admins.openCounts());
            Integer v = ctx.settings().activeConfigVersion();
            Long threshold = v == null ? null : ctx.config(v).payoutAlertThreshold();
            m.put("payout_alert_threshold", threshold);
            m.put("payout_alert_threshold_status", threshold == null ? "pending owner value (R-47)" : "configured");
            m.put("coin_provider", ctx.coin().isFake() ? "fake (in-project, R-44)" : "not integrated");
            m.put("campaign_code_provider", ctx.codes().isFake() ? "fake (development)" : "configured");
            return m;
        });
    }

    // ------------------------------------------------------------------------------------------------ A06 history

    public List<com.digikala.supercoin.gamification.games.priceexpert.application.port.out.AuditRepository.AuditEvent> audit(
            Admin a, String area, String actor, boolean changesOnly, Instant from, Instant to, long beforeId) {
        return ctx.tx().inTransaction(() -> ctx.audit().list(area, actor, changesOnly, from, to, 100, beforeId));
    }

    // ------------------------------------------------------------------------------------------------ A07 campaigns

    public List<Campaign> campaigns(Admin a) { return ctx.tx().inTransaction(() -> ctx.campaigns().list()); }

    public Campaign createCampaign(Admin a, Campaign draft) {
        requireEditor(a);
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            ctx.campaigns().lockSchedule();                                    // overlap check and insert as one step (review b023340 #16)
            validateCampaign(draft, null);
            Campaign c = new Campaign(Ids.newId(), draft.name().trim(), draft.startsAt(), draft.endsAt(), List.copyOf(draft.codeCheckpointIds()),
                    draft.codeAmountToman(), draft.validityDays(), draft.terms().trim(), draft.codeCap(), draft.budgetToman(), 0, 0, "SCHEDULED");
            ctx.campaigns().insert(c, a.username(), now);
            ctx.audit().append(a.username(), a.role().name(), "campaign.create", "campaign:" + c.campaignId(), null, json.apply(c), null, null, now);
            ctx.ledger().alert("ADMIN_CAMPAIGN", "campaign-create:" + c.campaignId(), "{\"campaign_id\":\"" + c.campaignId() + "\"}", now);
            return c;
        });
    }

    /** A running campaign cannot be edited, only ended (D-17). */
    public Campaign updateCampaign(Admin a, String campaignId, Campaign draft) {
        requireEditor(a);
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            ctx.campaigns().lockSchedule();                                    // a stale edit can no longer revive an ended campaign
            Campaign cur = ctx.campaigns().find(campaignId).orElseThrow(GameException::notFound);
            if ("ENDED".equals(cur.status()) || !now.isBefore(cur.startsAt())) throw GameException.conflict("CAMPAIGN_RUNNING", "a running or ended campaign cannot be edited");
            validateCampaign(draft, campaignId);
            Campaign c = new Campaign(cur.campaignId(), draft.name().trim(), draft.startsAt(), draft.endsAt(), List.copyOf(draft.codeCheckpointIds()),
                    draft.codeAmountToman(), draft.validityDays(), draft.terms().trim(), draft.codeCap(), draft.budgetToman(), cur.codesIssued(),
                    cur.budgetUsedToman(), cur.status());
            ctx.campaigns().update(c, now);
            ctx.audit().append(a.username(), a.role().name(), "campaign.update", "campaign:" + c.campaignId(), json.apply(cur), json.apply(c), null, null, now);
            ctx.ledger().alert("ADMIN_CAMPAIGN", null, "{\"campaign_id\":\"" + c.campaignId() + "\"}", now);
            return c;
        });
    }

    public Campaign endCampaign(Admin a, String campaignId, String reason) {
        requireEditor(a);
        requireReason(reason);
        return ctx.tx().inTransaction(() -> {
            Instant now = ctx.now();
            ctx.campaigns().lockSchedule();
            Campaign cur = ctx.campaigns().find(campaignId).orElseThrow(GameException::notFound);
            if ("ENDED".equals(cur.status())) return cur;
            Campaign c = new Campaign(cur.campaignId(), cur.name(), cur.startsAt(), now.isBefore(cur.endsAt()) && now.isAfter(cur.startsAt()) ? now : cur.endsAt(),
                    cur.codeCheckpointIds(), cur.codeAmountToman(), cur.validityDays(), cur.terms(), cur.codeCap(), cur.budgetToman(),
                    cur.codesIssued(), cur.budgetUsedToman(), "ENDED");
            ctx.campaigns().update(c, now);
            ctx.audit().append(a.username(), a.role().name(), "campaign.end", "campaign:" + c.campaignId(), json.apply(cur), json.apply(c), reason, null, now);
            ctx.ledger().alert("ADMIN_CAMPAIGN", null, "{\"campaign_id\":\"" + c.campaignId() + "\",\"ended\":true}", now);
            return c;
        });
    }

    private void validateCampaign(Campaign d, String except) {
        Map<String, Object> e = new LinkedHashMap<>();
        if (d.name() == null || d.name().isBlank() || d.name().length() > 80) e.put("name", "1–80 characters");
        if (d.startsAt() == null || d.endsAt() == null || !d.endsAt().isAfter(d.startsAt())) e.put("ends_at", "end must be after start");
        if (d.codeCheckpointIds() == null || d.codeCheckpointIds().isEmpty()) e.put("code_checkpoint_ids", "at least one checkpoint");
        else {
            GameConfig active = ctx.config(ctx.activeConfigVersion());
            for (String id : d.codeCheckpointIds())
                if (active.enabledCheckpoints().stream().noneMatch(c -> c.id().equals(id))) e.put("code_checkpoint_ids", "unknown checkpoint " + id);
        }
        if (d.codeAmountToman() <= 0) e.put("code_amount_toman", "must be positive");
        if (d.validityDays() <= 0) e.put("validity_days", "must be positive");
        if (d.codeCap() <= 0) e.put("code_cap", "must be above 0");
        if (d.budgetToman() <= 0) e.put("budget_toman", "must be above 0");
        if (d.terms() == null || d.terms().isBlank() || d.terms().length() > 500) e.put("terms", "1–500 characters");
        if (e.isEmpty() && ctx.campaigns().overlaps(d.startsAt(), d.endsAt(), except)) e.put("starts_at", "overlaps another campaign");
        if (!e.isEmpty()) throw GameException.validation("campaign is invalid", Map.of("fields", e));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Optional<StoredConfig> currentDraft() {
        return ctx.configs().versions(20).stream().filter(v -> "DRAFT".equals(v.status())).findFirst();
    }

    /** Content revision of a draft: a hash of its canonical JSON, so equal content has the same revision. */
    String revision(GameConfig c) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(json.apply(c).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void requireRevision(StoredConfig draft, String expected) {
        String actual = draft == null ? null : revision(draft.config());
        if (Objects.equals(actual, expected)) return;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("draft_version", draft == null ? null : draft.version());
        d.put("draft_revision", actual);
        throw GameException.conflict("DRAFT_CHANGED", draft == null ? "the draft was published or discarded; reload"
                : "the draft changed since you loaded it; reload and review it", d);
    }

    List<FieldChange> diff(GameConfig before, GameConfig after) {
        Map<String, Object> b = flatten.apply(before), a = flatten.apply(after);
        Set<String> keys = new TreeSet<>(b.keySet());
        keys.addAll(a.keySet());
        List<FieldChange> out = new ArrayList<>();
        for (String k : keys) if (!Objects.equals(b.get(k), a.get(k))) out.add(new FieldChange(k, b.get(k), a.get(k)));
        return out;
    }

    private Map<String, Object> bounds() {
        ConfigValidator.Bounds b = ctx.bounds();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("max_plays_per_day", b.maxPlaysPerDay());
        m.put("max_checkpoint_reward", b.maxCheckpointReward());
        m.put("max_continue_cost", b.maxContinueCost());
        m.put("max_continue_cap", b.maxContinueCap());
        m.put("max_streak_reward", b.maxStreakReward());
        m.put("status", b.equals(ConfigValidator.Bounds.pending()) ? "pending owner values: only structural invariants are enforced" : "configured");
        return m;
    }
}

package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.CampaignRepository;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Campaign;
import com.digikala.supercoin.gamification.games.priceexpert.infrastructure.config.Json;
import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

public final class JdbcCampaignRepository implements CampaignRepository {

    @Override public Optional<Campaign> liveAt(Instant t) {
        return one("SELECT * FROM pe_campaign WHERE status = 'SCHEDULED' AND starts_at <= ? AND ends_at > ? ORDER BY starts_at LIMIT 1",
                JdbcCampaignRepository::campaign, t, t);
    }

    @Override public Optional<Campaign> find(String id) {
        return one("SELECT * FROM pe_campaign WHERE campaign_id = ?", JdbcCampaignRepository::campaign, id);
    }

    @Override public List<Campaign> list() {
        return query("SELECT * FROM pe_campaign ORDER BY starts_at DESC", JdbcCampaignRepository::campaign);
    }

    @Override public void insert(Campaign c, String actor, Instant now) {
        Sql.update("""
            INSERT INTO pe_campaign (campaign_id, name, starts_at, ends_at, code_checkpoint_ids, code_amount_toman, validity_days, terms,
              code_cap, budget_toman, codes_issued, budget_used_toman, status, created_by, created_at, updated_at)
            VALUES (?,?,?,?,?::jsonb,?,?,?,?,?,0,0,?,?,?,?)""",
            c.campaignId(), c.name(), c.startsAt(), c.endsAt(), Json.write(c.codeCheckpointIds()), c.codeAmountToman(), c.validityDays(),
            c.terms(), c.codeCap(), c.budgetToman(), c.status(), actor, now, now);
    }

    @Override public void update(Campaign c, Instant now) {
        Sql.update("""
            UPDATE pe_campaign SET name = ?, starts_at = ?, ends_at = ?, code_checkpoint_ids = ?::jsonb, code_amount_toman = ?, validity_days = ?,
              terms = ?, code_cap = ?, budget_toman = ?, status = ?, updated_at = ? WHERE campaign_id = ?""",
            c.name(), c.startsAt(), c.endsAt(), Json.write(c.codeCheckpointIds()), c.codeAmountToman(), c.validityDays(), c.terms(),
            c.codeCap(), c.budgetToman(), c.status(), now, c.campaignId());
    }

    @Override public boolean reserveCode(String campaignId, long amount) {
        return Sql.update("""
            UPDATE pe_campaign SET codes_issued = codes_issued + 1, budget_used_toman = budget_used_toman + ?
            WHERE campaign_id = ? AND codes_issued < code_cap AND budget_used_toman + ? <= budget_toman""", amount, campaignId, amount) == 1;
    }

    @Override public boolean overlaps(Instant startsAt, Instant endsAt, String except) {
        return one("SELECT 1 FROM pe_campaign WHERE status <> 'ENDED' AND campaign_id <> ? AND starts_at < ? AND ends_at > ? LIMIT 1",
                rs -> true, except == null ? "" : except, endsAt, startsAt).isPresent();
    }

    @Override public Optional<Entitlement> entitlement(String campaignId, String userId) {
        return one("SELECT * FROM pe_campaign_entitlement WHERE campaign_id = ? AND user_id = ?", JdbcCampaignRepository::entitlement, campaignId, userId);
    }

    @Override public Optional<Entitlement> entitlementOfRun(String runId) {
        return one("SELECT * FROM pe_campaign_entitlement WHERE run_id = ?", JdbcCampaignRepository::entitlement, runId);
    }

    @Override public void insertEntitlement(Entitlement e) {
        Sql.update("""
            INSERT INTO pe_campaign_entitlement (entitlement_id, campaign_id, user_id, run_id, status, amount_toman, code_ciphertext, expires_at,
              provider_ref, attempts, next_attempt_at, last_error, created_at, issued_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            e.entitlementId(), e.campaignId(), e.userId(), e.runId(), e.status(), e.amountToman(), nz(e.codeCiphertext()), nz(e.expiresAt()),
            nz(e.providerRef()), e.attempts(), nz(e.nextAttemptAt()), nz(e.lastError()), e.createdAt(), nz(e.issuedAt()));
    }

    @Override public Optional<Entitlement> lockEntitlement(String id) {
        return one("SELECT * FROM pe_campaign_entitlement WHERE entitlement_id = ? FOR UPDATE", JdbcCampaignRepository::entitlement, id);
    }

    @Override public void updateEntitlement(Entitlement e) {
        Sql.update("""
            UPDATE pe_campaign_entitlement SET status = ?, code_ciphertext = ?, expires_at = ?, provider_ref = ?, attempts = ?,
              next_attempt_at = ?, last_error = ?, issued_at = ? WHERE entitlement_id = ?""",
            e.status(), nz(e.codeCiphertext()), nz(e.expiresAt()), nz(e.providerRef()), e.attempts(), nz(e.nextAttemptAt()),
            nz(e.lastError()), nz(e.issuedAt()), e.entitlementId());
    }

    @Override public List<String> dueEntitlementIds(Instant now, int limit) {
        return query("SELECT entitlement_id FROM pe_campaign_entitlement WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ?",
                rs -> rs.getString(1), now, limit);
    }

    static Campaign campaign(ResultSet rs) throws SQLException {
        List<String> cps;
        try { cps = Json.MAPPER.readValue(rs.getString("code_checkpoint_ids"), new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw new SQLException("bad code_checkpoint_ids", e); }
        return new Campaign(rs.getString("campaign_id"), rs.getString("name"), ts(rs, "starts_at"), ts(rs, "ends_at"), cps,
                rs.getLong("code_amount_toman"), rs.getInt("validity_days"), rs.getString("terms"), rs.getInt("code_cap"),
                rs.getLong("budget_toman"), rs.getInt("codes_issued"), rs.getLong("budget_used_toman"), rs.getString("status"));
    }

    static Entitlement entitlement(ResultSet rs) throws SQLException {
        return new Entitlement(rs.getString("entitlement_id"), rs.getString("campaign_id"), rs.getString("user_id"), rs.getString("run_id"),
                rs.getString("status"), rs.getLong("amount_toman"), rs.getString("code_ciphertext"), ts(rs, "expires_at"),
                rs.getString("provider_ref"), rs.getInt("attempts"), ts(rs, "next_attempt_at"), rs.getString("last_error"),
                ts(rs, "created_at"), ts(rs, "issued_at"));
    }
}

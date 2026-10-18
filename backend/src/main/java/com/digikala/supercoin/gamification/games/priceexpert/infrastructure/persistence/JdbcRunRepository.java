package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import com.digikala.supercoin.gamification.games.priceexpert.application.port.out.RunRepository;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.FinishReason;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OfferState;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.QuestionState;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.RunState;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Offer;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Question;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence.Sql.*;

public final class JdbcRunRepository implements RunRepository {

    private static final String RUN_COLS = "*";

    @Override public Optional<Run> lockRun(String runId) {
        return one("SELECT " + RUN_COLS + " FROM pe_run WHERE run_id = ? FOR UPDATE", JdbcRunRepository::run, runId);
    }

    @Override public Optional<Run> tryLockRun(String runId) {
        return one("SELECT " + RUN_COLS + " FROM pe_run WHERE run_id = ? FOR UPDATE SKIP LOCKED", JdbcRunRepository::run, runId);
    }

    @Override public Optional<Run> findRun(String runId) {
        return one("SELECT " + RUN_COLS + " FROM pe_run WHERE run_id = ?", JdbcRunRepository::run, runId);
    }

    @Override public Optional<Run> lockActiveRunOfUser(String userId) {
        return one("SELECT " + RUN_COLS + " FROM pe_run WHERE user_id = ? AND state <> 'FINISHED' FOR UPDATE", JdbcRunRepository::run, userId);
    }

    @Override public Optional<Run> findActiveRunOfUser(String userId) {
        return one("SELECT " + RUN_COLS + " FROM pe_run WHERE user_id = ? AND state <> 'FINISHED'", JdbcRunRepository::run, userId);
    }

    @Override public void insertRun(Run r) {
        update("""
            INSERT INTO pe_run (run_id, user_id, device_id, state, finish_reason, no_offer_reason, state_revision, start_business_date,
              started_at, finished_at, config_version, catalog_version, campaign_id, score, continues_used, highest_checkpoint_id,
              reference_dkp, next_seq, ready_since, miss_question_id, miss_at, loss_question_id, next_deadline_at, record_before,
              new_record, replaced_by_run_id, campaign_outcome, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, now())""",
            r.runId, r.userId, r.deviceId, r.state, nz(r.finishReason), nz(r.noOfferReason), r.stateRevision, r.startBusinessDate,
            r.startedAt, nz(r.finishedAt), r.configVersion, r.catalogVersion, nz(r.campaignId), r.score, r.continuesUsed,
            nz(r.highestCheckpointId), r.referenceDkp, r.nextSeq, nz(r.readySince), nz(r.missQuestionId), nz(r.missAt),
            nz(r.lossQuestionId), nz(r.nextDeadlineAt), nz(r.recordBefore), nz(r.newRecord), nz(r.replacedByRunId), nz(r.campaignOutcome));
    }

    @Override public void updateRun(Run r) {
        int n = update("""
            UPDATE pe_run SET state = ?, finish_reason = ?, no_offer_reason = ?, state_revision = state_revision + 1,
              finished_at = ?, score = ?, continues_used = ?, highest_checkpoint_id = ?, reference_dkp = ?, next_seq = ?,
              ready_since = ?, miss_question_id = ?, miss_at = ?, loss_question_id = ?, next_deadline_at = ?, record_before = ?,
              new_record = ?, replaced_by_run_id = ?, campaign_outcome = ?, updated_at = now()
            WHERE run_id = ? AND state_revision = ?""",
            r.state, nz(r.finishReason), nz(r.noOfferReason), nz(r.finishedAt), r.score, r.continuesUsed, nz(r.highestCheckpointId),
            r.referenceDkp, r.nextSeq, nz(r.readySince), nz(r.missQuestionId), nz(r.missAt), nz(r.lossQuestionId),
            nz(r.nextDeadlineAt), nz(r.recordBefore), nz(r.newRecord), nz(r.replacedByRunId), nz(r.campaignOutcome),
            r.runId, r.stateRevision);
        if (n != 1) throw new IllegalStateException("run " + r.runId + " changed concurrently (revision " + r.stateRevision + ")");
        r.stateRevision++;
    }

    @Override public List<String> dueRunIds(Instant now, int limit) {
        return query("SELECT run_id FROM pe_run WHERE state <> 'FINISHED' AND next_deadline_at <= ? ORDER BY next_deadline_at LIMIT ?",
                rs -> rs.getString(1), now, limit);
    }

    // ------------------------------------------------------------------ questions

    @Override public void insertQuestion(Question q) {
        update("""
            INSERT INTO pe_question (question_id, run_id, seq, score_before, band_index, reference_dkp, reference_price, comparison_dkp,
              comparison_price, released_at, input_deadline, accept_deadline, state, choice, received_at, settled_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            q.questionId, q.runId, q.seq, q.scoreBefore, q.bandIndex, q.referenceDkp, q.referencePrice, q.comparisonDkp,
            q.comparisonPrice, q.releasedAt, q.inputDeadline, q.acceptDeadline, q.state, nz(q.choice), nz(q.receivedAt), nz(q.settledAt));
    }

    @Override public Optional<Question> findQuestion(String id) {
        return one("SELECT * FROM pe_question WHERE question_id = ?", JdbcRunRepository::question, id);
    }

    @Override public Optional<Question> lockQuestion(String id) {
        return one("SELECT * FROM pe_question WHERE question_id = ? FOR UPDATE", JdbcRunRepository::question, id);
    }

    @Override public void updateQuestion(Question q) {
        update("UPDATE pe_question SET state = ?, choice = ?, received_at = ?, settled_at = ? WHERE question_id = ?",
                q.state, nz(q.choice), nz(q.receivedAt), nz(q.settledAt), q.questionId);
    }

    @Override public Optional<Question> openQuestion(String runId) {
        return one("SELECT * FROM pe_question WHERE run_id = ? AND state = 'OPEN'", JdbcRunRepository::question, runId);
    }

    @Override public Optional<Question> latestSettledQuestion(String runId) {
        return one("SELECT * FROM pe_question WHERE run_id = ? AND state IN ('CORRECT','WRONG','TIMED_OUT') ORDER BY seq DESC LIMIT 1",
                JdbcRunRepository::question, runId);
    }

    @Override public List<Question> questionsOfRun(String runId) {
        return query("SELECT * FROM pe_question WHERE run_id = ? ORDER BY seq", JdbcRunRepository::question, runId);
    }

    @Override public Set<Long> runProductDkps(String runId) {
        return new LinkedHashSet<>(query("""
            SELECT reference_dkp AS dkp FROM pe_question WHERE run_id = ? AND seq = 1
            UNION SELECT comparison_dkp FROM pe_question WHERE run_id = ?
            UNION SELECT reference_dkp FROM pe_run WHERE run_id = ?""", rs -> rs.getLong(1), runId, runId, runId));
    }

    // ------------------------------------------------------------------ offers

    @Override public void insertOffer(Offer o) {
        update("INSERT INTO pe_offer (offer_id, run_id, question_id, state, cost, opened_at, deadline, operation_id, closed_at) VALUES (?,?,?,?,?,?,?,?,?)",
                o.offerId, o.runId, o.questionId, o.state, o.cost, o.openedAt, o.deadline, nz(o.operationId), nz(o.closedAt));
    }

    @Override public Optional<Offer> findOffer(String offerId) {
        return one("SELECT * FROM pe_offer WHERE offer_id = ?", JdbcRunRepository::offer, offerId);
    }

    @Override public Optional<Offer> lockOffer(String offerId) {
        return one("SELECT * FROM pe_offer WHERE offer_id = ? FOR UPDATE", JdbcRunRepository::offer, offerId);
    }

    @Override public void updateOffer(Offer o) {
        update("UPDATE pe_offer SET state = ?, operation_id = ?, closed_at = ? WHERE offer_id = ?",
                o.state, nz(o.operationId), nz(o.closedAt), o.offerId);
    }

    @Override public Optional<Offer> latestOfferOfRun(String runId) {
        return one("SELECT * FROM pe_offer WHERE run_id = ? ORDER BY opened_at DESC LIMIT 1", JdbcRunRepository::offer, runId);
    }

    // ------------------------------------------------------------------ mapping

    static Run run(ResultSet rs) throws SQLException {
        Run r = new Run();
        r.runId = rs.getString("run_id");
        r.userId = rs.getString("user_id");
        r.deviceId = rs.getString("device_id");
        r.state = RunState.valueOf(rs.getString("state"));
        String fr = rs.getString("finish_reason");
        r.finishReason = fr == null ? null : FinishReason.valueOf(fr);
        r.noOfferReason = rs.getString("no_offer_reason");
        r.stateRevision = rs.getInt("state_revision");
        r.startBusinessDate = date(rs, "start_business_date");
        r.startedAt = ts(rs, "started_at");
        r.finishedAt = ts(rs, "finished_at");
        r.configVersion = rs.getInt("config_version");
        r.catalogVersion = rs.getInt("catalog_version");
        r.campaignId = rs.getString("campaign_id");
        r.score = rs.getInt("score");
        r.continuesUsed = rs.getInt("continues_used");
        r.highestCheckpointId = rs.getString("highest_checkpoint_id");
        r.referenceDkp = rs.getLong("reference_dkp");
        r.nextSeq = rs.getInt("next_seq");
        r.readySince = ts(rs, "ready_since");
        r.missQuestionId = rs.getString("miss_question_id");
        r.missAt = ts(rs, "miss_at");
        r.lossQuestionId = rs.getString("loss_question_id");
        r.nextDeadlineAt = ts(rs, "next_deadline_at");
        r.recordBefore = intOrNull(rs, "record_before");
        r.newRecord = boolOrNull(rs, "new_record");
        r.replacedByRunId = rs.getString("replaced_by_run_id");
        r.campaignOutcome = rs.getString("campaign_outcome");
        return r;
    }

    static Question question(ResultSet rs) throws SQLException {
        Question q = new Question();
        q.questionId = rs.getString("question_id");
        q.runId = rs.getString("run_id");
        q.seq = rs.getInt("seq");
        q.scoreBefore = rs.getInt("score_before");
        q.bandIndex = rs.getInt("band_index");
        q.referenceDkp = rs.getLong("reference_dkp");
        q.referencePrice = rs.getLong("reference_price");
        q.comparisonDkp = rs.getLong("comparison_dkp");
        q.comparisonPrice = rs.getLong("comparison_price");
        q.releasedAt = ts(rs, "released_at");
        q.inputDeadline = ts(rs, "input_deadline");
        q.acceptDeadline = ts(rs, "accept_deadline");
        q.state = QuestionState.valueOf(rs.getString("state"));
        String c = rs.getString("choice");
        q.choice = c == null ? null : Choice.valueOf(c);
        q.receivedAt = ts(rs, "received_at");
        q.settledAt = ts(rs, "settled_at");
        return q;
    }

    static Offer offer(ResultSet rs) throws SQLException {
        Offer o = new Offer();
        o.offerId = rs.getString("offer_id");
        o.runId = rs.getString("run_id");
        o.questionId = rs.getString("question_id");
        o.state = OfferState.valueOf(rs.getString("state"));
        o.cost = rs.getInt("cost");
        o.openedAt = ts(rs, "opened_at");
        o.deadline = ts(rs, "deadline");
        o.operationId = rs.getString("operation_id");
        o.closedAt = ts(rs, "closed_at");
        return o;
    }
}

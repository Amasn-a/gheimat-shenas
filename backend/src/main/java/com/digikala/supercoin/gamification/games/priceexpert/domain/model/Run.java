package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.FinishReason;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.RunState;

import java.time.Instant;
import java.time.LocalDate;

/** One «دست». Loaded under a row lock, changed in memory, written back with a revision check. */
public class Run {
    public String runId;
    public String userId;
    public String deviceId;
    public RunState state;
    public FinishReason finishReason;
    public String noOfferReason;
    public int stateRevision;
    public LocalDate startBusinessDate;
    public Instant startedAt;
    public Instant finishedAt;
    public int configVersion;
    public int catalogVersion;
    public String campaignId;
    public int score;
    public int continuesUsed;
    public String highestCheckpointId;
    public long referenceDkp;
    public int nextSeq;
    public Instant readySince;
    public String missQuestionId;
    public Instant missAt;
    public String lossQuestionId;
    public Instant nextDeadlineAt;
    public Integer recordBefore;
    public Boolean newRecord;
    public String replacedByRunId;
    public String campaignOutcome;

    public boolean isFinished() { return state == RunState.FINISHED; }
}

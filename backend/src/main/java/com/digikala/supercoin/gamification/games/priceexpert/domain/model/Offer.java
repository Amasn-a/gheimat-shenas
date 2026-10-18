package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OfferState;

import java.time.Instant;

/** A continue offer. Created only by the post-feedback activation (F-02); opened_at and deadline never change. */
public class Offer {
    public String offerId;
    public String runId;
    public String questionId;
    public OfferState state;
    public int cost;
    public Instant openedAt;
    public Instant deadline;
    public String operationId;
    public Instant closedAt;

    /** "Before expiry" is strict: at the deadline instant the offer is over (contract §6). */
    public boolean openAt(Instant t) { return t.isBefore(deadline); }
}

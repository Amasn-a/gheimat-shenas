package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.OfferState;

import java.time.Instant;

/**
 * A continue (extra-life) offer. Created only by the post-feedback activation (F-02); opened_at, deadline and
 * accept_deadline never change. deadline = the visible decision window; accept_deadline = the last instant a purchase
 * request may ARRIVE (inclusive). An admitted purchase may finish processing after both.
 */
public class Offer {
    public String offerId;
    public String runId;
    public String questionId;
    public OfferState state;
    public int cost;
    public Instant openedAt;
    public Instant deadline;          // visible window end (offer start + offerMs)
    public Instant acceptDeadline;    // purchase arrival deadline (offer start + offerAcceptMs), inclusive
    public Instant purchaseReceivedAt;   // arrival of the first purchase request not yet turned into an operation
    public String operationId;
    public Instant closedAt;

    /** A purchase request that ARRIVED at {@code t} is admitted: t <= accept deadline (inclusive boundary). */
    public boolean admits(Instant t) { return !t.isAfter(acceptDeadline); }

    /** An admitted charge still unresolved at this instant ends the run (continue_failed); a later debit is refunded. */
    public Instant resolveBy(long paymentResolutionMs) { return acceptDeadline.plusMillis(paymentResolutionMs); }
}

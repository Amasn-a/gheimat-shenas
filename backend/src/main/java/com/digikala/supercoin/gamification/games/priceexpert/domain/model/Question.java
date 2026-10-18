package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.Choice;
import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Enums.QuestionState;

import java.time.Instant;

/** One higher/lower decision. Prices are the catalog snapshot locked at release; the comparison price is secret while OPEN. */
public class Question {
    public String questionId;
    public String runId;
    public int seq;
    public int scoreBefore;
    public int bandIndex;
    public long referenceDkp;
    public long referencePrice;
    public long comparisonDkp;
    public long comparisonPrice;
    public Instant releasedAt;      // t0
    public Instant inputDeadline;   // t0 + inputMs
    public Instant acceptDeadline;  // t0 + acceptMs
    public QuestionState state;
    public Choice choice;
    public Instant receivedAt;
    public Instant settledAt;

    public boolean equalPrices() { return referencePrice == comparisonPrice; }

    /** Equal prices: both choices are correct (spec, R-24). */
    public boolean isCorrect(Choice c) {
        if (comparisonPrice == referencePrice) return true;
        return c == Choice.higher ? comparisonPrice > referencePrice : comparisonPrice < referencePrice;
    }
}

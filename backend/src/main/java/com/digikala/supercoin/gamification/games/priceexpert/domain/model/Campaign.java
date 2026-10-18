package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import java.time.Instant;
import java.util.List;

/** A07 campaign. Codes are additional to DigiCoin (R-29); one code per account per campaign (R-37); cap and budget (R-48). */
public record Campaign(String campaignId, String name, Instant startsAt, Instant endsAt, List<String> codeCheckpointIds,
                       long codeAmountToman, int validityDays, String terms, int codeCap, long budgetToman,
                       int codesIssued, long budgetUsedToman, String status) {
    public boolean liveAt(Instant t) { return "SCHEDULED".equals(status) && !t.isBefore(startsAt) && t.isBefore(endsAt); }
}

package com.digikala.supercoin.gamification.games.priceexpert.domain.service;

import java.time.LocalDate;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.Collection;

/**
 * Spec «استریک و پاداش روزانه»: the streak is the number of consecutive business days on which the player
 * started a run whose finish was recorded (blocked runs excluded, R-34). It is recomputed from the eligible
 * start dates after every finalisation, never incremented in place, so late or out-of-order endings cannot corrupt it.
 */
public final class StreakPolicy {
    private StreakPolicy() {}

    public record Streak(int days, LocalDate lastDate, boolean lostNotice) {}

    /**
     * @param eligibleDates start dates of finished, non-blocked runs
     * @param today         the current business date
     * @return the current streak: consecutive days ending today, or ending yesterday (still alive until today ends).
     *         {@code lostNotice} is true when the latest streak ended before yesterday, i.e. the chain is broken and
     *         the player has not yet completed a run today (lost-streak line, R-24).
     */
    public static Streak compute(Collection<LocalDate> eligibleDates, LocalDate today) {
        NavigableSet<LocalDate> dates = new TreeSet<>(eligibleDates);
        dates.removeIf(d -> d.isAfter(today));
        if (dates.isEmpty()) return new Streak(0, null, false);
        LocalDate last = dates.last();
        boolean alive = last.equals(today) || last.equals(today.minusDays(1));
        if (!alive) return new Streak(0, last, true);
        int days = 0;
        LocalDate d = last;
        while (dates.contains(d)) { days++; d = d.minusDays(1); }
        return new Streak(days, last, false);
    }
}

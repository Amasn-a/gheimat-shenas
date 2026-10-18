package com.digikala.supercoin.gamification.games.priceexpert.domain.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Business dates are derived on the server in Asia/Tehran (R-52); the device calendar is never an authority. */
public final class BusinessCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Tehran");

    private BusinessCalendar() {}

    public static LocalDate dateOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /** 00:00 Asia/Tehran of the next business day, as an instant (quota reset, CP-30 countdown). */
    public static Instant nextMidnight(Instant now) {
        return dateOf(now).plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}

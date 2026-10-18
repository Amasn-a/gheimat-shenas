package com.digikala.supercoin.gamification.games.priceexpert.application.port.out;

import com.digikala.supercoin.gamification.games.priceexpert.domain.model.Player;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository {
    record Participation(String userId, LocalDate date, int playsUsed, int finishedRuns, boolean streakEligible) {}

    Player lockOrCreatePlayer(String userId, Instant now);
    Optional<Player> findPlayer(String userId);
    void updatePlayer(Player p, Instant now);
    void setBlocked(String userId, boolean blocked, String actor, String reason, Instant now);

    Participation lockParticipation(String userId, LocalDate date);
    int playsUsed(String userId, LocalDate date);
    void saveParticipation(Participation p);
    List<LocalDate> streakEligibleDates(String userId, LocalDate since);

    void markSeen(String userId, LocalDate showDate, long dkp, Instant now);
}

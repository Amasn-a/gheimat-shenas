package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

import java.time.LocalDate;

public class Player {
    public String userId;
    public int record;
    public String recordRunId;
    public int streakDays;
    public LocalDate streakLastDate;
    public boolean onboarded;
    public boolean blocked;
}

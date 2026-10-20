package com.digikala.supercoin.gamification.games.priceexpert.domain.model;

/** Holder for the small closed vocabularies of the game. Values match the database CHECK constraints. */
public final class Enums {
    private Enums() {}

    /** Stored run state (handoff "Server states" + AWAITING_OFFER for F-02). Client screen ids are derived. */
    public enum RunState { READY, QUESTION_OPEN, AWAITING_OFFER, OFFER_OPEN, PAYMENT_PENDING, FINISHED;
        public boolean active() { return this != FINISHED; } }

    public enum FinishReason {
        wrong, timeout, skipped, expired, continue_failed, left, no_products, inactive, game_off, blocked, replaced;
        /** Endings that follow a miss: the miss question is the loss target on the products list (UX-5). */
        public boolean endsOnMiss() { return this == wrong || this == timeout || this == skipped || this == expired || this == continue_failed; }
    }

    public enum QuestionState { OPEN, CORRECT, WRONG, TIMED_OUT, UNANSWERED }

    public enum Choice { higher, lower }

    public enum OfferState { OPEN, PAYMENT_PENDING, GRANTED, SKIPPED, EXPIRED, ENDED_UNCONFIRMED, CANCELLED }

    public enum OperationType { CHARGE, CHECKPOINT_REWARD, STREAK_REWARD, REFUND;
        public boolean isCredit() { return this == CHECKPOINT_REWARD || this == STREAK_REWARD; } }

    /** PENDING = intent stored, not yet confirmed by the provider; UNKNOWN = sent, outcome not known. */
    /** CLAIMABLE: a daily (streak) reward earned and recorded, waiting for the player's claim; never picked by the worker. */
    public enum OperationStatus { CLAIMABLE, PENDING, UNKNOWN, SUCCEEDED, FAILED, HELD, NEEDS_REVIEW }

    /** Why a miss opened no continue offer (answer/activation responses; client picks the screen). */
    public enum NoOfferReason { no_funds, limit_used, game_off, disabled, blocked, no_products }
}

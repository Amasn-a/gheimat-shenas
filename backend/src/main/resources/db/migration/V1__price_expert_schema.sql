-- Price Expert («قیمت‌شناس») schema. PostgreSQL 16.
-- Conventions: every table is prefixed pe_; no cross-table foreign keys (the gamification module
-- uses ConstraintMode.NO_CONSTRAINT); money, coins and prices are BIGINT/INTEGER in explicit units;
-- instants are TIMESTAMPTZ (UTC); business dates are DATE in Asia/Tehran derived on the server.
-- Public ids (run, question, offer, operation) are 128-bit random values encoded as 26-char base32 text.

-- ---------------------------------------------------------------- configuration and catalog
CREATE TABLE pe_config_version (
    version         INTEGER PRIMARY KEY,
    status          TEXT        NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    payload         JSONB       NOT NULL,
    based_on        INTEGER,
    created_by      TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    published_by    TEXT,
    published_at    TIMESTAMPTZ
);

CREATE TABLE pe_catalog_version (
    version         INTEGER PRIMARY KEY,
    status          TEXT        NOT NULL CHECK (status IN ('STAGED','PUBLISHED','RETIRED')),
    source          TEXT        NOT NULL,
    product_count   INTEGER     NOT NULL,
    eligible_count  INTEGER     NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ
);

-- One playable entry per DKP per catalog version, bound to the displayed default variant (R-40).
CREATE TABLE pe_catalog_product (
    catalog_version INTEGER     NOT NULL,
    dkp             BIGINT      NOT NULL,
    variant_id      BIGINT      NOT NULL,
    title           TEXT        NOT NULL,
    display_title   TEXT        NOT NULL CHECK (char_length(display_title) <= 50),
    image_url       TEXT        NOT NULL,
    price_toman     BIGINT      NOT NULL CHECK (price_toman > 0),
    prev_price_toman BIGINT,
    category        TEXT,
    eligible        BOOLEAN     NOT NULL,
    exclude_reason  TEXT,
    PRIMARY KEY (catalog_version, dkp),
    CHECK (eligible OR exclude_reason IS NOT NULL)
);
CREATE INDEX pe_catalog_product_price ON pe_catalog_product (catalog_version, price_toman) WHERE eligible;

-- Single row: the active pointers, the kill switch and the player message (A05).
CREATE TABLE pe_game_settings (
    id                     SMALLINT PRIMARY KEY CHECK (id = 1),
    active_config_version  INTEGER,
    active_catalog_version INTEGER,
    game_enabled           BOOLEAN     NOT NULL DEFAULT TRUE,
    player_message         TEXT,
    revision               INTEGER     NOT NULL DEFAULT 0,
    updated_by             TEXT,
    updated_at             TIMESTAMPTZ
);
INSERT INTO pe_game_settings (id) VALUES (1);

-- Highest daily account cap effective on each business date (R-47 high-water mark).
CREATE TABLE pe_daily_cap (
    business_date   DATE PRIMARY KEY,
    cap_high_water  INTEGER NOT NULL CHECK (cap_high_water >= 0),
    updated_at      TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------- players and participation
CREATE TABLE pe_player (
    user_id          TEXT PRIMARY KEY,
    record           INTEGER     NOT NULL DEFAULT 0,
    record_run_id    TEXT,
    streak_days      INTEGER     NOT NULL DEFAULT 0,
    streak_last_date DATE,
    streak_best_before_break INTEGER NOT NULL DEFAULT 0,
    onboarded        BOOLEAN     NOT NULL DEFAULT FALSE,
    blocked          BOOLEAN     NOT NULL DEFAULT FALSE,
    blocked_at       TIMESTAMPTZ,
    blocked_by       TEXT,
    block_reason     TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL
);

-- Admission quota and streak eligibility per account and run-start business date.
CREATE TABLE pe_daily_participation (
    user_id          TEXT    NOT NULL,
    business_date    DATE    NOT NULL,
    plays_used       INTEGER NOT NULL DEFAULT 0 CHECK (plays_used >= 0),
    finished_runs    INTEGER NOT NULL DEFAULT 0,
    streak_eligible  BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id, business_date)
);

-- Products shown to an account, by the business date they were shown (R-52/R-62).
CREATE TABLE pe_seen_product (
    user_id        TEXT        NOT NULL,
    show_date      DATE        NOT NULL,
    dkp            BIGINT      NOT NULL,
    first_seen_at  TIMESTAMPTZ NOT NULL,
    last_seen_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, show_date, dkp)
);

-- ---------------------------------------------------------------- runs, questions, offers
CREATE TABLE pe_run (
    run_id              TEXT PRIMARY KEY,
    user_id             TEXT        NOT NULL,
    device_id           TEXT        NOT NULL,
    state               TEXT        NOT NULL CHECK (state IN ('READY','QUESTION_OPEN','AWAITING_OFFER','OFFER_OPEN','PAYMENT_PENDING','FINISHED')),
    finish_reason       TEXT        CHECK (finish_reason IN ('wrong','timeout','skipped','expired','continue_failed','left','no_products','inactive','game_off','blocked','replaced')),
    no_offer_reason     TEXT,
    state_revision      INTEGER     NOT NULL DEFAULT 0,
    start_business_date DATE        NOT NULL,
    started_at          TIMESTAMPTZ NOT NULL,
    finished_at         TIMESTAMPTZ,
    config_version      INTEGER     NOT NULL,
    catalog_version     INTEGER     NOT NULL,
    campaign_id         TEXT,
    score               INTEGER     NOT NULL DEFAULT 0,
    continues_used      INTEGER     NOT NULL DEFAULT 0,
    highest_checkpoint_id TEXT,
    reference_dkp       BIGINT      NOT NULL,
    next_seq            INTEGER     NOT NULL DEFAULT 1,
    ready_since         TIMESTAMPTZ,
    miss_question_id    TEXT,
    miss_at             TIMESTAMPTZ,
    loss_question_id    TEXT,
    next_deadline_at    TIMESTAMPTZ,
    record_before       INTEGER,
    new_record          BOOLEAN,
    replaced_by_run_id  TEXT,
    campaign_outcome    TEXT CHECK (campaign_outcome IN ('pending','issued','sold_out','already_issued','not_reached')),
    updated_at          TIMESTAMPTZ NOT NULL
);
-- At most one unfinished run per account (handoff: partial unique index plus a player row lock).
CREATE UNIQUE INDEX pe_run_one_active ON pe_run (user_id) WHERE state <> 'FINISHED';
CREATE INDEX pe_run_due ON pe_run (next_deadline_at) WHERE state <> 'FINISHED';
CREATE INDEX pe_run_user_started ON pe_run (user_id, started_at DESC);

CREATE TABLE pe_question (
    question_id       TEXT PRIMARY KEY,
    run_id            TEXT        NOT NULL,
    seq               INTEGER     NOT NULL,
    score_before      INTEGER     NOT NULL,
    band_index        INTEGER     NOT NULL,
    reference_dkp     BIGINT      NOT NULL,
    reference_price   BIGINT      NOT NULL,
    comparison_dkp    BIGINT      NOT NULL,
    comparison_price  BIGINT      NOT NULL,
    released_at       TIMESTAMPTZ NOT NULL,
    input_deadline    TIMESTAMPTZ NOT NULL,
    accept_deadline   TIMESTAMPTZ NOT NULL,
    state             TEXT        NOT NULL CHECK (state IN ('OPEN','CORRECT','WRONG','TIMED_OUT','UNANSWERED')),
    choice            TEXT        CHECK (choice IN ('higher','lower')),
    received_at       TIMESTAMPTZ,
    settled_at        TIMESTAMPTZ,
    UNIQUE (run_id, seq),
    UNIQUE (run_id, comparison_dkp)        -- a product is never introduced twice in one run
);

-- Offers exist only after post-feedback activation (F-02); opened_at/deadline are immutable.
CREATE TABLE pe_offer (
    offer_id      TEXT PRIMARY KEY,
    run_id        TEXT        NOT NULL,
    question_id   TEXT        NOT NULL UNIQUE,
    state         TEXT        NOT NULL CHECK (state IN ('OPEN','PAYMENT_PENDING','GRANTED','SKIPPED','EXPIRED','ENDED_UNCONFIRMED','CANCELLED')),
    cost          INTEGER     NOT NULL CHECK (cost > 0),
    opened_at     TIMESTAMPTZ NOT NULL,
    deadline      TIMESTAMPTZ NOT NULL,
    operation_id  TEXT,
    closed_at     TIMESTAMPTZ,
    CHECK (deadline > opened_at)
);
CREATE INDEX pe_offer_run ON pe_offer (run_id);

-- ---------------------------------------------------------------- money: operations ledger / outbox
-- Every charge, reward and refund is written in the same transaction as the decision that causes it.
-- A worker delivers it to the coin adapter with operation_id as the external idempotency key.
CREATE TABLE pe_coin_operation (
    operation_id        TEXT PRIMARY KEY,
    user_id             TEXT        NOT NULL,
    run_id              TEXT,
    offer_id            TEXT,
    type                TEXT        NOT NULL CHECK (type IN ('CHARGE','CHECKPOINT_REWARD','STREAK_REWARD','REFUND')),
    amount              INTEGER     NOT NULL CHECK (amount >= 0),
    held_amount         INTEGER     NOT NULL DEFAULT 0 CHECK (held_amount >= 0),
    status              TEXT        NOT NULL CHECK (status IN ('PENDING','UNKNOWN','SUCCEEDED','FAILED','HELD','NEEDS_REVIEW')),
    cap_day             DATE,
    related_operation_id TEXT,
    provider_ref        TEXT,
    attempts            INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ,
    give_up_at          TIMESTAMPTZ,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    executed_at         TIMESTAMPTZ,
    confirmed_at        TIMESTAMPTZ
);
CREATE UNIQUE INDEX pe_op_one_checkpoint_reward ON pe_coin_operation (run_id) WHERE type = 'CHECKPOINT_REWARD';
CREATE UNIQUE INDEX pe_op_one_streak_reward ON pe_coin_operation (user_id, cap_day) WHERE type = 'STREAK_REWARD';
CREATE UNIQUE INDEX pe_op_one_refund ON pe_coin_operation (related_operation_id) WHERE type = 'REFUND';
CREATE UNIQUE INDEX pe_op_one_live_charge ON pe_coin_operation (offer_id) WHERE type = 'CHARGE' AND status IN ('PENDING','UNKNOWN','SUCCEEDED');
CREATE INDEX pe_op_due ON pe_coin_operation (next_attempt_at) WHERE status IN ('PENDING','UNKNOWN');
CREATE INDEX pe_op_user ON pe_coin_operation (user_id, created_at DESC);
CREATE INDEX pe_op_run ON pe_coin_operation (run_id);

-- Account credits counted against the daily cap, keyed by the run's start business date (F-03).
CREATE TABLE pe_daily_credit (
    user_id    TEXT    NOT NULL,
    cap_day    DATE    NOT NULL,
    credited   INTEGER NOT NULL DEFAULT 0 CHECK (credited >= 0),
    held       INTEGER NOT NULL DEFAULT 0 CHECK (held >= 0),
    PRIMARY KEY (user_id, cap_day)
);

-- Coins actually paid out per execution date, for the total-payout alert (monitoring only).
CREATE TABLE pe_daily_payout (
    business_date  DATE PRIMARY KEY,
    paid           BIGINT  NOT NULL DEFAULT 0,
    alert_raised   BOOLEAN NOT NULL DEFAULT FALSE
);

-- ---------------------------------------------------------------- campaigns (A07)
CREATE TABLE pe_campaign (
    campaign_id        TEXT PRIMARY KEY,
    name               TEXT        NOT NULL,
    starts_at          TIMESTAMPTZ NOT NULL,
    ends_at            TIMESTAMPTZ NOT NULL,
    code_checkpoint_ids JSONB      NOT NULL,
    code_amount_toman  BIGINT      NOT NULL CHECK (code_amount_toman > 0),
    validity_days      INTEGER     NOT NULL CHECK (validity_days > 0),
    terms              TEXT        NOT NULL,
    code_cap           INTEGER     NOT NULL CHECK (code_cap >= 0),
    budget_toman       BIGINT      NOT NULL CHECK (budget_toman >= 0),
    codes_issued       INTEGER     NOT NULL DEFAULT 0,
    budget_used_toman  BIGINT      NOT NULL DEFAULT 0,
    status             TEXT        NOT NULL CHECK (status IN ('DRAFT','SCHEDULED','ENDED')),
    created_by         TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CHECK (ends_at > starts_at),
    CHECK (codes_issued <= code_cap),
    CHECK (budget_used_toman <= budget_toman)
);

CREATE TABLE pe_campaign_entitlement (
    entitlement_id  TEXT PRIMARY KEY,
    campaign_id     TEXT        NOT NULL,
    user_id         TEXT        NOT NULL,
    run_id          TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING','ISSUED','NEEDS_REVIEW')),
    amount_toman    BIGINT      NOT NULL,
    code_ciphertext TEXT,
    expires_at      TIMESTAMPTZ,
    provider_ref    TEXT,
    attempts        INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    issued_at       TIMESTAMPTZ,
    UNIQUE (campaign_id, user_id)
);
CREATE INDEX pe_entitlement_due ON pe_campaign_entitlement (next_attempt_at) WHERE status = 'PENDING';

-- ---------------------------------------------------------------- idempotency, audit, review, alerts
CREATE TABLE pe_idempotency (
    scope           TEXT        NOT NULL,     -- authenticated user id or admin username
    method          TEXT        NOT NULL,
    path            TEXT        NOT NULL,
    idem_key        TEXT        NOT NULL,
    body_hash       TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS','DONE')),
    response_status INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope, method, path, idem_key)
);
CREATE INDEX pe_idempotency_created ON pe_idempotency (created_at);

CREATE TABLE pe_audit_event (
    id             BIGSERIAL PRIMARY KEY,
    actor          TEXT        NOT NULL,
    actor_role     TEXT        NOT NULL,
    action         TEXT        NOT NULL,
    target         TEXT,
    before_value   JSONB,
    after_value    JSONB,
    reason         TEXT,
    correlation_id TEXT,
    at             TIMESTAMPTZ NOT NULL
);
CREATE INDEX pe_audit_at ON pe_audit_event (at DESC);
-- Append-only: forbid UPDATE and DELETE.
CREATE FUNCTION pe_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'pe_audit_event is append-only'; END $$;
CREATE TRIGGER pe_audit_no_update BEFORE UPDATE OR DELETE ON pe_audit_event FOR EACH ROW EXECUTE FUNCTION pe_audit_append_only();

CREATE TABLE pe_review (
    review_id   TEXT PRIMARY KEY,
    user_id     TEXT        NOT NULL,
    run_id      TEXT,
    outcome     TEXT        NOT NULL CHECK (outcome IN ('no_issue','suspicious','needs_more_review')),
    note        TEXT,
    actor       TEXT        NOT NULL,
    at          TIMESTAMPTZ NOT NULL
);

CREATE TABLE pe_alert (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT        NOT NULL,
    dedupe_key  TEXT        UNIQUE,
    details     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------- standalone admin (development sign-in)
CREATE TABLE pe_admin_user (
    username       TEXT PRIMARY KEY,
    display_name   TEXT    NOT NULL,
    role           TEXT    NOT NULL CHECK (role IN ('EDITOR','VIEWER')),
    password_hash  TEXT    NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE pe_admin_session (
    token_hash   TEXT PRIMARY KEY,
    username     TEXT        NOT NULL,
    role         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------- development fakes (never used in production)
CREATE TABLE pe_fake_wallet (
    user_id   TEXT PRIMARY KEY,
    balance   BIGINT NOT NULL CHECK (balance >= 0)
);
CREATE TABLE pe_fake_wallet_tx (
    provider_ref  TEXT PRIMARY KEY,           -- = our operation id (idempotency key)
    user_id       TEXT        NOT NULL,
    kind          TEXT        NOT NULL CHECK (kind IN ('CHARGE','CREDIT','REFUND')),
    amount        BIGINT      NOT NULL,
    applied       BOOLEAN     NOT NULL,
    visible_at    TIMESTAMPTZ NOT NULL,       -- before this instant a query reports PENDING (late confirmation)
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE TABLE pe_fake_scenario (
    user_id    TEXT PRIMARY KEY,
    script     JSONB NOT NULL                 -- ordered list of scripted outcomes consumed by the fake providers
);

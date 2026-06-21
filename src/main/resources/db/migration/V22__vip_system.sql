-- VIP loyalty system: permanent tier based on lifetime wager, bi-weekly cashback

ALTER TABLE players
    ADD COLUMN vip_tier              VARCHAR(10)  NOT NULL DEFAULT 'NONE'
        COMMENT 'Permanent VIP tier — NONE/CORAL/PEARL/REEF',
    ADD COLUMN vip_lifetime_wager    BIGINT       NOT NULL DEFAULT 0
        COMMENT 'Cumulative total wager across all games (never resets)',
    ADD COLUMN vip_period_net_loss   INT          NOT NULL DEFAULT 0
        COMMENT 'Net loss in current cashback period (bet-sum minus won-sum); reset on each payout',
    ADD COLUMN vip_period_start      DATE
        COMMENT 'Start date of the current cashback period',
    ADD COLUMN vip_cashback_paid_at  DATETIME
        COMMENT 'Timestamp of the last cashback payment';

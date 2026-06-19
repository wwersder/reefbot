-- Plinko Mini App: game log table + plinko state columns on players

CREATE TABLE plinko_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id   BIGINT NOT NULL,
    island_id   BIGINT NOT NULL,
    bet         INT NOT NULL,
    slot        TINYINT NOT NULL,
    multiplier  DECIMAL(6,2) NOT NULL,
    won         INT NOT NULL,
    rows        TINYINT NOT NULL,
    risk        VARCHAR(10) NOT NULL,
    played_at   DATETIME NOT NULL,
    INDEX idx_plinko_player_played (player_id, played_at),
    INDEX idx_plinko_won           (won DESC),
    INDEX idx_plinko_multiplier    (multiplier DESC)
);

-- Daily loss tracking and cooldown per player
ALTER TABLE players
    ADD COLUMN plinko_daily_lost INT      NOT NULL DEFAULT 0,
    ADD COLUMN plinko_daily_date DATE,
    ADD COLUMN plinko_last_play  DATETIME;

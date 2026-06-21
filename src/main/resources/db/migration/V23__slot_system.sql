-- V23: The Reef House slot machine

-- Free spins state stored on the player row (temporary game state)
ALTER TABLE players
    ADD COLUMN slot_free_spins_remaining INT NOT NULL DEFAULT 0,
    ADD COLUMN slot_multiplier           INT NOT NULL DEFAULT 1;

-- Spin history
CREATE TABLE slot_logs (
    id               BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id        BIGINT       NOT NULL,
    bet              INT          NOT NULL,
    win              INT          NOT NULL DEFAULT 0,
    scatter_count    INT          NOT NULL DEFAULT 0,
    was_free_spin    TINYINT(1)   NOT NULL DEFAULT 0,
    triggered_bonus  TINYINT(1)   NOT NULL DEFAULT 0,
    grid_json        VARCHAR(600) NOT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_slot_log_player FOREIGN KEY (player_id)
        REFERENCES players(id) ON DELETE CASCADE
);

CREATE INDEX idx_slot_logs_player ON slot_logs(player_id);

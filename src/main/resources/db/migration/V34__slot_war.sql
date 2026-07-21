-- Slot «Шторм vs Штиль» — game logs and per-player state
CREATE TABLE slot_war_logs (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id        BIGINT NOT NULL,
    mode             ENUM('CALM','STORM') NOT NULL,
    bet              INT NOT NULL,
    payline_win      INT NOT NULL DEFAULT 0,
    total_mult       INT NOT NULL DEFAULT 1,
    final_win        INT NOT NULL DEFAULT 0,
    expanded_count   TINYINT NOT NULL DEFAULT 0,
    bonus_triggered  BOOLEAN NOT NULL DEFAULT FALSE,
    was_free_spin    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (player_id) REFERENCES players(id)
);

CREATE TABLE player_slot_war_state (
    player_id           BIGINT PRIMARY KEY,
    mode                ENUM('CALM','STORM') NOT NULL DEFAULT 'CALM',
    free_spins_remaining INT NOT NULL DEFAULT 0,
    sticky_columns_json  VARCHAR(200) NULL,
    fs_pending_win       INT NOT NULL DEFAULT 0,
    FOREIGN KEY (player_id) REFERENCES players(id)
);

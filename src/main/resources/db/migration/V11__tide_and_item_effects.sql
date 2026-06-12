-- Tide state (separate table, same pattern as player_fishing)
CREATE TABLE player_tide (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id           BIGINT NOT NULL UNIQUE,
    tide_available_at   DATETIME NULL,
    tide_expires_at     DATETIME NULL,
    tide_rolls_json     TEXT NULL,        -- {"rolls":[5,2,4],"rewardIndex":1,"narrativeIndex":2}
    tide_round_index    TINYINT NOT NULL DEFAULT 0,
    tide_hits           TINYINT NOT NULL DEFAULT 0,
    tide_notified       BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_player_tide_player FOREIGN KEY (player_id) REFERENCES players (id)
);

INSERT INTO player_tide (player_id) SELECT id FROM players;

-- Active item effects stored on player_fishing
ALTER TABLE player_fishing
    ADD COLUMN effect_speed_cast    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN effect_yield_bonus   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN effect_xp_bonus      BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN effect_instant_next  BOOLEAN NOT NULL DEFAULT FALSE;

-- Extract slot game state from players into dedicated table (same pattern as player_fishing)

CREATE TABLE player_slot_state (
    id                   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    player_id            BIGINT       NOT NULL,
    free_spins_remaining INT          NOT NULL DEFAULT 0,
    multiplier           INT          NOT NULL DEFAULT 1,
    sticky_wilds_json    VARCHAR(500)          DEFAULT NULL,
    fs_pending_win       INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_pss_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
    CONSTRAINT uq_pss_player UNIQUE (player_id)
);

-- Migrate existing data from players
INSERT INTO player_slot_state (player_id, free_spins_remaining, multiplier, sticky_wilds_json, fs_pending_win)
SELECT id,
       slot_free_spins_remaining,
       slot_multiplier,
       sticky_wilds_json,
       slot_fs_pending_win
FROM players;

-- Drop migrated columns
ALTER TABLE players
    DROP COLUMN slot_free_spins_remaining,
    DROP COLUMN slot_multiplier,
    DROP COLUMN sticky_wilds_json,
    DROP COLUMN slot_fs_pending_win;

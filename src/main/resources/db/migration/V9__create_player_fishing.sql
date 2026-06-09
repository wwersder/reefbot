CREATE TABLE player_fishing (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id         BIGINT NOT NULL UNIQUE,
    fishing_xp        INT NOT NULL DEFAULT 0,
    fishing_level     INT NOT NULL DEFAULT 1,
    fishing_finish_at DATETIME NULL,
    fishing_spot      VARCHAR(50) NULL,
    fishing_notified  BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_player_fishing_player FOREIGN KEY (player_id) REFERENCES players (id)
);

-- Migrate existing data
INSERT INTO player_fishing (player_id, fishing_xp, fishing_level, fishing_finish_at, fishing_spot, fishing_notified)
SELECT id, fishing_xp, fishing_level, fishing_finish_at, fishing_spot, fishing_notified FROM players;

ALTER TABLE players
    DROP COLUMN fishing_xp,
    DROP COLUMN fishing_level,
    DROP COLUMN fishing_finish_at,
    DROP COLUMN fishing_spot,
    DROP COLUMN fishing_notified;

CREATE TABLE player_state (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id               BIGINT NOT NULL UNIQUE,
    current_screen          VARCHAR(50) NOT NULL DEFAULT 'MAIN',
    has_completed_first_fish BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_player_state_player FOREIGN KEY (player_id) REFERENCES players (id)
);

-- Migrate existing data
INSERT INTO player_state (player_id, current_screen, has_completed_first_fish)
SELECT id, current_screen, has_completed_first_fish FROM players;

ALTER TABLE players
    DROP COLUMN current_screen,
    DROP COLUMN has_completed_first_fish;

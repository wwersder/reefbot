-- Daily bonus state: stored in player_state to avoid a new table for 2 fields
ALTER TABLE player_state
    ADD COLUMN daily_bonus_at DATETIME NULL,
    ADD COLUMN daily_streak   INT NOT NULL DEFAULT 0;

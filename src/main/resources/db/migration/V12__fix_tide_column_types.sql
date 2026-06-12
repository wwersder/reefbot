-- Fix column types: TINYINT → INT to match Hibernate Integer mapping
ALTER TABLE player_tide
    MODIFY COLUMN tide_round_index INT NOT NULL DEFAULT 0,
    MODIFY COLUMN tide_hits        INT NOT NULL DEFAULT 0;

-- Rebuild player_forest: replace lumberjack columns with hunter columns
ALTER TABLE player_forest
    DROP COLUMN level,
    DROP COLUMN xp,
    DROP COLUMN activity,
    ADD COLUMN hunter_level INT NOT NULL DEFAULT 1,
    ADD COLUMN hunter_xp   INT NOT NULL DEFAULT 0,
    ADD COLUMN hunting_spot VARCHAR(30) NULL;

-- Add hunting resources to islands table
ALTER TABLE islands
    ADD COLUMN meat INT NOT NULL DEFAULT 0,
    ADD COLUMN fur  INT NOT NULL DEFAULT 0;

-- Forest events: beast sighting (trap placement, revealed at collect) + patrol scan
ALTER TABLE player_forest
    ADD COLUMN patrol_scanned_at     DATETIME  NULL,
    ADD COLUMN sighting_available_at DATETIME  NULL,
    ADD COLUMN sighting_expires_at   DATETIME  NULL,
    ADD COLUMN sighting_claimed      BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN sighting_notified     BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN sighting_trap_choice  TINYINT   NULL;  -- 0/1/2 = trap index chosen; NULL = not set

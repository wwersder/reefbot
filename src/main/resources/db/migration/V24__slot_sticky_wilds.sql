-- Sticky wilds positions for Dog House-style bonus round
-- Format: [[col,row,mult],...] e.g. [[0,1,2],[3,2,1]]
ALTER TABLE players
    ADD COLUMN sticky_wilds_json VARCHAR(500) DEFAULT NULL
        AFTER slot_multiplier;

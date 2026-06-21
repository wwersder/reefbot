-- Accumulate free spin winnings; credit all at bonus end
ALTER TABLE players
    ADD COLUMN slot_fs_pending_win INT NOT NULL DEFAULT 0
        AFTER sticky_wilds_json;

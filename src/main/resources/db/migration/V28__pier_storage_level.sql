-- Separate storage level for pier (independent upgrade track)
ALTER TABLE island_buildings
    ADD COLUMN storage_level          INT      NOT NULL DEFAULT 1,
    ADD COLUMN storage_build_finish_at DATETIME          DEFAULT NULL;

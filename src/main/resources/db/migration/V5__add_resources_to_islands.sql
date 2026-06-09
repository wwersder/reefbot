ALTER TABLE islands
    ADD COLUMN wood             INT          NOT NULL DEFAULT 0,
    ADD COLUMN stone            INT          NOT NULL DEFAULT 0,
    ADD COLUMN fish             INT          NOT NULL DEFAULT 0,
    ADD COLUMN shells           INT          NOT NULL DEFAULT 0,
    ADD COLUMN coral            INT          NOT NULL DEFAULT 0,
    ADD COLUMN storage_capacity INT          NOT NULL DEFAULT 100,
    ADD COLUMN dev_points       INT          NOT NULL DEFAULT 0;

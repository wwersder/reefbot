ALTER TABLE players
    ADD COLUMN current_screen              VARCHAR(50)  NOT NULL DEFAULT 'MAIN',
    ADD COLUMN fishing_xp                  INT          NOT NULL DEFAULT 0,
    ADD COLUMN fishing_level               INT          NOT NULL DEFAULT 1,
    ADD COLUMN fishing_finish_at           DATETIME     NULL,
    ADD COLUMN fishing_spot                VARCHAR(50)  NULL,
    ADD COLUMN has_completed_first_fish    BOOLEAN      NOT NULL DEFAULT FALSE;

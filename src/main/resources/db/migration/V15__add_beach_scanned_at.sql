-- Механика «Прочесать пляж»: кулдаун 4 часа между сканированиями.
-- NULL = никогда не сканировал = сразу доступно.
ALTER TABLE player_tide
    ADD COLUMN beach_scanned_at DATETIME NULL DEFAULT NULL;

-- Оптимистичная блокировка: поле version для @Version в Player.
-- JPA инкрементирует его при каждом UPDATE; при коллизии двух потоков
-- второй получит ObjectOptimisticLockingFailureException вместо молчаливой перезаписи.
ALTER TABLE players
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

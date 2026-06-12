-- При удалении острова удаляем все его здания автоматически
ALTER TABLE island_buildings DROP FOREIGN KEY fk_ib_island;
ALTER TABLE island_buildings ADD CONSTRAINT fk_ib_island
    FOREIGN KEY (island_id) REFERENCES islands(id) ON DELETE CASCADE;

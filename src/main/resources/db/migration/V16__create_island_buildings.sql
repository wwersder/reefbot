CREATE TABLE island_buildings (
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    island_id                BIGINT       NOT NULL,
    building_type            VARCHAR(64)  NOT NULL,
    level                    INT          NOT NULL DEFAULT 1,
    build_finish_at          DATETIME     NULL,
    production_collected_at  DATETIME     NULL,

    CONSTRAINT fk_ib_island FOREIGN KEY (island_id) REFERENCES islands(id),
    CONSTRAINT uq_ib_island_type UNIQUE (island_id, building_type)
);

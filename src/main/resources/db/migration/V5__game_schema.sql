-- Buildings constructed on islands
CREATE TABLE buildings
(
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    island_id          BIGINT      NOT NULL,
    type               VARCHAR(50) NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    level              INT         NOT NULL DEFAULT 1,
    started_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_at          DATETIME    NOT NULL,
    last_collected_at  DATETIME    NULL,
    notification_sent  BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_buildings_island
        FOREIGN KEY (island_id) REFERENCES islands (id),
    CONSTRAINT uq_island_building_type
        UNIQUE (island_id, type)
);

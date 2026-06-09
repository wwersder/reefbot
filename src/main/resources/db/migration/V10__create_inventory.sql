CREATE TABLE inventory (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    item_type VARCHAR(50) NOT NULL,
    item_key  VARCHAR(100) NOT NULL,
    quantity  INT NOT NULL DEFAULT 1,
    CONSTRAINT fk_inventory_player FOREIGN KEY (player_id) REFERENCES players (id),
    CONSTRAINT uq_player_item UNIQUE (player_id, item_key)
);

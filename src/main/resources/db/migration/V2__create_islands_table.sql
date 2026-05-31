CREATE TABLE islands
(
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    player_id BIGINT NOT NULL UNIQUE,

    name VARCHAR(50) NOT NULL,

    level INT NOT NULL DEFAULT 0,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_islands_player
        FOREIGN KEY (player_id)
            REFERENCES players(id)
);
-- Forest activity sessions
CREATE TABLE player_forest (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    player_id  BIGINT NOT NULL,
    level      INT    NOT NULL DEFAULT 1,
    xp         INT    NOT NULL DEFAULT 0,
    activity   VARCHAR(30) NULL,
    finish_at  DATETIME    NULL,
    notified   TINYINT(1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_player_forest (player_id),
    CONSTRAINT fk_player_forest_player FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE
);

-- Mine activity sessions
CREATE TABLE player_mine (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    player_id  BIGINT NOT NULL,
    level      INT    NOT NULL DEFAULT 1,
    xp         INT    NOT NULL DEFAULT 0,
    depth      INT    NULL,
    finish_at  DATETIME    NULL,
    notified   TINYINT(1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_player_mine (player_id),
    CONSTRAINT fk_player_mine_player FOREIGN KEY (player_id) REFERENCES players (id) ON DELETE CASCADE
);

-- Support staff (agents who can handle tickets)
CREATE TABLE support_staff (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    telegram_id  BIGINT       NOT NULL UNIQUE,
    username     VARCHAR(255),
    first_name   VARCHAR(255),
    role         ENUM('SUPPORT','SUPER_ADMIN') NOT NULL DEFAULT 'SUPPORT',
    granted_by   BIGINT       NOT NULL,
    granted_at   DATETIME     NOT NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    INDEX idx_staff_telegram_id (telegram_id)
);

-- Support tickets
CREATE TABLE support_tickets (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id         BIGINT NOT NULL,
    status            ENUM('OPEN','IN_PROGRESS','RESOLVED','CLOSED_BY_PLAYER') NOT NULL DEFAULT 'OPEN',
    claimed_by        BIGINT NULL,
    root_group_msg_id BIGINT NULL,
    created_at        DATETIME NOT NULL,
    updated_at        DATETIME NOT NULL,
    resolved_at       DATETIME NULL,
    resolved_by       BIGINT NULL,
    FOREIGN KEY (player_id)   REFERENCES players(id),
    FOREIGN KEY (claimed_by)  REFERENCES support_staff(id),
    FOREIGN KEY (resolved_by) REFERENCES support_staff(id),
    INDEX idx_ticket_player (player_id),
    INDEX idx_ticket_status (status)
);

-- Support messages (full history, bidirectional)
CREATE TABLE support_messages (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id          BIGINT       NOT NULL,
    direction          ENUM('FROM_PLAYER','FROM_SUPPORT') NOT NULL,
    sender_tg_id       BIGINT       NOT NULL,
    sender_name        VARCHAR(255),
    text               TEXT         NULL,
    attachment_type    ENUM('PHOTO','DOCUMENT','VIDEO','AUDIO','VOICE',
                            'STICKER','ANIMATION','VIDEO_NOTE','FORWARDED') NULL,
    attachment_file_id VARCHAR(255) NULL,
    group_msg_id       BIGINT       NULL,
    player_msg_id      BIGINT       NULL,
    sent_at            DATETIME     NOT NULL,
    FOREIGN KEY (ticket_id) REFERENCES support_tickets(id),
    INDEX idx_msg_ticket    (ticket_id),
    INDEX idx_msg_group_id  (group_msg_id)
);

CREATE TABLE players
(
    id BIGINT PRIMARY KEY AUTO_INCREMENT,

    telegram_id BIGINT NOT NULL UNIQUE,

    username VARCHAR(255),

    onboarding_step VARCHAR(50) NOT NULL,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
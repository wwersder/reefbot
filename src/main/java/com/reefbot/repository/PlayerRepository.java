package com.reefbot.repository;

import com.reefbot.entity.Player;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    /**
     * Загружает игрока вместе со всеми связанными сущностями за один JOIN-запрос.
     * Без EntityGraph каждая EAGER-связь (state, fishing, tide) порождает отдельный SELECT (N+1).
     * При 1000 игроках это 4000 запросов вместо 1000 — существенная нагрузка на MySQL.
     */
    @EntityGraph(attributePaths = {"state", "fishing", "tide"})
    Optional<Player> findByTelegramId(Long telegramId);

    Optional<Player> findByUsernameIgnoreCase(String username);
}
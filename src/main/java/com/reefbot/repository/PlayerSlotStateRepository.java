package com.reefbot.repository;

import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerSlotState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerSlotStateRepository extends JpaRepository<PlayerSlotState, Long> {
    Optional<PlayerSlotState> findByPlayer(Player player);
}

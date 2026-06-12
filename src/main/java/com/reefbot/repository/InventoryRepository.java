package com.reefbot.repository;

import com.reefbot.entity.InventoryItem;
import com.reefbot.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<InventoryItem, Long> {

    List<InventoryItem> findByPlayerId(Long playerId);

    Optional<InventoryItem> findByPlayerIdAndItemKey(Long playerId, String itemKey);

    Optional<InventoryItem> findByPlayerAndItemTypeAndItemKey(Player player, String itemType, String itemKey);
}

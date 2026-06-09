package com.reefbot.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "inventory",
       uniqueConstraints = @UniqueConstraint(columnNames = {"player_id", "item_key"}))
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** Category: BLUEPRINT, MATERIAL, TOOL, etc. */
    private String itemType;

    /** Unique identifier: e.g. "SAWMILL_BLUEPRINT", "FISHING_ROD_BASIC" */
    private String itemKey;

    @Builder.Default
    private Integer quantity = 1;
}

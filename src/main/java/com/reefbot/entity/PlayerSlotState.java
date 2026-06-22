package com.reefbot.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Slot machine session state for The Reef House.
 * Follows the same pattern as {@link PlayerFishing} and {@link PlayerTide}:
 * game-specific state lives in its own table, not on the players row.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_slot_state")
public class PlayerSlotState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** Free spins remaining in the current bonus round (0 = not in bonus). */
    @Builder.Default
    private Integer freeSpinsRemaining = 0;

    /** Current total multiplier (sum of all sticky wild multipliers). */
    @Builder.Default
    private Integer multiplier = 1;

    /** Sticky wild positions as JSON [[col,row,mult],...]. Null when not in FS. */
    @Column(name = "sticky_wilds_json", length = 500)
    private String stickyWildsJson;

    /** Accumulated winnings during the current FS round (credited all at once at end). */
    @Builder.Default
    private Integer fsPendingWin = 0;
}

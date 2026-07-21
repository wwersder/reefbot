package com.reefbot.entity;

import com.reefbot.enums.SlotWarMode;
import jakarta.persistence.*;
import lombok.*;

/**
 * Per-player state for the «Шторм vs Штиль» slot mini-app.
 * Mirrors PlayerSlotState but for the 5×5 expanding-wild mechanic.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_slot_war_state")
public class PlayerSlotWarState {

    @Id
    @Column(name = "player_id")
    private Long playerId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "player_id")
    private Player player;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SlotWarMode mode = SlotWarMode.CALM;

    /** Number of free spins still remaining (0 = not in bonus). */
    @Builder.Default
    @Column(name = "free_spins_remaining", nullable = false)
    private Integer freeSpinsRemaining = 0;

    /**
     * Sticky expanded columns as JSON [[col,mult],...].
     * Each entry means that entire column is WILD with accumulated multiplier.
     * Null when not in free spins.
     */
    @Column(name = "sticky_columns_json", length = 200)
    private String stickyColumnsJson;

    /** Accumulated win during the current free-spins round (credited at round end). */
    @Builder.Default
    @Column(name = "fs_pending_win", nullable = false)
    private Integer fsPendingWin = 0;
}

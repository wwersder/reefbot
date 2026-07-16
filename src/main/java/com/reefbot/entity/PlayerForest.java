package com.reefbot.entity;

import com.reefbot.enums.HuntingSpot;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_forest")
public class PlayerForest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Builder.Default
    @Column(name = "hunter_level")
    private Integer hunterLevel = 1;

    @Builder.Default
    @Column(name = "hunter_xp")
    private Integer hunterXp = 0;

    /** Selected or active hunting spot; null when idle and no spot chosen. */
    @Enumerated(EnumType.STRING)
    @Column(name = "hunting_spot")
    private HuntingSpot huntingSpot;

    /** When the active hunt finishes; null when idle. */
    @Column(name = "finish_at")
    private LocalDateTime finishAt;

    /** True once the "hunt done" push notification has been sent. */
    @Builder.Default
    private Boolean notified = false;

    // ── Forest events ─────────────────────────────────────────────────────────

    /** When the player last patrolled the area (4h cooldown). Null = never → available immediately. */
    @Column(name = "patrol_scanned_at")
    private LocalDateTime patrolScannedAt;

    /** When a beast sighting event window opens. Null = not yet scheduled. */
    @Column(name = "sighting_available_at")
    private LocalDateTime sightingAvailableAt;

    /** When the sighting window closes (sightingAvailableAt + 25 min). */
    @Column(name = "sighting_expires_at")
    private LocalDateTime sightingExpiresAt;

    /** True once the player collected the sighting bonus this session. */
    @Builder.Default
    @Column(name = "sighting_claimed")
    private Boolean sightingClaimed = false;

    /** True once the push notification for the current sighting has been sent. */
    @Builder.Default
    @Column(name = "sighting_notified")
    private Boolean sightingNotified = false;

    /**
     * Which trap location the player chose (0, 1, or 2).
     * Null = player hasn't picked a trap yet this session.
     * Set by ForestTrapCallbackHandler; read by HuntingService.collectYield().
     */
    @Column(name = "sighting_trap_choice")
    private Integer sightingTrapChoice;
}

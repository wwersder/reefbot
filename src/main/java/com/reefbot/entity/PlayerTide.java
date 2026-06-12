package com.reefbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_tide")
public class PlayerTide {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    /** When the tide arrived (window opens). Null = no active tide. */
    private LocalDateTime tideAvailableAt;

    /** When the tide window closes (tideAvailableAt + 40 min). */
    private LocalDateTime tideExpiresAt;

    /**
     * JSON: {"rolls":[5,2,4],"rewardIndex":1,"narrativeIndex":2}
     * Pre-determined at tide generation. Null = not yet generated.
     */
    private String tideRollsJson;

    /** Which round the player is on (0 = not started, 1–3 = in progress). */
    @Builder.Default
    private Integer tideRoundIndex = 0;

    /** Number of correct guesses so far. */
    @Builder.Default
    private Integer tideHits = 0;

    /** True if notification has been sent for the current tide. */
    @Builder.Default
    private Boolean tideNotified = false;
}

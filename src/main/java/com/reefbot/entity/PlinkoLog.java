package com.reefbot.entity;

import com.reefbot.enums.PlinkoRisk;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "plinko_logs")
public class PlinkoLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "island_id", nullable = false)
    private Long islandId;

    /** Shells wagered. */
    private Integer bet;

    /** Final slot index (0-based). */
    private Integer slot;

    /** Multiplier applied (e.g. 2.50). */
    @Column(precision = 6, scale = 2)
    private Double multiplier;

    /** Shells returned (bet × multiplier, rounded). */
    private Integer won;

    /** Number of rows on the board (8 or 12). */
    private Integer rows;

    @Enumerated(EnumType.STRING)
    private PlinkoRisk risk;

    private LocalDateTime playedAt;
}

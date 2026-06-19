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

    /** Final slot index (0-based). DB: TINYINT */
    @Column(columnDefinition = "TINYINT")
    private Integer slot;

    /** Multiplier applied (e.g. 2.50). DB: DECIMAL(6,2) */
    @Column(columnDefinition = "DECIMAL(6,2)")
    private Double multiplier;

    /** Shells returned (bet × multiplier, rounded). */
    private Integer won;

    /** Number of rows on the board (8 or 12). DB: TINYINT */
    @Column(name = "board_rows", columnDefinition = "TINYINT")
    private Integer boardRows;

    @Enumerated(EnumType.STRING)
    private PlinkoRisk risk;

    private LocalDateTime playedAt;
}

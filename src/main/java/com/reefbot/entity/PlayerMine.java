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
@Table(name = "player_mine")
public class PlayerMine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Builder.Default
    private Integer level = 1;

    @Builder.Default
    private Integer xp = 0;

    /** Depth level of active session (1/2/3); null when idle. */
    private Integer depth;

    /** When the active mining finishes; null when idle. */
    private LocalDateTime finishAt;

    /** True once notification has been sent. */
    @Builder.Default
    private Boolean notified = false;
}

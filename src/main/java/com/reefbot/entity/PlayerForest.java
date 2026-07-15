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
}

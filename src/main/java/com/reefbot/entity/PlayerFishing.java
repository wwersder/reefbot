package com.reefbot.entity;

import com.reefbot.enums.FishingSpot;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_fishing")
public class PlayerFishing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Builder.Default
    private Integer fishingXp = 0;

    @Builder.Default
    private Integer fishingLevel = 1;

    private LocalDateTime fishingFinishAt;

    @Enumerated(EnumType.STRING)
    private FishingSpot fishingSpot;

    @Builder.Default
    private Boolean fishingNotified = false;
}

package com.reefbot.entity;

import com.reefbot.enums.PlayerScreen;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_state")
public class PlayerState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private PlayerScreen currentScreen = PlayerScreen.MAIN;

    @Builder.Default
    private Boolean hasCompletedFirstFish = false;

    // ── Daily bonus ───────────────────────────────────────────────────────────

    /** Timestamp of the last successfully claimed daily bonus (null = never). */
    private LocalDateTime dailyBonusAt;

    /** Current consecutive-day streak (1 = first claim, max meaningful = 7). */
    @Builder.Default
    private Integer dailyStreak = 0;
}

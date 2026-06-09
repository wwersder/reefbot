package com.reefbot.entity;

import com.reefbot.enums.PlayerScreen;
import jakarta.persistence.*;
import lombok.*;

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
}

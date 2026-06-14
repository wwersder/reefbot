package com.reefbot.entity;

import com.reefbot.enums.SupportRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "support_staff")
public class SupportStaff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long telegramId;

    private String username;

    private String firstName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupportRole role;

    /** Telegram ID of the admin who granted this role. */
    @Column(nullable = false)
    private Long grantedBy;

    @Column(nullable = false)
    private LocalDateTime grantedAt;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;
}

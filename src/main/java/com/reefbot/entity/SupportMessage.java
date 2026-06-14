package com.reefbot.entity;

import com.reefbot.enums.AttachmentType;
import com.reefbot.enums.MessageDirection;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "support_messages")
public class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicket ticket;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MessageDirection direction;

    @Column(nullable = false)
    private Long senderTgId;

    private String senderName;

    @Column(columnDefinition = "TEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    private AttachmentType attachmentType;

    private String attachmentFileId;

    /** Telegram message ID in the support group (for reply-chain routing). */
    private Long groupMsgId;

    /** Telegram message ID sent to the player in private chat. */
    private Long playerMsgId;

    @Column(nullable = false)
    private LocalDateTime sentAt;
}

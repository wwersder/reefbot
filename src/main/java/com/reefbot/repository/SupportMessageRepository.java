package com.reefbot.repository;

import com.reefbot.entity.SupportMessage;
import com.reefbot.entity.SupportTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {

    /** Looks up a tracked message by its ID in the support group (for reply-chain routing). */
    Optional<SupportMessage> findByGroupMsgId(Long groupMsgId);

    List<SupportMessage> findAllByTicketOrderBySentAtAsc(SupportTicket ticket);

    /** Last message in a ticket (for previews). */
    Optional<SupportMessage> findTopByTicketOrderBySentAtDesc(SupportTicket ticket);

    /** Last N messages in a ticket (for /ticket detail). */
    List<SupportMessage> findTop5ByTicketOrderBySentAtDesc(SupportTicket ticket);
}

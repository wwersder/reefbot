package com.reefbot.repository;

import com.reefbot.entity.Player;
import com.reefbot.entity.SupportTicket;
import com.reefbot.enums.TicketStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, Long> {

    /** Finds the player's currently active ticket (OPEN or IN_PROGRESS). */
    Optional<SupportTicket> findByPlayerAndStatusIn(Player player, List<TicketStatus> statuses);

    List<SupportTicket> findAllByStatusInOrderByCreatedAtDesc(List<TicketStatus> statuses, Pageable pageable);

    List<SupportTicket> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<SupportTicket> findAllByPlayerOrderByCreatedAtDesc(Player player);

    long countByStatus(TicketStatus status);

    long countByStatusAndResolvedAtBetween(TicketStatus status, LocalDateTime from, LocalDateTime to);

    /** Average response time in seconds for resolved tickets since :since (native MySQL). */
    @Query(value = "SELECT AVG(TIMESTAMPDIFF(SECOND, created_at, resolved_at)) " +
                   "FROM support_tickets WHERE status = 'RESOLVED' AND resolved_at >= :since",
           nativeQuery = true)
    Double avgResponseTimeSecondsSince(LocalDateTime since);
}

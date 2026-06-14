package com.reefbot.web;

import com.reefbot.entity.Player;
import com.reefbot.entity.SupportTicket;
import com.reefbot.enums.TicketStatus;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.repository.SupportMessageRepository;
import com.reefbot.repository.SupportStaffRepository;
import com.reefbot.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminController {

    private final SupportTicketRepository ticketRepository;
    private final SupportMessageRepository messageRepository;
    private final SupportStaffRepository staffRepository;
    private final PlayerRepository playerRepository;

    @GetMapping("/login")
    public String loginPage() {
        return "admin/login";
    }

    // ── Dashboard ─────────────────────────────────────────────────────────

    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        long openCount       = ticketRepository.countByStatus(TicketStatus.OPEN);
        long inProgressCount = ticketRepository.countByStatus(TicketStatus.IN_PROGRESS);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        long resolvedToday = ticketRepository.countByStatusAndResolvedAtBetween(
                TicketStatus.RESOLVED, todayStart, LocalDateTime.now());

        Double avgSecondsObj = ticketRepository.avgResponseTimeSecondsSince(todayStart);
        String avgResponse = avgSecondsObj == null ? "—" : formatDuration(avgSecondsObj.longValue());

        Pageable top10 = PageRequest.of(0, 10);
        List<SupportTicket> recentTickets = ticketRepository.findAllByOrderByCreatedAtDesc(top10);

        model.addAttribute("openCount", openCount);
        model.addAttribute("inProgressCount", inProgressCount);
        model.addAttribute("resolvedToday", resolvedToday);
        model.addAttribute("avgResponse", avgResponse);
        model.addAttribute("recentTickets", recentTickets);

        return "admin/dashboard";
    }

    // ── Ticket list ───────────────────────────────────────────────────────

    @GetMapping("/tickets")
    public String ticketList(@RequestParam(required = false) String status, Model model) {
        Pageable pageable = PageRequest.of(0, 50);

        List<SupportTicket> tickets;
        if (status != null && !status.isBlank()) {
            try {
                TicketStatus filter = TicketStatus.valueOf(status.toUpperCase());
                tickets = ticketRepository.findAllByStatusInOrderByCreatedAtDesc(List.of(filter), pageable);
            } catch (IllegalArgumentException e) {
                tickets = ticketRepository.findAllByOrderByCreatedAtDesc(pageable);
            }
        } else {
            tickets = ticketRepository.findAllByOrderByCreatedAtDesc(pageable);
        }

        model.addAttribute("tickets", tickets);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("allStatuses", TicketStatus.values());

        return "admin/tickets";
    }

    // ── Ticket detail ─────────────────────────────────────────────────────

    @GetMapping("/tickets/{id}")
    public String ticketDetail(@PathVariable Long id, Model model) {
        Optional<SupportTicket> ticketOpt = ticketRepository.findById(id);
        if (ticketOpt.isEmpty()) {
            model.addAttribute("error", "Тикет #" + id + " не найден.");
            return "admin/error";
        }

        SupportTicket ticket = ticketOpt.get();
        model.addAttribute("ticket", ticket);
        model.addAttribute("messages", messageRepository.findAllByTicketOrderBySentAtAsc(ticket));

        return "admin/ticket-detail";
    }

    // ── Player history ────────────────────────────────────────────────────

    @GetMapping("/players/{telegramId}/tickets")
    public String playerTickets(@PathVariable Long telegramId, Model model) {
        Optional<Player> playerOpt = playerRepository.findByTelegramId(telegramId);
        if (playerOpt.isEmpty()) {
            model.addAttribute("error", "Игрок с Telegram ID " + telegramId + " не найден.");
            return "admin/error";
        }

        Player player = playerOpt.get();
        List<SupportTicket> tickets = ticketRepository.findAllByPlayerOrderByCreatedAtDesc(player);

        model.addAttribute("player", player);
        model.addAttribute("tickets", tickets);

        return "admin/player-tickets";
    }

    // ── Staff ─────────────────────────────────────────────────────────────

    @GetMapping("/staff")
    public String staff(Model model) {
        model.addAttribute("staff", staffRepository.findAllByActiveTrue());
        return "admin/staff";
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + " сек";
        if (seconds < 3600) return (seconds / 60) + " мин";
        return (seconds / 3600) + " ч " + ((seconds % 3600) / 60) + " мин";
    }
}

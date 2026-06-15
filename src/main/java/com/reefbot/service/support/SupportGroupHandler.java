package com.reefbot.service.support;

import com.reefbot.config.SupportProperties;
import com.reefbot.entity.SupportStaff;
import com.reefbot.entity.SupportTicket;
import com.reefbot.enums.SupportRole;
import com.reefbot.enums.TicketStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Optional;

/**
 * Handles all messages arriving in the support group:
 * - Replies from staff → relayed to the corresponding player
 * - Slash commands: /resolve, /transfer, /history, /tickets, /grantsupport, /revokesupport
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportGroupHandler {

    private final SupportService supportService;
    private final SupportProperties supportProperties;
    private final TelegramClient telegramClient;

    @Transactional
    public void handle(Update update) {
        if (!update.hasMessage()) return;
        Message message = update.getMessage();
        Long chatId    = message.getChatId();
        Long senderTgId = message.getFrom().getId();

        String text = message.hasText() ? message.getText() : null;

        // ── Commands ─────────────────────────────────────────────────────
        if (text != null && text.startsWith("/")) {
            handleCommand(message, senderTgId, chatId, text);
            return;
        }

        // ── Replies from support staff ────────────────────────────────────
        if (message.getReplyToMessage() != null) {
            handleStaffReply(message, senderTgId, chatId);
        }
    }

    // ── Command dispatch ──────────────────────────────────────────────────

    private void handleCommand(Message message, Long senderTgId, Long chatId, String text) {
        // Strip bot username suffix (e.g. /resolve@reefbot_bot)
        String cmd = text.split("\\s+")[0].split("@")[0].toLowerCase();
        String args = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : "";

        String reply = switch (cmd) {
            case "/resolve"       -> handleResolve(message, senderTgId);
            case "/close"         -> handleClose(message, senderTgId, args);
            case "/reply"         -> handleReply(senderTgId, args);
            case "/transfer"      -> handleTransfer(message, senderTgId, args);
            case "/history"       -> handleHistory(chatId, args);
            case "/tickets"       -> handleTickets(chatId, args);
            case "/ticket"        -> handleTicketDetail(chatId, args);
            case "/grantsupport"  -> handleGrant(message, senderTgId, args, SupportRole.SUPPORT);
            case "/supersupport"  -> handleGrant(message, senderTgId, args, SupportRole.SUPER_ADMIN);
            case "/revokesupport" -> handleRevoke(senderTgId, args);
            default               -> null;
        };

        if (reply != null) {
            sendToGroup(chatId, reply);
        }
    }

    // ── /resolve ──────────────────────────────────────────────────────────

    private String handleResolve(Message message, Long senderTgId) {
        Long ticketId = resolveTicketIdFromReply(message);
        if (ticketId == null) return "⚠️ Используй /resolve как реплай на сообщение тикета.";
        return supportService.resolveTicket(ticketId, senderTgId);
    }

    // ── /close [id] ───────────────────────────────────────────────────────

    private String handleClose(Message message, Long senderTgId, String args) {
        // /close 16  — by ID
        if (!args.isEmpty()) {
            try {
                long id = Long.parseLong(args.trim());
                return supportService.closeTicketById(id, senderTgId);
            } catch (NumberFormatException e) {
                return "⚠️ Использование: /close <id>  или /close как реплай на тикет.";
            }
        }
        // /close as reply
        Long ticketId = resolveTicketIdFromReply(message);
        if (ticketId == null) return "⚠️ Использование: /close <id>  или /close как реплай на тикет.";
        return supportService.closeTicketById(ticketId, senderTgId);
    }

    // ── /ticket <id> ──────────────────────────────────────────────────────

    private String handleTicketDetail(Long chatId, String args) {
        if (args.isEmpty()) return "Использование: /ticket &lt;id&gt;";

        long ticketId;
        try {
            ticketId = Long.parseLong(args.trim());
        } catch (NumberFormatException e) {
            return "⚠️ ID тикета должен быть числом.";
        }

        String detail = supportService.buildTicketDetail(ticketId);
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(detail)
                    .parseMode("HTML")
                    .replyMarkup(supportService.buildTicketDetailKeyboard(ticketId))
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send ticket detail for #{}", ticketId, e);
        }
        return null; // already sent
    }

    // ── /reply <id> <text> ────────────────────────────────────────────────

    private String handleReply(Long senderTgId, String args) {
        if (args.isEmpty()) return "Использование: /reply &lt;id&gt; &lt;текст&gt;";

        String[] parts = args.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            return "Использование: /reply &lt;id&gt; &lt;текст&gt;";
        }

        long ticketId;
        try {
            ticketId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return "⚠️ ID тикета должен быть числом.";
        }

        return supportService.replyToTicket(ticketId, senderTgId, parts[1].trim());
    }

    // ── /transfer @username ───────────────────────────────────────────────

    private String handleTransfer(Message message, Long senderTgId, String args) {
        if (args.isEmpty()) return "Использование: /transfer @username";
        Long ticketId = resolveTicketIdFromReply(message);
        if (ticketId == null) return "⚠️ Используй /transfer как реплай на сообщение тикета.";
        return supportService.transferTicket(ticketId, senderTgId, args);
    }

    // ── /history <id> ─────────────────────────────────────────────────────

    private String handleHistory(Long chatId, String args) {
        if (args.isEmpty()) return "Использование: /history &lt;id&gt;";

        long ticketId;
        try {
            ticketId = Long.parseLong(args.trim());
        } catch (NumberFormatException e) {
            return "⚠️ ID тикета должен быть числом.";
        }

        String summary = supportService.buildHistorySummary(ticketId);
        // Send separately so inline button is included
        try {
            SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                    .chatId(chatId)
                    .text(summary)
                    .parseMode("HTML")
                    .replyMarkup(historyKeyboard(ticketId));
            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            log.error("Failed to send history for ticket #{}", ticketId, e);
        }
        return null; // already sent inline
    }

    // ── /tickets @username ────────────────────────────────────────────────

    private String handleTickets(Long chatId, String args) {
        return supportService.buildActiveTicketsList();
    }

    // ── /grantsupport / /supersupport ────────────────────────────────────

    private String handleGrant(Message message, Long senderTgId, String args, SupportRole role) {
        String cmdName = role == SupportRole.SUPPORT ? "grantsupport" : "supersupport";

        // /grantsupport @username — lookup by username in players table
        if (!args.isEmpty()) {
            return supportService.grantSupportByUsername(senderTgId, args, role);
        }

        // /grantsupport as reply
        if (message.getReplyToMessage() == null) {
            return "⚠️ Используй /" + cmdName + " @username  или реплай на сообщение нужного пользователя.";
        }

        org.telegram.telegrambots.meta.api.objects.message.Message replyTo = message.getReplyToMessage();
        Long targetTgId  = replyTo.getFrom().getId();
        String username  = replyTo.getFrom().getUserName();
        String firstName = replyTo.getFrom().getFirstName();

        return supportService.grantSupport(senderTgId, username, firstName, targetTgId, role);
    }

    // ── /revokesupport @username ──────────────────────────────────────────

    private String handleRevoke(Long senderTgId, String args) {
        if (args.isEmpty()) return "Использование: /revokesupport @username";
        return supportService.revokeSupport(senderTgId, args);
    }

    // ── Staff reply relay ─────────────────────────────────────────────────

    private void handleStaffReply(Message message, Long senderTgId, Long chatId) {
        Optional<SupportStaff> staffOpt = supportService.findStaff(senderTgId);
        if (staffOpt.isEmpty()) return; // Not a staff member — ignore

        SupportStaff staff = staffOpt.get();

        // Find the ticket this reply belongs to
        Long replyToMsgId = (long) message.getReplyToMessage().getMessageId();
        Optional<SupportTicket> ticketOpt = supportService.findTicketByGroupMsgId(replyToMsgId);
        if (ticketOpt.isEmpty()) {
            // Might be a reply to a header message — no ticket found, ignore silently
            return;
        }

        SupportTicket ticket = ticketOpt.get();
        if (!ticket.getStatus().isActive()) return;

        // Permission: only claimant or SUPER_ADMIN after ticket is claimed
        if (ticket.getClaimedBy() != null
                && !ticket.getClaimedBy().getId().equals(staff.getId())
                && staff.getRole() != SupportRole.SUPER_ADMIN) {
            sendToGroup(chatId, "⚠️ Тикет #" + ticket.getId() + " взял другой сотрудник. Ты не можешь ответить.");
            return;
        }

        Long groupMsgId = (long) message.getMessageId();

        if (message.hasText()) {
            supportService.relayStaffText(ticket, staff, message.getText(), groupMsgId);
        } else {
            supportService.relayStaffMedia(ticket, staff, message, groupMsgId);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Traverses the reply chain to find the ticket ID.
     * Checks the direct reply target first, then falls back to root ticket lookup.
     */
    private Long resolveTicketIdFromReply(Message message) {
        if (message.getReplyToMessage() == null) return null;
        Long replyToMsgId = (long) message.getReplyToMessage().getMessageId();
        return supportService.findTicketByGroupMsgId(replyToMsgId)
                .map(SupportTicket::getId)
                .orElse(null);
    }

    private InlineKeyboardMarkup historyKeyboard(long ticketId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🌐 Открыть полную историю")
                                .url(buildAdminUrl() + "/tickets/" + ticketId)
                                .build()
                )))
                .build();
    }

    private String buildAdminUrl() {
        return supportProperties.getAdminUrl();
    }

    private void sendToGroup(Long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send group message", e);
        }
    }
}

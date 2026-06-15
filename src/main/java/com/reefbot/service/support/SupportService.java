package com.reefbot.service.support;

import com.reefbot.config.SupportProperties;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.*;
import com.reefbot.enums.*;
import com.reefbot.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageCaption;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupportService {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    private final SupportTicketRepository ticketRepository;
    private final SupportMessageRepository messageRepository;
    private final SupportStaffRepository staffRepository;
    private final PlayerRepository playerRepository;
    private final SupportProperties props;
    private final TelegramClient telegramClient;

    // ── Player commands ───────────────────────────────────────────────────

    /**
     * Handles /support, /myticket, /closeticket commands from a player in private chat.
     */
    @Transactional
    public BotResponse handlePlayerCommand(Player player, String text) {
        if (text.startsWith("/support")) {
            return handleCreateTicket(player, text.substring(8).trim());
        }
        if ("/myticket".equals(text)) {
            return handleMyTicket(player);
        }
        if ("/closeticket".equals(text)) {
            return handleCloseTicket(player);
        }
        return null;
    }

    private BotResponse handleCreateTicket(Player player, String messageText) {
        if (player.isSupportBlocked()) {
            return BotResponse.html("🚫 Тебе закрыт доступ к поддержке.");
        }

        Optional<SupportTicket> existing = ticketRepository.findByPlayerAndStatusIn(
                player, List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS));

        // If ticket already open and text provided, relay it as a follow-up
        if (existing.isPresent()) {
            if (!messageText.isEmpty()) {
                if (countUnrespondedPlayerMessages(existing.get()) >= MAX_UNRESPONDED_MESSAGES) {
                    return rateLimitResponse();
                }
                boolean sent = relayPlayerText(player, messageText, null);
                return sent
                    ? new BotResponse("📨 Сообщение передано в поддержку.")
                    : new BotResponse("⚠️ Не удалось отправить сообщение. Попробуй позже.");
            }
            SupportTicket t = existing.get();
            return BotResponse.html(
                "У тебя уже есть открытое обращение <b>#" + t.getId() + "</b> "
                + "(" + t.getStatus().emoji() + " " + t.getStatus().displayName() + ").\n\n"
                + "Продолжай писать, я всё передаю. Закрыть: /closeticket"
            );
        }

        if (messageText.isEmpty()) {
            return BotResponse.html(
                "Напиши сообщение после команды:\n<code>/support твой вопрос</code>\n\n"
                + "Например: /support Застряла рыбалка"
            );
        }

        return createTicket(player, messageText);
    }

    @Transactional
    public BotResponse createTicket(Player player, String messageText) {
        LocalDateTime now = LocalDateTime.now();

        SupportTicket ticket = SupportTicket.builder()
                .player(player)
                .status(TicketStatus.OPEN)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ticket = ticketRepository.save(ticket);

        // Post to support group
        Long groupMsgId = postTicketToGroup(ticket, player, messageText);
        if (groupMsgId != null) {
            ticket.setRootGroupMsgId(groupMsgId);
            ticket = ticketRepository.save(ticket);
        }

        // Save initial player message
        SupportMessage msg = SupportMessage.builder()
                .ticket(ticket)
                .direction(MessageDirection.FROM_PLAYER)
                .senderTgId(player.getTelegramId())
                .senderName(displayName(player))
                .text(messageText)
                .groupMsgId(groupMsgId)
                .sentAt(now)
                .build();
        messageRepository.save(msg);

        log.info("Ticket #{} created by player {} (tg={})", ticket.getId(),
                player.getId(), player.getTelegramId());

        return BotResponse.html(
            "✅ <b>Обращение #" + ticket.getId() + " принято</b> — скоро ответим!\n\n"
            + "Статус: /myticket"
        );
    }

    private BotResponse handleMyTicket(Player player) {
        Optional<SupportTicket> opt = ticketRepository.findByPlayerAndStatusIn(
                player, List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS));
        if (opt.isEmpty()) {
            return new BotResponse("У тебя нет открытых обращений.");
        }

        SupportTicket t = opt.get();

        return BotResponse.html(
            "🎫 <b>Обращение #" + t.getId() + "</b>  ·  "
            + t.getStatus().emoji() + " " + t.getStatus().displayName() + "\n"
            + "Создано: " + t.getCreatedAt().format(DATE_FMT)
            + "\n\nЗакрыть: /closeticket"
        );
    }

    private BotResponse handleCloseTicket(Player player) {
        Optional<SupportTicket> opt = ticketRepository.findByPlayerAndStatusIn(
                player, List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS));
        if (opt.isEmpty()) {
            return new BotResponse("Нет активного обращения.");
        }

        SupportTicket ticket = opt.get();
        ticket.setStatus(TicketStatus.CLOSED_BY_PLAYER);
        ticket.setResolvedAt(LocalDateTime.now());
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        // Notify support group
        notifyGroupTicketClosed(ticket, player);

        log.info("Ticket #{} closed by player {} (tg={})", ticket.getId(),
                player.getId(), player.getTelegramId());

        return BotResponse.html("🔴 <b>Обращение #" + ticket.getId() + " закрыто.</b>\n\nСпасибо за обращение!");
    }

    /** Maximum player messages allowed without a staff reply before further messages are blocked. */
    private static final int MAX_UNRESPONDED_MESSAGES = 3;

    // ── Relay: player follow-up → group ──────────────────────────────────

    /**
     * Relays a follow-up text message from a player (who has an open ticket) to the support group.
     * Called from ReefBot when the player sends a plain text that isn't a command.
     */
    @Transactional
    public boolean relayPlayerText(Player player, String text, Long playerMsgId) {
        Optional<SupportTicket> opt = ticketRepository.findByPlayerAndStatusIn(
                player, List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS));
        if (opt.isEmpty()) return false;

        SupportTicket ticket = opt.get();
        if (props.getGroupChatId() == null || props.getGroupChatId() == 0) return false;

        if (countUnrespondedPlayerMessages(ticket) >= MAX_UNRESPONDED_MESSAGES) return false;

        try {
            // Reply to root message in group so thread stays connected
            SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                    .chatId(props.getGroupChatId())
                    .text("💬 <b>" + escapeHtml(displayName(player)) + "</b>\n" + escapeHtml(text))
                    .parseMode("HTML");

            if (ticket.getRootGroupMsgId() != null) {
                builder.replyToMessageId(ticket.getRootGroupMsgId().intValue());
            }

            org.telegram.telegrambots.meta.api.objects.message.Message sent =
                    telegramClient.execute(builder.build());

            SupportMessage msg = SupportMessage.builder()
                    .ticket(ticket)
                    .direction(MessageDirection.FROM_PLAYER)
                    .senderTgId(player.getTelegramId())
                    .senderName(displayName(player))
                    .text(text)
                    .groupMsgId((long) sent.getMessageId())
                    .playerMsgId(playerMsgId)
                    .sentAt(LocalDateTime.now())
                    .build();
            messageRepository.save(msg);

            ticket.setUpdatedAt(LocalDateTime.now());
            ticketRepository.save(ticket);
            return true;

        } catch (TelegramApiException e) {
            log.error("Failed to relay player text to group for ticket #{}", ticket.getId(), e);
            return false;
        }
    }

    /**
     * Relays a media message from a player to the support group via copyMessage.
     */
    @Transactional
    public BotResponse relayPlayerMedia(Player player, Message message, boolean isForwarded) {
        log.info("relayPlayerMedia: player={} tg={}", player.getId(), player.getTelegramId());
        Optional<SupportTicket> opt = ticketRepository.findByPlayerAndStatusIn(
                player, List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS));

        SupportTicket ticket;
        boolean isNewTicket = opt.isEmpty();

        if (isNewTicket) {
            if (isForwarded) {
                // Forwarded messages attach to an existing ticket only
                return new BotResponse(
                    "⚠️ Нет открытого обращения. Сначала напиши /support текст — потом пересылай сообщения.");
            }
            log.info("relayPlayerMedia: no open ticket for player tg={}, auto-creating", player.getTelegramId());
            LocalDateTime now = LocalDateTime.now();
            ticket = ticketRepository.save(SupportTicket.builder()
                    .player(player)
                    .status(TicketStatus.OPEN)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        } else {
            ticket = opt.get();
        }

        log.info("relayPlayerMedia: ticket={} groupChatId={}", ticket.getId(), props.getGroupChatId());
        if (props.getGroupChatId() == null || props.getGroupChatId() == 0) return null;

        if (!isNewTicket && countUnrespondedPlayerMessages(ticket) >= MAX_UNRESPONDED_MESSAGES) {
            return rateLimitResponse();
        }

        try {
            // For media messages use caption; for forwarded text messages fall back to getText()
            String rawCaption = message.getCaption() != null ? message.getCaption() : message.getText();
            if (rawCaption != null && rawCaption.toLowerCase().startsWith("/support")) {
                rawCaption = rawCaption.substring(8).trim();
            }

            CopyMessage.CopyMessageBuilder<?, ?> copyBuilder = CopyMessage.builder()
                    .fromChatId(message.getChatId())
                    .chatId(props.getGroupChatId())
                    .messageId(message.getMessageId());

            if (isNewTicket) {
                // New ticket: embed full ticket card as photo caption — 1 message total
                copyBuilder
                        .caption(buildMediaTicketCaption(ticket, player, rawCaption))
                        .parseMode("HTML")
                        .replyMarkup(buildTicketKeyboard(ticket));
            } else {
                // Follow-up: reply to existing ticket card, preserve original caption
                if (ticket.getRootGroupMsgId() != null) {
                    copyBuilder.replyToMessageId(ticket.getRootGroupMsgId().intValue());
                }
            }

            org.telegram.telegrambots.meta.api.objects.MessageId copied =
                    telegramClient.execute(copyBuilder.build());

            if (isNewTicket) {
                ticket.setRootGroupMsgId((long) copied.getMessageId());
                ticket = ticketRepository.save(ticket);
            }

            AttachmentType attachType = detectAttachmentType(message);
            String fileId = extractFileId(message);

            messageRepository.save(SupportMessage.builder()
                    .ticket(ticket)
                    .direction(MessageDirection.FROM_PLAYER)
                    .senderTgId(player.getTelegramId())
                    .senderName(displayName(player))
                    .text(rawCaption)
                    .attachmentType(attachType)
                    .attachmentFileId(fileId)
                    .groupMsgId((long) copied.getMessageId())
                    .playerMsgId((long) message.getMessageId())
                    .sentAt(LocalDateTime.now())
                    .build());

            ticket.setUpdatedAt(LocalDateTime.now());
            ticketRepository.save(ticket);

            if (isNewTicket) {
                return BotResponse.html(
                    "✅ <b>Обращение #" + ticket.getId() + " принято</b> — скоро ответим!\n\n"
                    + "Статус: /myticket");
            }
            return new BotResponse("📨 Файл передан в поддержку.");

        } catch (TelegramApiException e) {
            log.error("Failed to relay player media to group for ticket #{}", ticket.getId(), e);
            return null;
        }
    }

    private String buildMediaTicketCaption(SupportTicket ticket, Player player, String messageText) {
        Island island = player.getIsland();
        String devPoints = island != null
                ? String.valueOf(island.getDevPoints() != null ? island.getDevPoints() : 0) : "—";
        String islandName = island != null ? island.getName() : "—";

        return "🎫 <b>Тикет #" + ticket.getId() + "</b>  ·  🟡 OPEN\n\n"
            + "👤 " + escapeHtml(displayName(player))
            + "  ·  #" + player.getId()
            + "  ·  <code>" + player.getTelegramId() + "</code>\n"
            + "🏝 " + escapeHtml(islandName) + "  ·  " + devPoints + " ОР\n\n"
            + (messageText != null && !messageText.isEmpty() ? escapeHtml(messageText) + "\n\n" : "")
            + "<i>" + ticket.getCreatedAt().format(DATE_FMT) + "</i>";
    }

    private InlineKeyboardMarkup buildTicketKeyboard(SupportTicket ticket) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📋 История на сайте")
                                .url(props.getAdminUrl() + "/tickets/" + ticket.getId())
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("✅ Закрыть")
                                .callbackData("support:resolve:" + ticket.getId())
                                .build()
                )))
                .build();
    }

    // ── Relay: staff → player ─────────────────────────────────────────────

    /**
     * Relays a text reply from a staff member to the player.
     * Handles auto-claim if the ticket is unclaimed.
     *
     * @return the group message ID of the staff reply (already stored in DB)
     */
    @Transactional
    public void relayStaffText(SupportTicket ticket, SupportStaff staff,
                               String text, Long groupMsgId) {
        // Auto-claim if unclaimed
        autoClaimIfNeeded(ticket, staff);

        Long playerTgId = ticket.getPlayer().getTelegramId();

        try {
            SendMessage send = SendMessage.builder()
                    .chatId(playerTgId)
                    .text("🎫 <b>Обращение #" + ticket.getId() + "</b>\n\n"
                        + escapeHtml(text) + "\n\n"
                        + "<i>Ответить: /support &lt;текст&gt;</i>")
                    .parseMode("HTML")
                    .build();
            org.telegram.telegrambots.meta.api.objects.message.Message sent =
                    telegramClient.execute(send);

            SupportMessage msg = SupportMessage.builder()
                    .ticket(ticket)
                    .direction(MessageDirection.FROM_SUPPORT)
                    .senderTgId(staff.getTelegramId())
                    .senderName(staffDisplayName(staff))
                    .text(text)
                    .groupMsgId(groupMsgId)
                    .playerMsgId((long) sent.getMessageId())
                    .sentAt(LocalDateTime.now())
                    .build();
            messageRepository.save(msg);

            ticket.setUpdatedAt(LocalDateTime.now());
            ticketRepository.save(ticket);

            // Confirm delivery to staff in the group
            if (groupMsgId != null && props.getGroupChatId() != null) {
                try {
                    telegramClient.execute(SendMessage.builder()
                            .chatId(props.getGroupChatId())
                            .replyToMessageId(groupMsgId.intValue())
                            .text("✅ Ответ отправлен в тикет #" + ticket.getId() + ".")
                            .build());
                } catch (TelegramApiException ignored) {}
            }

        } catch (TelegramApiException e) {
            log.error("Failed to relay staff text to player for ticket #{}", ticket.getId(), e);
        }
    }

    /**
     * Relays media from a staff member to the player via copyMessage.
     */
    @Transactional
    public void relayStaffMedia(SupportTicket ticket, SupportStaff staff,
                                Message message, Long groupMsgId) {
        autoClaimIfNeeded(ticket, staff);

        Long playerTgId = ticket.getPlayer().getTelegramId();

        try {
            CopyMessage copy = CopyMessage.builder()
                    .fromChatId(message.getChatId())
                    .chatId(playerTgId)
                    .messageId(message.getMessageId())
                    .build();
            org.telegram.telegrambots.meta.api.objects.MessageId copied =
                    telegramClient.execute(copy);

            AttachmentType attachType = detectAttachmentType(message);
            String fileId = extractFileId(message);

            SupportMessage msg = SupportMessage.builder()
                    .ticket(ticket)
                    .direction(MessageDirection.FROM_SUPPORT)
                    .senderTgId(staff.getTelegramId())
                    .senderName(staffDisplayName(staff))
                    .text(message.getCaption())
                    .attachmentType(attachType)
                    .attachmentFileId(fileId)
                    .groupMsgId(groupMsgId)
                    .playerMsgId((long) copied.getMessageId())
                    .sentAt(LocalDateTime.now())
                    .build();
            messageRepository.save(msg);

            ticket.setUpdatedAt(LocalDateTime.now());
            ticketRepository.save(ticket);

        } catch (TelegramApiException e) {
            log.error("Failed to relay staff media to player for ticket #{}", ticket.getId(), e);
        }
    }

    // ── Ticket lifecycle ─────────────────────────────────────────────────

    @Transactional
    public String resolveTicket(Long ticketId, Long staffTgId) {
        SupportStaff staff = staffRepository.findByTelegramIdAndActiveTrue(staffTgId).orElse(null);
        if (staff == null) return "❌ У тебя нет прав поддержки.";

        SupportTicket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) return "❌ Тикет #" + ticketId + " не найден.";
        if (!ticket.getStatus().isActive()) return "❌ Тикет уже закрыт (" + ticket.getStatus().displayName() + ").";

        // Permission check: only claimant or SUPER_ADMIN
        if (!canAct(staff, ticket)) {
            return "❌ Этот тикет взял " + staffDisplayName(ticket.getClaimedBy()) + ". Только он или SUPER_ADMIN могут закрыть его.";
        }

        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setResolvedAt(LocalDateTime.now());
        ticket.setResolvedBy(staff);
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        // Notify player
        notifyPlayerResolved(ticket);

        log.info("Ticket #{} resolved by staff {} (tg={})", ticketId, staff.getId(), staffTgId);
        return "✅ Тикет #" + ticketId + " закрыт.";
    }

    /**
     * Sends a text reply to a ticket player directly by ticket ID.
     * Used by the /reply command — no need to find and reply to the original message.
     */
    @Transactional
    public String replyToTicket(Long ticketId, Long staffTgId, String text) {
        SupportStaff staff = staffRepository.findByTelegramIdAndActiveTrue(staffTgId).orElse(null);
        if (staff == null) return "❌ У тебя нет прав поддержки.";

        SupportTicket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) return "❌ Тикет #" + ticketId + " не найден.";
        if (!ticket.getStatus().isActive()) return "❌ Тикет #" + ticketId + " уже закрыт.";

        if (!canAct(staff, ticket)) {
            return "❌ Тикет взял " + staffDisplayName(ticket.getClaimedBy()) + ". Только он или SUPER_ADMIN могут ответить.";
        }

        autoClaimIfNeeded(ticket, staff);
        relayStaffText(ticket, staff, text, null);
        return "✅ Ответ отправлен в тикет #" + ticketId + ".";
    }

    @Transactional
    public String transferTicket(Long ticketId, Long fromStaffTgId, String toUsername) {
        SupportStaff from = staffRepository.findByTelegramIdAndActiveTrue(fromStaffTgId).orElse(null);
        if (from == null) return "❌ У тебя нет прав поддержки.";

        SupportTicket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) return "❌ Тикет #" + ticketId + " не найден.";
        if (!ticket.getStatus().isActive()) return "❌ Тикет уже закрыт.";
        if (!canAct(from, ticket)) return "❌ Нельзя передать чужой тикет.";

        // Strip leading @ if present
        String username = toUsername.startsWith("@") ? toUsername.substring(1) : toUsername;
        SupportStaff to = staffRepository.findByUsernameIgnoreCaseAndActiveTrue(username).orElse(null);
        if (to == null) return "❌ Сотрудник @" + username + " не найден или неактивен.";

        ticket.setClaimedBy(to);
        ticket.setUpdatedAt(LocalDateTime.now());
        ticketRepository.save(ticket);

        log.info("Ticket #{} transferred from {} to {}", ticketId, fromStaffTgId, to.getTelegramId());
        return "↗️ Тикет #" + ticketId + " передан " + staffDisplayName(to) + ".";
    }

    // ── Role management ──────────────────────────────────────────────────

    @Transactional
    public String grantSupportByUsername(Long grantedByTgId, String rawUsername, SupportRole role) {
        String username = rawUsername.startsWith("@") ? rawUsername.substring(1) : rawUsername;

        Player player = playerRepository.findByUsernameIgnoreCase(username).orElse(null);
        if (player == null) {
            return "❌ Игрок @" + username + " не найден. Убедись, что он зарегистрирован в боте.";
        }

        return grantSupport(grantedByTgId, player.getUsername(), null, player.getTelegramId(), role);
    }

    @Transactional
    public String grantSupport(Long grantedByTgId, String username, String firstName, Long targetTgId, SupportRole role) {
        SupportStaff granter = staffRepository.findByTelegramIdAndActiveTrue(grantedByTgId).orElse(null);
        // Only SUPER_ADMIN can grant (or the hardcoded owner)
        if (!OWNER_TELEGRAM_ID.equals(grantedByTgId)
                && (granter == null || granter.getRole() != SupportRole.SUPER_ADMIN)) {
            return "❌ Только SUPER_ADMIN может выдавать роли.";
        }

        // Check active first
        Optional<SupportStaff> existing = staffRepository.findByTelegramIdAndActiveTrue(targetTgId);
        if (existing.isPresent()) {
            return "⚠️ @" + username + " уже является " + existing.get().getRole().name() + ".";
        }

        // Reactivate inactive record if one exists (avoids UNIQUE constraint violation on telegram_id)
        Optional<SupportStaff> inactive = staffRepository.findByTelegramId(targetTgId);
        if (inactive.isPresent()) {
            SupportStaff staff = inactive.get();
            staff.setActive(true);
            staff.setRole(role);
            staff.setUsername(username);
            staff.setFirstName(firstName);
            staff.setGrantedBy(grantedByTgId);
            staff.setGrantedAt(LocalDateTime.now());
            staffRepository.save(staff);
            log.info("Support role {} re-granted to tg={} by tg={}", role, targetTgId, grantedByTgId);
            return "✅ @" + username + " теперь " + role.name() + ".";
        }

        SupportStaff staff = SupportStaff.builder()
                .telegramId(targetTgId)
                .username(username)
                .firstName(firstName)
                .role(role)
                .grantedBy(grantedByTgId)
                .grantedAt(LocalDateTime.now())
                .active(true)
                .build();
        staffRepository.save(staff);

        log.info("Support role {} granted to tg={} by tg={}", role, targetTgId, grantedByTgId);
        return "✅ @" + username + " теперь " + role.name() + ".";
    }

    @Transactional
    public String revokeSupport(Long revokerTgId, String username) {
        SupportStaff revoker = staffRepository.findByTelegramIdAndActiveTrue(revokerTgId).orElse(null);
        if (revoker == null || revoker.getRole() != SupportRole.SUPER_ADMIN) {
            return "❌ Только SUPER_ADMIN может отзывать роли.";
        }

        String clean = username.startsWith("@") ? username.substring(1) : username;
        SupportStaff target = staffRepository.findByUsernameIgnoreCaseAndActiveTrue(clean).orElse(null);
        if (target == null) return "❌ @" + clean + " не найден среди активных сотрудников.";

        target.setActive(false);
        staffRepository.save(target);

        log.info("Support role revoked from {} by tg={}", target.getTelegramId(), revokerTgId);
        return "✅ Роль @" + clean + " отозвана.";
    }

    // ── /history summary ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String buildActiveTicketsList() {
        List<SupportTicket> tickets = ticketRepository.findAllByStatusInOrderByCreatedAtDesc(
                List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS),
                org.springframework.data.domain.PageRequest.of(0, 20));

        if (tickets.isEmpty()) return "✅ Активных обращений нет.";

        StringBuilder sb = new StringBuilder("🎫 <b>Активные обращения</b> (" + tickets.size() + ")\n\n");

        for (SupportTicket t : tickets) {
            sb.append(t.getStatus().emoji()).append(" <b>#").append(t.getId()).append("</b>")
              .append("  ·  ").append(escapeHtml(displayName(t.getPlayer())));
            if (t.getClaimedBy() != null) {
                sb.append("  👷 ").append(escapeHtml(staffDisplayName(t.getClaimedBy())));
            }
            sb.append("\n");

            // Last message preview
            Optional<SupportMessage> lastMsg = messageRepository.findTopByTicketOrderBySentAtDesc(t);
            if (lastMsg.isPresent()) {
                SupportMessage m = lastMsg.get();
                String preview = m.getText() != null
                        ? truncate(m.getText(), 60)
                        : (m.getAttachmentType() != null ? "[" + m.getAttachmentType().name().toLowerCase() + "]" : "");
                String who = m.getDirection() == MessageDirection.FROM_PLAYER ? "👤" : "👷";
                sb.append(who).append(" <i>").append(escapeHtml(preview)).append("</i>")
                  .append("  ·  ").append(timeAgo(m.getSentAt())).append("\n");
            }
            sb.append("\n");
        }
        sb.append("<i>/ticket &lt;id&gt; — подробности</i>");
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String buildTicketDetail(Long ticketId) {
        SupportTicket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) return "❌ Тикет #" + ticketId + " не найден.";

        Player player = ticket.getPlayer();
        Island island = player.getIsland();

        StringBuilder sb = new StringBuilder();
        sb.append("🎫 <b>Тикет #").append(ticket.getId()).append("</b>  ·  ")
          .append(ticket.getStatus().emoji()).append(" ").append(ticket.getStatus().displayName()).append("\n\n");

        sb.append("👤 ").append(escapeHtml(displayName(player)));
        if (player.getUsername() != null) sb.append("  ·  @").append(player.getUsername());
        sb.append("  ·  <code>").append(player.getTelegramId()).append("</code>\n");
        if (island != null) {
            sb.append("🏝 ").append(escapeHtml(island.getName()))
              .append("  ·  ").append(island.getDevPoints() != null ? island.getDevPoints() : 0).append(" ОР\n");
        }
        sb.append("📅 Создан: ").append(ticket.getCreatedAt().format(DATE_FMT));
        if (ticket.getResolvedAt() != null) {
            long sec = java.time.Duration.between(ticket.getCreatedAt(), ticket.getResolvedAt()).getSeconds();
            sb.append("  →  Закрыт: ").append(ticket.getResolvedAt().format(DATE_FMT))
              .append(" (").append(formatDuration(sec)).append(")");
        }
        sb.append("\n");

        if (ticket.getClaimedBy() != null) {
            sb.append("👷 ").append(escapeHtml(staffDisplayName(ticket.getClaimedBy())));
        } else {
            sb.append("👷 не назначен");
        }

        // Time without staff reply
        List<SupportMessage> msgs = messageRepository.findTop5ByTicketOrderBySentAtDesc(ticket);
        Optional<SupportMessage> lastStaffMsg = msgs.stream()
                .filter(m -> m.getDirection() == MessageDirection.FROM_SUPPORT)
                .findFirst();
        Optional<SupportMessage> lastPlayerMsg = msgs.stream()
                .filter(m -> m.getDirection() == MessageDirection.FROM_PLAYER)
                .findFirst();
        boolean ticketIsOpen = ticket.getStatus() == TicketStatus.OPEN
                || ticket.getStatus() == TicketStatus.IN_PROGRESS;
        if (ticketIsOpen && lastPlayerMsg.isPresent()) {
            boolean staffRepliedAfter = lastStaffMsg.isPresent() &&
                    lastStaffMsg.get().getSentAt().isAfter(lastPlayerMsg.get().getSentAt());
            if (!staffRepliedAfter) {
                long sec = java.time.Duration.between(lastPlayerMsg.get().getSentAt(), java.time.LocalDateTime.now()).getSeconds();
                sb.append("  ·  ⏱ без ответа ").append(formatDuration(sec));
            }
        }
        sb.append("\n");

        // Last messages (reversed to show oldest first)
        if (!msgs.isEmpty()) {
            sb.append("\n<b>Последние сообщения:</b>\n");
            List<SupportMessage> ordered = new java.util.ArrayList<>(msgs);
            java.util.Collections.reverse(ordered);
            for (SupportMessage m : ordered) {
                String who = m.getDirection() == MessageDirection.FROM_PLAYER ? "👤" : "👷";
                String text = m.getText() != null ? truncate(m.getText(), 80)
                        : (m.getAttachmentType() != null ? "[" + m.getAttachmentType().name().toLowerCase() + "]" : "");
                sb.append("<code>").append(m.getSentAt().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))).append("</code>")
                  .append(" ").append(who).append(" <i>").append(escapeHtml(text)).append("</i>\n");
            }
        }

        return sb.toString().stripTrailing();
    }

    public InlineKeyboardMarkup buildTicketDetailKeyboard(Long ticketId) {
        SupportTicket ticket = ticketRepository.findById(ticketId).orElse(null);
        boolean isOpen = ticket != null &&
                (ticket.getStatus() == TicketStatus.OPEN || ticket.getStatus() == TicketStatus.IN_PROGRESS);

        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder builder = InlineKeyboardMarkup.builder();

        List<InlineKeyboardButton> row = new java.util.ArrayList<>();
        if (isOpen) {
            row.add(InlineKeyboardButton.builder()
                    .text("✅ Закрыть")
                    .callbackData("support:resolve:" + ticketId)
                    .build());
        }
        row.add(InlineKeyboardButton.builder()
                .text("📋 История")
                .url(props.getAdminUrl() + "/tickets/" + ticketId)
                .build());

        return builder.keyboardRow(new InlineKeyboardRow(row)).build();
    }

    @Transactional
    public String closeTicketById(Long ticketId, Long staffTgId) {
        return resolveTicket(ticketId, staffTgId);
    }

    // ── Rate limiting ─────────────────────────────────────────────────────

    /**
     * Counts how many player messages have been sent since the last staff reply.
     * If there are no staff replies at all, counts all player messages.
     */
    private int countUnrespondedPlayerMessages(SupportTicket ticket) {
        List<SupportMessage> msgs = messageRepository.findAllByTicketOrderBySentAtAsc(ticket);
        int count = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            MessageDirection dir = msgs.get(i).getDirection();
            if (dir == MessageDirection.FROM_SUPPORT) break;
            if (dir == MessageDirection.FROM_PLAYER) count++;
        }
        return count;
    }

    private BotResponse rateLimitResponse() {
        return new BotResponse(
            "Вы уже отправили несколько сообщений — дождитесь ответа специалиста.\n\n"
            + "Статус обращения: /myticket"
        );
    }

    // ── Formatting helpers ─────────────────────────────────────────────────

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String timeAgo(java.time.LocalDateTime time) {
        long sec = java.time.Duration.between(time, java.time.LocalDateTime.now()).getSeconds();
        if (sec < 60) return "только что";
        if (sec < 3600) return (sec / 60) + " мин назад";
        if (sec < 86400) return (sec / 3600) + " ч назад";
        return (sec / 86400) + " д назад";
    }

    // ── Support block / unblock ───────────────────────────────────────────

    @Transactional
    public String blockPlayerSupport(String username) {
        String clean = username.replaceAll("^@", "").trim();
        Optional<Player> opt = playerRepository.findByUsernameIgnoreCase(clean);
        if (opt.isEmpty()) return "❌ Игрок @" + clean + " не найден.";
        Player player = opt.get();
        if (player.isSupportBlocked()) return "⚠️ @" + clean + " уже заблокирован.";
        player.setSupportBlocked(true);
        playerRepository.save(player);
        log.info("Support blocked for player @{}", clean);
        return "🚫 @" + clean + " больше не может создавать обращения.";
    }

    @Transactional
    public String unblockPlayerSupport(String username) {
        String clean = username.replaceAll("^@", "").trim();
        Optional<Player> opt = playerRepository.findByUsernameIgnoreCase(clean);
        if (opt.isEmpty()) return "❌ Игрок @" + clean + " не найден.";
        Player player = opt.get();
        if (!player.isSupportBlocked()) return "⚠️ @" + clean + " и так не заблокирован.";
        player.setSupportBlocked(false);
        playerRepository.save(player);
        log.info("Support unblocked for player @{}", clean);
        return "✅ @" + clean + " снова может обращаться в поддержку.";
    }

    // ── Ticket lookup helpers ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<SupportTicket> findTicketById(Long ticketId) {
        return ticketRepository.findById(ticketId);
    }

    /**
     * Resolves a ticket and immediately edits the group message to reflect the new status.
     * Everything runs in a single transaction to avoid lazy-loading issues.
     *
     * @return true if resolved successfully, false otherwise
     */
    @Transactional
    public boolean resolveAndEditGroupMessage(Long ticketId, Long staffTgId,
                                              long groupChatId, int groupMessageId) {
        String result = resolveTicket(ticketId, staffTgId);
        if (!result.startsWith("✅")) {
            log.warn("resolveAndEditGroupMessage: resolve failed for ticket #{}: {}", ticketId, result);
            return false;
        }

        SupportTicket ticket = ticketRepository.findById(ticketId).orElse(null);
        if (ticket == null) return false;

        String newText = buildGroupTicketCardText(ticket);

        // Try text edit first; fall back to caption for photo messages
        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(groupChatId)
                    .messageId(groupMessageId)
                    .text(newText)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            try {
                telegramClient.execute(EditMessageCaption.builder()
                        .chatId(groupChatId)
                        .messageId(groupMessageId)
                        .caption(newText)
                        .parseMode("HTML")
                        .build());
            } catch (TelegramApiException e2) {
                log.warn("Failed to edit group message for resolved ticket #{}", ticketId, e2);
            }
        }
        return true;
    }

    private String buildGroupTicketCardText(SupportTicket ticket) {
        Player player = ticket.getPlayer();
        Island island = player.getIsland();
        String devPoints = island != null
                ? String.valueOf(island.getDevPoints() != null ? island.getDevPoints() : 0) : "—";
        String islandName = island != null ? island.getName() : "—";

        List<SupportMessage> msgs = messageRepository.findAllByTicketOrderBySentAtAsc(ticket);
        String firstText = msgs.stream()
                .filter(m -> m.getDirection() == MessageDirection.FROM_PLAYER && m.getText() != null)
                .map(SupportMessage::getText)
                .findFirst().orElse("");

        return "🎫 <b>Тикет #" + ticket.getId() + "</b>  ·  "
            + ticket.getStatus().emoji() + " " + ticket.getStatus().displayName() + "\n\n"
            + "👤 " + escapeHtml(displayName(player))
            + "  ·  #" + player.getId()
            + "  ·  <code>" + player.getTelegramId() + "</code>\n"
            + "🏝 " + escapeHtml(islandName) + "  ·  " + devPoints + " ОР\n\n"
            + (!firstText.isEmpty() ? escapeHtml(firstText) + "\n\n" : "")
            + "<i>" + ticket.getCreatedAt().format(DATE_FMT) + "</i>";
    }

    @Transactional(readOnly = true)
    public Optional<SupportTicket> findTicketByGroupMsgId(Long groupMsgId) {
        // Load the ticket fully (not just a lazy proxy) so callers can access fields without a session
        return messageRepository.findByGroupMsgId(groupMsgId)
                .flatMap(msg -> ticketRepository.findById(msg.getTicket().getId()));
    }

    private static final Long OWNER_TELEGRAM_ID = 920215477L;

    public Optional<SupportStaff> findStaff(Long telegramId) {
        return staffRepository.findByTelegramIdAndActiveTrue(telegramId);
    }

    public boolean isActiveStaff(Long telegramId) {
        if (OWNER_TELEGRAM_ID.equals(telegramId)) return true;
        return staffRepository.findByTelegramIdAndActiveTrue(telegramId).isPresent();
    }

    public boolean isSuperAdminOrOwner(Long telegramId) {
        if (OWNER_TELEGRAM_ID.equals(telegramId)) return true;
        return staffRepository.findByTelegramIdAndActiveTrue(telegramId)
                .map(s -> s.getRole() == SupportRole.SUPER_ADMIN)
                .orElse(false);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private Long postTicketToGroup(SupportTicket ticket, Player player, String messageText) {
        if (props.getGroupChatId() == null || props.getGroupChatId() == 0) {
            log.warn("Support group chat ID not configured — ticket #{} not posted", ticket.getId());
            return null;
        }

        Island island = player.getIsland();
        String devPoints = island != null
                ? String.valueOf(island.getDevPoints() != null ? island.getDevPoints() : 0)
                : "—";
        String islandName = island != null ? island.getName() : "—";

        String text = "🎫 <b>Тикет #" + ticket.getId() + "</b>  ·  🟡 OPEN\n\n"
            + "👤 " + escapeHtml(displayName(player))
            + "  ·  #" + player.getId()
            + "  ·  <code>" + player.getTelegramId() + "</code>\n"
            + "🏝 " + escapeHtml(islandName) + "  ·  " + devPoints + " ОР\n\n"
            + escapeHtml(messageText) + "\n\n"
            + "<i>" + ticket.getCreatedAt().format(DATE_FMT) + "</i>";

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📋 История на сайте")
                                .url(props.getAdminUrl() + "/tickets/" + ticket.getId())
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("✅ Закрыть")
                                .callbackData("support:resolve:" + ticket.getId())
                                .build()
                )))
                .build();

        try {
            SendMessage send = SendMessage.builder()
                    .chatId(props.getGroupChatId())
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build();
            org.telegram.telegrambots.meta.api.objects.message.Message sent =
                    telegramClient.execute(send);
            return (long) sent.getMessageId();

        } catch (TelegramApiException e) {
            log.error("Failed to post ticket #{} to support group", ticket.getId(), e);
            return null;
        }
    }

    private void notifyGroupTicketClosed(SupportTicket ticket, Player player) {
        if (props.getGroupChatId() == null || props.getGroupChatId() == 0) return;

        String text = "🔴 <b>Тикет #" + ticket.getId() + " закрыт игроком</b>\n\n"
                + "👤 " + escapeHtml(displayName(player))
                + "  ·  #" + player.getId()
                + "  ·  <code>" + player.getTelegramId() + "</code>";

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🌐 Открыть историю")
                                .url(props.getAdminUrl() + "/tickets/" + ticket.getId())
                                .build()
                )))
                .build();

        try {
            SendMessage send = SendMessage.builder()
                    .chatId(props.getGroupChatId())
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .replyToMessageId(ticket.getRootGroupMsgId() != null
                            ? ticket.getRootGroupMsgId().intValue() : null)
                    .build();
            telegramClient.execute(send);
        } catch (TelegramApiException e) {
            log.error("Failed to notify group of ticket #{} close", ticket.getId(), e);
        }
    }

    private void notifyPlayerResolved(SupportTicket ticket) {
        try {
            SendMessage send = SendMessage.builder()
                    .chatId(ticket.getPlayer().getTelegramId())
                    .text("✅ Обращение #" + ticket.getId() + " закрыто командой поддержки.\n\n"
                            + "Если появятся вопросы — пиши /support")
                    .build();
            telegramClient.execute(send);
        } catch (TelegramApiException e) {
            log.error("Failed to notify player for resolved ticket #{}", ticket.getId(), e);
        }
    }

    private void autoClaimIfNeeded(SupportTicket ticket, SupportStaff staff) {
        if (ticket.getClaimedBy() == null) {
            ticket.setClaimedBy(staff);
            ticket.setStatus(TicketStatus.IN_PROGRESS);
            ticket.setUpdatedAt(LocalDateTime.now());
            ticketRepository.save(ticket);
        }
    }

    /**
     * Checks if the given staff member can act on the ticket
     * (is the claimant, or is SUPER_ADMIN, or ticket is unclaimed).
     */
    private boolean canAct(SupportStaff staff, SupportTicket ticket) {
        if (staff.getRole() == SupportRole.SUPER_ADMIN) return true;
        if (ticket.getClaimedBy() == null) return true;
        return ticket.getClaimedBy().getId().equals(staff.getId());
    }

    // ── Attachment detection ──────────────────────────────────────────────

    private AttachmentType detectAttachmentType(Message message) {
        if (message.hasPhoto())     return AttachmentType.PHOTO;
        if (message.hasDocument())  return AttachmentType.DOCUMENT;
        if (message.hasVideo())     return AttachmentType.VIDEO;
        if (message.hasAudio())     return AttachmentType.AUDIO;
        if (message.hasVoice())     return AttachmentType.VOICE;
        if (message.hasSticker())   return AttachmentType.STICKER;
        if (message.hasAnimation()) return AttachmentType.ANIMATION;
        if (message.hasVideoNote()) return AttachmentType.VIDEO_NOTE;
        return null;
    }

    private String extractFileId(Message message) {
        if (message.hasPhoto()) {
            var photos = message.getPhoto(); // List<PhotoSize>
            return photos.isEmpty() ? null : photos.get(photos.size() - 1).getFileId();
        }
        if (message.hasDocument())  return message.getDocument().getFileId();
        if (message.hasVideo())     return message.getVideo().getFileId();
        if (message.hasAudio())     return message.getAudio().getFileId();
        if (message.hasVoice())     return message.getVoice().getFileId();
        if (message.hasSticker())   return message.getSticker().getFileId();
        if (message.hasAnimation()) return message.getAnimation().getFileId();
        if (message.hasVideoNote()) return message.getVideoNote().getFileId();
        return null;
    }

    // ── Formatting helpers ────────────────────────────────────────────────

    private String displayName(Player player) {
        // Player entity only stores username; use it as display name, fall back to ID
        return player.getUsername() != null
                ? "@" + player.getUsername()
                : "Игрок#" + player.getId();
    }

    private String staffDisplayName(SupportStaff staff) {
        if (staff.getUsername() != null) return "@" + staff.getUsername();
        if (staff.getFirstName() != null) return staff.getFirstName();
        return "staff#" + staff.getId();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;");
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + " сек";
        if (seconds < 3600) return (seconds / 60) + " мин";
        return (seconds / 3600) + " ч " + ((seconds % 3600) / 60) + " мин";
    }
}

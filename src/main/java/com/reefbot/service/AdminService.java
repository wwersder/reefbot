package com.reefbot.service;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.TideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Обработчик admin-команд (только для Telegram ID из MessageDispatcher.ADMIN_TELEGRAM_ID).
 *
 * <p>Все команды используют внутренний DB player ID (не Telegram ID).
 * <pre>
 *   /del  &lt;playerId&gt;
 *   /tide &lt;playerId&gt;
 *   /give &lt;playerId&gt; &lt;ресурс&gt; &lt;количество&gt;
 *
 *   Ресурсы для /give: fish, shells, wood, stone, coral, devpoints (или dp), xp
 *   Количество может быть отрицательным (списать).
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final PlayerService playerService;
    private final TideService tideService;
    private final PlayerRepository playerRepository;
    private final IslandRepository islandRepository;

    @Transactional
    public BotResponse handle(String text) {

        if (text.startsWith("/del ")) {
            return handleDel(text.substring(5).trim());
        }

        if (text.startsWith("/tide ")) {
            return handleTide(text.substring(6).trim());
        }

        if (text.startsWith("/give ")) {
            return handleGive(text.substring(6).trim());
        }

        return null;
    }

    // ── /del <playerId> ───────────────────────────────────────────────────

    private BotResponse handleDel(String arg) {
        long playerId;
        try {
            playerId = Long.parseLong(arg);
        } catch (NumberFormatException e) {
            return new BotResponse("Неверный формат. Используй: /del <playerId>");
        }

        boolean deleted = playerService.deletePlayer(playerId);
        return new BotResponse(deleted
            ? "✅ Игрок #" + playerId + " удалён."
            : "❌ Игрок #" + playerId + " не найден.");
    }

    // ── /tide <playerId> ──────────────────────────────────────────────────

    private BotResponse handleTide(String arg) {
        long playerId;
        try {
            playerId = Long.parseLong(arg);
        } catch (NumberFormatException e) {
            return new BotResponse("Неверный формат. Используй: /tide <playerId>");
        }

        Optional<Player> playerOpt = playerRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return new BotResponse("Игрок #" + playerId + " не найден.");
        }

        tideService.forceTide(playerOpt.get());
        return new BotResponse("🌊 Прилив активирован для игрока #" + playerId + ".");
    }

    // ── /give <playerId> <resource> <amount> ──────────────────────────────

    private BotResponse handleGive(String args) {
        String[] parts = args.split("\\s+", 3);
        if (parts.length != 3) {
            return new BotResponse(
                "Неверный формат. Используй:\n/give <playerId> <ресурс> <количество>\n\n"
                + "Ресурсы: fish, shells, wood, stone, coral, devpoints (dp), xp"
            );
        }

        long playerId;
        int amount;
        try {
            playerId = Long.parseLong(parts[0]);
            amount   = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return new BotResponse("playerId и количество должны быть числами.");
        }

        String resource = parts[1].toLowerCase();

        Optional<Player> playerOpt = playerRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return new BotResponse("Игрок #" + playerId + " не найден.");
        }

        Player player = playerOpt.get();

        // XP — на игроке, не на острове
        if (resource.equals("xp")) {
            int before = player.getFishing().getFishingXp();
            player.getFishing().setFishingXp(Math.max(0, before + amount));
            playerRepository.save(player);
            return new BotResponse(String.format(
                "✅ xp: %+d → было %d, стало %d (игрок #%d)",
                amount, before, player.getFishing().getFishingXp(), playerId
            ));
        }

        // Всё остальное — на острове
        Island island = islandRepository.findByPlayer(player).orElse(null);
        if (island == null) {
            return new BotResponse("У игрока #" + playerId + " нет острова (онбординг не завершён).");
        }

        int before;
        int after;
        switch (resource) {
            case "fish" -> {
                before = island.getFish();
                island.setFish(Math.max(0, before + amount));
                after = island.getFish();
            }
            case "shells" -> {
                before = island.getShells();
                island.setShells(Math.max(0, before + amount));
                after = island.getShells();
            }
            case "wood" -> {
                before = island.getWood();
                island.setWood(Math.max(0, before + amount));
                after = island.getWood();
            }
            case "stone" -> {
                before = island.getStone();
                island.setStone(Math.max(0, before + amount));
                after = island.getStone();
            }
            case "coral" -> {
                before = island.getCoral();
                island.setCoral(Math.max(0, before + amount));
                after = island.getCoral();
            }
            case "devpoints", "dp" -> {
                before = island.getDevPoints();
                island.setDevPoints(Math.max(0, before + amount));
                after = island.getDevPoints();
            }
            default -> {
                return new BotResponse(
                    "Неизвестный ресурс: " + resource + "\n"
                    + "Доступные: fish, shells, wood, stone, coral, devpoints (dp), xp"
                );
            }
        }

        islandRepository.save(island);
        log.info("Admin give: player#{} {} {:+d} ({} → {})", playerId, resource, amount, before, after);

        return new BotResponse(String.format(
            "✅ %s: %+d → было %d, стало %d (игрок #%d, остров: %s)",
            resource, amount, before, after, playerId, island.getName()
        ));
    }
}

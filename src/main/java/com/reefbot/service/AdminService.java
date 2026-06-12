package com.reefbot.service;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.entity.Player;
import com.reefbot.repository.IslandBuildingRepository;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.TideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Обработчик admin-команд (только для Telegram ID из MessageDispatcher.ADMIN_TELEGRAM_ID).
 *
 * <p>Все команды используют внутренний DB player ID (не Telegram ID).
 * <pre>
 *   /del     &lt;playerId&gt;
 *   /tide    &lt;playerId&gt;
 *   /give    &lt;playerId&gt; &lt;ресурс&gt; &lt;количество&gt;
 *   /speedup   &lt;playerId&gt; [buildingType]          — оставить 10 сек до конца стройки
 *   /buildings &lt;playerId&gt;                        — статус всех зданий игрока
 *   /produce   &lt;playerId&gt; &lt;BUILDING_TYPE&gt; &lt;кол&gt; — накинуть N единиц в копилку здания
 *
 *   Ресурсы для /give: fish, shells, wood, stone, coral, devpoints (или dp), xp
 *   Количество может быть отрицательным (списать).
 *   buildingType для /speedup: FISHING_PIER (или не указывать — ускорит все).
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private static final int SPEEDUP_SECONDS = 10;

    private final PlayerService playerService;
    private final TideService tideService;
    private final PlayerRepository playerRepository;
    private final IslandRepository islandRepository;
    private final IslandBuildingRepository buildingRepository;

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

        if (text.startsWith("/speedup ")) {
            return handleSpeedup(text.substring(9).trim());
        }

        if (text.startsWith("/buildings ")) {
            return handleBuildings(text.substring(11).trim());
        }

        if (text.startsWith("/produce ")) {
            return handleProduce(text.substring(9).trim());
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
            int after  = before + amount;
            player.getFishing().setFishingXp(after);
            playerRepository.save(player);
            return new BotResponse(String.format(
                "✅ xp: %+d → было %d, стало %d (игрок #%d)",
                amount, before, after, playerId
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
                after  = before + amount;
                island.setFish(after);
            }
            case "shells" -> {
                before = island.getShells();
                after  = before + amount;
                island.setShells(after);
            }
            case "wood" -> {
                before = island.getWood();
                after  = before + amount;
                island.setWood(after);
            }
            case "stone" -> {
                before = island.getStone();
                after  = before + amount;
                island.setStone(after);
            }
            case "coral" -> {
                before = island.getCoral();
                after  = before + amount;
                island.setCoral(after);
            }
            case "devpoints", "dp" -> {
                before = island.getDevPoints();
                after  = before + amount;
                island.setDevPoints(after);
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

    // ── /speedup <playerId> [BUILDING_TYPE] ───────────────────────────────

    /**
     * Оставляет SPEEDUP_SECONDS секунд до конца каждой активной стройки.
     * Если buildingType не указан — ускоряет все активные стройки игрока.
     * Уже завершённые (buildFinishAt в прошлом) пропускает — пусть сам заберёт.
     */
    private BotResponse handleSpeedup(String args) {
        String[] parts = args.split("\\s+", 2);

        long playerId;
        try {
            playerId = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return new BotResponse("Неверный формат. Используй: /speedup <playerId> [BUILDING_TYPE]");
        }

        Optional<Player> playerOpt = playerRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return new BotResponse("Игрок #" + playerId + " не найден.");
        }

        Island island = islandRepository.findByPlayer(playerOpt.get()).orElse(null);
        if (island == null) {
            return new BotResponse("У игрока #" + playerId + " нет острова.");
        }

        // Фильтрация по типу здания (опционально)
        List<IslandBuilding> candidates = buildingRepository
                .findAllByIslandAndBuildFinishAtIsNotNull(island);

        if (parts.length == 2) {
            String typeArg = parts[1].toUpperCase();
            candidates = candidates.stream()
                    .filter(b -> b.getBuildingType().name().equals(typeArg))
                    .toList();
            if (candidates.isEmpty()) {
                return new BotResponse(
                    "У игрока #" + playerId + " нет активной стройки типа " + typeArg + ".\n"
                    + "Доступные типы: FISHING_PIER"
                );
            }
        }

        // Разделяем на «уже готовые» и «ещё идут»
        LocalDateTime now = LocalDateTime.now();
        List<IslandBuilding> active  = candidates.stream()
                .filter(b -> b.getBuildFinishAt().isAfter(now)).toList();
        List<IslandBuilding> already = candidates.stream()
                .filter(b -> !b.getBuildFinishAt().isAfter(now)).toList();

        if (active.isEmpty() && already.isEmpty()) {
            return new BotResponse("У игрока #" + playerId + " нет активных строек.");
        }

        // Ускоряем только те, что ещё не завершились
        LocalDateTime target = now.plusSeconds(SPEEDUP_SECONDS);
        for (IslandBuilding b : active) {
            b.setBuildFinishAt(target);
        }
        buildingRepository.saveAll(active);

        // Формируем ответ
        StringBuilder sb = new StringBuilder();
        sb.append("⚡ Speedup игрока #").append(playerId)
          .append(" (остров: ").append(island.getName()).append(")\n\n");

        for (IslandBuilding b : active) {
            int targetLvl = b.getLevel() + 1;
            sb.append("🔨 ").append(b.getBuildingType().nameAt(targetLvl))
              .append(" (ур.").append(targetLvl).append(")")
              .append(" — ").append(SPEEDUP_SECONDS).append(" сек\n");
        }

        if (!already.isEmpty()) {
            sb.append("\nУже завершены (ждут сбора):\n");
            for (IslandBuilding b : already) {
                int targetLvl = b.getLevel() + 1;
                sb.append("✅ ").append(b.getBuildingType().nameAt(targetLvl))
                  .append(" (ур.").append(targetLvl).append(")\n");
            }
        }

        if (active.isEmpty()) {
            sb.append("Нечего ускорять — все стройки уже завершены.");
        }

        log.info("Admin speedup: player#{} — {} buildings sped up", playerId, active.size());
        return new BotResponse(sb.toString().trim());
    }

    // ── /buildings <playerId> ─────────────────────────────────────────────

    private BotResponse handleBuildings(String arg) {
        long playerId;
        try {
            playerId = Long.parseLong(arg);
        } catch (NumberFormatException e) {
            return new BotResponse("Неверный формат. Используй: /buildings <playerId>");
        }

        Optional<Player> playerOpt = playerRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return new BotResponse("Игрок #" + playerId + " не найден.");
        }

        Island island = islandRepository.findByPlayer(playerOpt.get()).orElse(null);
        if (island == null) {
            return new BotResponse("У игрока #" + playerId + " нет острова.");
        }

        List<IslandBuilding> all = buildingRepository.findAllByIsland(island);
        if (all.isEmpty()) {
            return new BotResponse("У игрока #" + playerId + " нет зданий (ни одно не строилось).");
        }

        LocalDateTime now = LocalDateTime.now();
        StringBuilder sb = new StringBuilder();
        sb.append("🏗 Здания игрока #").append(playerId)
          .append(" (").append(island.getName()).append(")\n\n");

        for (IslandBuilding b : all) {
            int lvl = b.getLevel();
            sb.append("• ").append(b.getBuildingType().name()).append("\n");

            if (b.getBuildFinishAt() != null) {
                int targetLvl = lvl + 1;
                if (b.getBuildFinishAt().isAfter(now)) {
                    // Строится
                    long totalSec = java.time.Duration.between(now, b.getBuildFinishAt()).getSeconds();
                    long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
                    String remaining = h > 0 ? h + "ч " + m + "м " + s + "с"
                                     : m > 0 ? m + "м " + s + "с"
                                     : s + "с";
                    sb.append("  ⏳ строится до ур.").append(targetLvl)
                      .append(", осталось: ").append(remaining).append("\n");
                } else {
                    // Готово, не забрано
                    sb.append("  ✅ ур.").append(targetLvl)
                      .append(" готово, ждёт финализации\n");
                }
            } else if (lvl > 0) {
                // Работает
                sb.append("  ✅ ур.").append(lvl).append(" — работает\n");
            } else {
                sb.append("  ❓ уровень 0, buildFinishAt=null (некорректное состояние)\n");
            }
        }

        return new BotResponse(sb.toString().trim());
    }

    // ── /produce <playerId> <BUILDING_TYPE> <amount> ──────────────────────

    /**
     * Откатывает productionCollectedAt назад так, чтобы calcAccumulated() вернул
     * ровно {@code amount} (или cap, если amount его превышает).
     *
     * Формула: collectedAt = now - (amount / prodPerHour) * 3600 сек.
     */
    private BotResponse handleProduce(String args) {
        String[] parts = args.split("\\s+", 3);
        if (parts.length != 3) {
            return new BotResponse(
                "Неверный формат. Используй:\n/produce <playerId> <BUILDING_TYPE> <количество>\n\n"
                + "Пример: /produce 3 FISHING_PIER 5"
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

        if (amount < 0) {
            return new BotResponse("Количество должно быть >= 0.");
        }

        String typeArg = parts[1].toUpperCase();
        com.reefbot.enums.BuildingType buildingType;
        try {
            buildingType = com.reefbot.enums.BuildingType.valueOf(typeArg);
        } catch (IllegalArgumentException e) {
            return new BotResponse("Неизвестный тип здания: " + typeArg + "\nДоступные: FISHING_PIER");
        }

        Optional<Player> playerOpt = playerRepository.findById(playerId);
        if (playerOpt.isEmpty()) {
            return new BotResponse("Игрок #" + playerId + " не найден.");
        }

        Island island = islandRepository.findByPlayer(playerOpt.get()).orElse(null);
        if (island == null) {
            return new BotResponse("У игрока #" + playerId + " нет острова.");
        }

        IslandBuilding building = buildingRepository
                .findByIslandAndBuildingType(island, buildingType)
                .orElse(null);

        if (building == null || building.getLevel() == 0) {
            return new BotResponse("Здание " + typeArg + " у игрока #" + playerId + " ещё не построено.");
        }
        if (building.getBuildFinishAt() != null) {
            return new BotResponse("Здание " + typeArg + " сейчас в процессе стройки/апгрейда.\nДождись завершения.");
        }

        int lvl        = building.getLevel();
        int prodPerHour = buildingType.productionPerHourAt(lvl);
        int cap         = buildingType.capAt(lvl);

        // Капаем по потолку и предупреждаем
        String capWarning = "";
        if (amount > cap) {
            capWarning = "\n⚠️ Запрошено " + amount + ", но потолок " + cap + " — выставлено " + cap + ".";
            amount = cap;
        }

        // collectedAt = now - (amount / prodPerHour) * 3600 сек
        // Используем double чтобы дроби не терялись
        long secondsBack = Math.round((double) amount / prodPerHour * 3600);
        building.setProductionCollectedAt(LocalDateTime.now().minusSeconds(secondsBack));
        buildingRepository.save(building);

        log.info("Admin produce: player#{} {} +{} (set collectedAt -{} sec)",
                playerId, typeArg, amount, secondsBack);

        return new BotResponse(String.format(
            "✅ %s игрока #%d: в копилке теперь %d %s%s",
            buildingType.nameAt(lvl), playerId, amount,
            buildingType == com.reefbot.enums.BuildingType.FISHING_PIER ? "🐟" : "ед.",
            capWarning
        ));
    }
}

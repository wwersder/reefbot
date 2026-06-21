package com.reefbot.service;

import com.reefbot.bot.handlers.AdminCallbackHandler;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.InventoryItem;
import com.reefbot.entity.Island;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.entity.Player;
import com.reefbot.enums.ConsumableItem;
import com.reefbot.enums.VipTier;
import com.reefbot.repository.InventoryRepository;
import com.reefbot.repository.IslandBuildingRepository;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.FishingService;
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
    private final FishingService fishingService;
    private final TideService tideService;
    private final PlayerRepository playerRepository;
    private final IslandRepository islandRepository;
    private final IslandBuildingRepository buildingRepository;
    private final InventoryRepository inventoryRepository;

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

        if (text.startsWith("/vip ") || text.equals("/vip")) {
            return handleVip(text.length() > 5 ? text.substring(5).trim() : "");
        }

        if (text.equals("/admin") || text.equals("/admin help")) {
            return handleAdminHelp();
        }

        return null;
    }

    // ── /vip — управление VIP ────────────────────────────────────────────
    //
    //  /vip <id>                — показать VIP-профиль игрока
    //  /vip tier <id> <TIER>    — установить тир (NONE/CORAL/PEARL/REEF)
    //  /vip wager <id> <delta>  — прибавить/убавить оборот (может быть <0)
    //  /vip reset <id>          — обнулить период (чистый минус → 0, дата → сегодня)
    //  /vip cashback <id>       — принудительно выплатить кешбэк сейчас
    // ─────────────────────────────────────────────────────────────────────

    private BotResponse handleVip(String args) {
        if (args.isEmpty()) {
            return new BotResponse("""
                    Используй:
                    /vip <id>               — VIP-профиль
                    /vip tier <id> <TIER>   — установить тир
                    /vip wager <id> <delta> — изменить оборот
                    /vip reset <id>         — обнулить период
                    /vip cashback <id>      — выплатить кешбэк сейчас
                    /vip nuke <id>          — снести всё в ноль""");
        }

        String[] parts = args.split("\\s+");
        String sub = parts[0].toLowerCase();

        return switch (sub) {
            case "tier"     -> handleVipTier(parts);
            case "wager"    -> handleVipWager(parts);
            case "reset"    -> handleVipReset(parts);
            case "cashback" -> handleVipCashback(parts);
            case "nuke"     -> handleVipNuke(parts);
            default         -> handleVipInfo(parts);  // /vip <id>
        };
    }

    /** /vip <id> — показать VIP профиль. */
    private BotResponse handleVipInfo(String[] parts) {
        Player player = resolvePlayer(parts[0]);
        if (player == null) return new BotResponse("Игрок #" + parts[0] + " не найден.");

        VipTier tier     = safe(player.getVipTier());
        long wager       = player.getVipLifetimeWager() != null ? player.getVipLifetimeWager() : 0L;
        int periodLoss   = player.getVipPeriodNetLoss()  != null ? player.getVipPeriodNetLoss()  : 0;
        int cashback     = (int) Math.floor(Math.max(0, periodLoss) * tier.getCashbackRate());
        String paidAt    = player.getVipCashbackPaidAt() != null
                ? player.getVipCashbackPaidAt().toString() : "никогда";
        String periodStart = player.getVipPeriodStart() != null
                ? player.getVipPeriodStart().toString() : "—";

        VipTier next = tier.next();
        String nextInfo = next != null
                ? String.format("До %s: %,d 🐚 осталось",
                        next.label(), Math.max(0, next.getWageredThreshold() - wager))
                : "Максимальный статус 🏆";

        return new BotResponse(String.format("""
                ✨ VIP профиль — игрок #%d (@%s)

                Статус:      %s (%d%% кешбэк)
                Оборот:      %,d 🐚
                %s

                Период с:    %s
                Чистый минус: %,d 🐚
                Кешбэк ~:    %,d 🐚

                Последняя выплата: %s""",
                player.getId(),
                player.getUsername() != null ? player.getUsername() : "без юзернейма",
                tier.label(), (int)(tier.getCashbackRate() * 100),
                wager,
                nextInfo,
                periodStart,
                Math.max(0, periodLoss),
                cashback,
                paidAt));
    }

    /** /vip tier <id> <TIER> — установить тир. */
    private BotResponse handleVipTier(String[] parts) {
        if (parts.length < 3)
            return new BotResponse("Формат: /vip tier <id> <TIER>\nТиры: NONE, CORAL, PEARL, REEF");

        Player player = resolvePlayer(parts[1]);
        if (player == null) return new BotResponse("Игрок #" + parts[1] + " не найден.");

        VipTier newTier;
        try {
            newTier = VipTier.valueOf(parts[2].toUpperCase());
        } catch (IllegalArgumentException e) {
            return new BotResponse("Неизвестный тир: " + parts[2] + "\nДоступные: NONE, CORAL, PEARL, REEF");
        }

        VipTier old = safe(player.getVipTier());
        player.setVipTier(newTier);
        playerRepository.save(player);
        log.info("Admin vip tier: player#{} {} → {}", player.getId(), old, newTier);

        return new BotResponse(String.format("✅ Тир игрока #%d: %s → %s",
                player.getId(), old.label(), newTier.label()));
    }

    /** /vip wager <id> <delta> — изменить пожизненный оборот. */
    private BotResponse handleVipWager(String[] parts) {
        if (parts.length < 3)
            return new BotResponse("Формат: /vip wager <id> <delta>\nДельта может быть отрицательной.");

        Player player = resolvePlayer(parts[1]);
        if (player == null) return new BotResponse("Игрок #" + parts[1] + " не найден.");

        long delta;
        try { delta = Long.parseLong(parts[2]); }
        catch (NumberFormatException e) { return new BotResponse("delta должна быть числом."); }

        long before = player.getVipLifetimeWager() != null ? player.getVipLifetimeWager() : 0L;
        long after  = Math.max(0, before + delta);
        player.setVipLifetimeWager(after);

        // Update tier based on new wager (never downgrade below current tier)
        VipTier earned = VipTier.forWager(after);
        VipTier current = safe(player.getVipTier());
        if (earned.ordinal() > current.ordinal()) {
            player.setVipTier(earned);
        }
        playerRepository.save(player);
        log.info("Admin vip wager: player#{} {} → {} (delta {})", player.getId(), before, after, delta);

        return new BotResponse(String.format("✅ Оборот игрока #%d: %,d → %,d 🐚 (тир: %s)",
                player.getId(), before, after, safe(player.getVipTier()).label()));
    }

    /** /vip reset <id> — обнулить период кешбэка. */
    private BotResponse handleVipReset(String[] parts) {
        if (parts.length < 2)
            return new BotResponse("Формат: /vip reset <id>");

        Player player = resolvePlayer(parts[1]);
        if (player == null) return new BotResponse("Игрок #" + parts[1] + " не найден.");

        int before = player.getVipPeriodNetLoss() != null ? player.getVipPeriodNetLoss() : 0;
        player.setVipPeriodNetLoss(0);
        player.setVipPeriodStart(java.time.LocalDate.now());
        playerRepository.save(player);
        log.info("Admin vip reset: player#{} period cleared (was {})", player.getId(), before);

        return new BotResponse(String.format(
                "✅ Период кешбэка игрока #%d обнулён.\n"
                + "Чистый минус был: %,d 🐚 → 0",
                player.getId(), Math.max(0, before)));
    }

    /** /vip cashback <id> — принудительно выплатить кешбэк. */
    private BotResponse handleVipCashback(String[] parts) {
        if (parts.length < 2)
            return new BotResponse("Формат: /vip cashback <id>");

        Player player = resolvePlayer(parts[1]);
        if (player == null) return new BotResponse("Игрок #" + parts[1] + " не найден.");

        VipTier tier = safe(player.getVipTier());
        if (tier == VipTier.NONE) {
            return new BotResponse("Игрок #" + parts[1] + " не имеет VIP статуса — кешбэк не начисляется.");
        }

        int loss = player.getVipPeriodNetLoss() != null ? player.getVipPeriodNetLoss() : 0;
        if (loss <= 0) {
            return new BotResponse(String.format(
                    "Игрок #%d не в минусе в текущем периоде (чистый минус: %,d 🐚).\n"
                    + "Кешбэк не выплачивается.", player.getId(), loss));
        }

        int cashback = (int) Math.floor(loss * tier.getCashbackRate());
        Island island = islandRepository.findByPlayer(player).orElse(null);
        if (island == null) {
            return new BotResponse("У игрока #" + parts[1] + " нет острова.");
        }

        island.setShells(island.getShells() + cashback);
        islandRepository.save(island);

        player.setVipPeriodNetLoss(0);
        player.setVipPeriodStart(java.time.LocalDate.now());
        player.setVipCashbackPaidAt(java.time.LocalDateTime.now());
        playerRepository.save(player);

        log.info("Admin vip cashback: player#{} +{} shells (period loss={})", player.getId(), cashback, loss);

        return new BotResponse(String.format(
                "✅ Кешбэк выплачен игроку #%d\n"
                + "Тир: %s (%d%%)\n"
                + "Чистый минус: %,d 🐚\n"
                + "Выплачено: +%,d 🐚\n"
                + "Период обнулён.",
                player.getId(), tier.label(), (int)(tier.getCashbackRate() * 100), loss, cashback));
    }

    /** /vip nuke <id> — полный сброс VIP профиля в ноль. */
    private BotResponse handleVipNuke(String[] parts) {
        if (parts.length < 2)
            return new BotResponse("Формат: /vip nuke <id>");

        Player player = resolvePlayer(parts[1]);
        if (player == null) return new BotResponse("Игрок #" + parts[1] + " не найден.");

        VipTier oldTier  = safe(player.getVipTier());
        long oldWager    = player.getVipLifetimeWager()  != null ? player.getVipLifetimeWager()  : 0L;
        int oldLoss      = player.getVipPeriodNetLoss()  != null ? player.getVipPeriodNetLoss()  : 0;

        player.setVipTier(VipTier.NONE);
        player.setVipLifetimeWager(0L);
        player.setVipPeriodNetLoss(0);
        player.setVipPeriodStart(null);
        player.setVipCashbackPaidAt(null);
        playerRepository.save(player);

        log.info("Admin vip nuke: player#{} — tier={} wager={} loss={} → all zeroed",
                player.getId(), oldTier, oldWager, oldLoss);

        return new BotResponse(String.format("""
                💥 VIP профиль игрока #%d полностью обнулён.

                Было:
                  Тир:    %s
                  Оборот: %,d 🐚
                  Минус:  %,d 🐚

                Стало: NONE / 0 / 0""",
                player.getId(), oldTier.label(), oldWager, Math.max(0, oldLoss)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Player resolvePlayer(String idStr) {
        try {
            return playerRepository.findById(Long.parseLong(idStr)).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private VipTier safe(VipTier tier) {
        return tier != null ? tier : VipTier.NONE;
    }

    // ── /admin — справка ──────────────────────────────────────────────────

    private BotResponse handleAdminHelp() {
        return BotResponse.html(AdminCallbackHandler.textMenu(), AdminCallbackHandler.menuKeyboard());
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
                + "Ресурсы: fish, shells, wood, stone, coral, devpoints (dp), xp\n"
                + "Предметы: scroll, bait, hook, vial"
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

        // XP — на игроке, не на острове; уровень пересчитывается автоматически
        if (resource.equals("xp")) {
            int before    = player.getFishing().getFishingXp();
            int levelWas  = player.getFishing().getFishingLevel();
            int after     = before + amount;
            int levelNow  = fishingService.levelForXp(Math.max(0, after));
            player.getFishing().setFishingXp(after);
            player.getFishing().setFishingLevel(levelNow);
            playerRepository.save(player);
            String levelInfo = levelNow != levelWas
                ? " | уровень: " + levelWas + " → " + levelNow
                : "";
            return new BotResponse(String.format(
                "✅ xp: %+d → было %d, стало %d (игрок #%d)%s",
                amount, before, after, playerId, levelInfo
            ));
        }

        // Предметы инвентаря — scroll, bait, hook, vial
        ConsumableItem consumable = switch (resource) {
            case "scroll" -> ConsumableItem.SPEED_SCROLL;
            case "bait"   -> ConsumableItem.BAIT;
            case "hook"   -> ConsumableItem.FISHING_HOOK;
            case "vial"   -> ConsumableItem.TIDE_VIAL;
            default       -> null;
        };
        if (consumable != null) {
            return giveItem(player, consumable, amount);
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
                    + "Доступные: fish, shells, wood, stone, coral, devpoints (dp), xp\n"
                    + "Предметы: scroll, bait, hook, vial"
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

    // ── Inventory item give/take ───────────────────────────────────────────

    private BotResponse giveItem(Player player, ConsumableItem item, int amount) {
        var existing = inventoryRepository.findByPlayerAndItemTypeAndItemKey(
                player, ConsumableItem.ITEM_TYPE, item.name());

        int before = existing.map(InventoryItem::getQuantity).orElse(0);
        int after  = before + amount;

        if (after <= 0) {
            // Удаляем запись если количество <= 0
            existing.ifPresent(inventoryRepository::delete);
            after = 0;
        } else if (existing.isPresent()) {
            existing.get().setQuantity(after);
            inventoryRepository.save(existing.get());
        } else {
            inventoryRepository.save(InventoryItem.builder()
                    .player(player)
                    .itemType(ConsumableItem.ITEM_TYPE)
                    .itemKey(item.name())
                    .quantity(after)
                    .build());
        }

        log.info("Admin give item: player#{} {} {:+d} ({} → {})", player.getId(), item.name(), amount, before, after);
        return new BotResponse(String.format(
            "✅ %s: %+d → было %d, стало %d (игрок #%d)",
            item.name().toLowerCase(), amount, before, after, player.getId()
        ));
    }
}

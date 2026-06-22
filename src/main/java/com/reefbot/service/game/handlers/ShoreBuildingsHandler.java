package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.entity.Player;
import com.reefbot.enums.BuildingType;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.service.game.BuildingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.TideService;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.util.Fmt;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Хаб строительства — показывает что можно построить и что строится.
 * Скрин: ZONE_SHORE_BUILDINGS.
 *
 * <p>Управление готовыми зданиями (сбор, апгрейд) — в отдельных
 * хэндлерах: {@link ShorePierHandler} и будущих аналогах.
 */
@Component
@RequiredArgsConstructor
public class ShoreBuildingsHandler implements GameHandler {

    public static final String BTN_BUILD = "🔨 Начать строительство";
    public static final String BTN_BACK  = "◀️ На берег";

    private final BuildingService buildingService;
    private final TideService tideService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE_BUILDINGS;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        Optional<IslandBuilding> pierOpt = buildingService.find(island, BuildingType.FISHING_PIER);
        IslandBuilding pier = pierOpt.orElse(null);

        // Если помост уже построен и работает — сюда не зайти через UI,
        // но на всякий случай редиректим в экран помоста
        if (pier != null && pier.getLevel() > 0 && pier.getBuildFinishAt() == null) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE_PIER);
            playerRepository.save(player);
            return ShorePierHandler.buildPierScreen(island, pier);
        }

        return switch (text) {
            case BTN_BUILD -> tryBuild(player, island, pier);
            case BTN_BACK  -> goBack(player, island, pier);
            default        -> buildConstructionScreen(island, pier);
        };
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private BotResponse tryBuild(Player player, Island island, IslandBuilding pier) {
        if (pier != null && (buildingService.isUnderConstruction(pier) || pier.getLevel() > 0)) {
            return buildConstructionScreen(island, pier);
        }

        if (!buildingService.canAfford(island, BuildingType.FISHING_PIER, 1)) {
            RichText rt = new RichText();
            rt.bold("❌ Не хватает ресурсов").add("\n\n")
              .add("Нужно: ").bold(ShorePierHandler.costsText(BuildingType.FISHING_PIER, 1)).add("\n")
              .add("Есть:  ").bold(Fmt.n(island.getFish()) + " 🐟  "
                  + Fmt.n(island.getShells()) + " 🐚  "
                  + Fmt.n(island.getWood()) + " 🪵");
            return rt.build().withFollowUp(buildConstructionScreen(island, pier));
        }

        IslandBuilding updated = buildingService.startBuild(island, BuildingType.FISHING_PIER);
        return buildConstructionScreen(island, updated);
    }

    private BotResponse goBack(Player player, Island island, IslandBuilding pier) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return ShoreZoneHandler.buildZoneScreen(player, tideService, pier);
    }

    // ── Static screen builder ──────────────────────────────────────────────

    public static BotResponse buildConstructionScreen(Island island, IslandBuilding pier) {
        RichText rt = new RichText();
        rt.bold("🏗 Стройка — Берег").add("\n\n");

        appendPierEntry(rt, island, pier);

        return rt.build(keyboard(island, pier));
    }

    private static void appendPierEntry(RichText rt, Island island, IslandBuilding pier) {
        if (pier == null) {
            // Не начато
            rt.bold("🎣 Рыбацкий помост").add("\n")
              .add("Пассивный доход рыбы прямо с воды.\n\n")
              .add("Уровень 1  ·  +5 🐟/ч  ·  потолок 8 ч\n")
              .add("Стоимость: ").bold(ShorePierHandler.costsText(BuildingType.FISHING_PIER, 1)).add("\n")
              .add("Время: ").bold(ShorePierHandler.minutesToText(BuildingType.FISHING_PIER.buildMinutesFor(1)));

        } else if (pier.getLevel() == 0) {
            // Строится (ещё не достиг уровня 1)
            boolean ready = pier.getBuildFinishAt() == null
                    || !LocalDateTime.now().isBefore(pier.getBuildFinishAt());

            if (ready) {
                rt.bold("🎣 Рыбацкий помост").add("\n")
                  .add("✅ Строительство завершено — открой помост, чтобы начать работу.");
            } else {
                rt.bold("🎣 Рыбацкий помост").add("\n")
                  .add("⏳ Строится...\n")
                  .add("Готово через: ").bold(remainingText(pier.getBuildFinishAt()));
            }

        } else {
            // pier.level > 0 with buildFinishAt set — upgrade in progress (redirect in handle() only
            // fires when buildFinishAt is null, so this branch is reachable during active upgrade)
            int nextLevel = pier.getLevel() + 1;
            boolean ready = pier.getBuildFinishAt() == null
                    || !LocalDateTime.now().isBefore(pier.getBuildFinishAt());
            if (ready) {
                rt.bold("🎣 " + BuildingType.FISHING_PIER.nameAt(pier.getLevel())).add("\n")
                  .add("✅ Улучшение завершено — зайди на помост, чтобы применить.");
            } else {
                rt.bold("🎣 " + BuildingType.FISHING_PIER.nameAt(nextLevel)).add("\n")
                  .add("⏳ Улучшается до уровня " + nextLevel + "...\n")
                  .add("Готово через: ").bold(remainingText(pier.getBuildFinishAt()));
            }
        }
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    private static ReplyKeyboard keyboard(Island island, IslandBuilding pier) {
        KeyboardBuilder kb = KeyboardBuilder.builder();

        boolean canBuild = pier == null;
        boolean isBuilding = pier != null && pier.getLevel() == 0
                && pier.getBuildFinishAt() != null
                && LocalDateTime.now().isBefore(pier.getBuildFinishAt());

        if (canBuild) {
            KeyboardButton buildBtn = new KeyboardButton(BTN_BUILD);
            // Include wood in canAfford check — level 3+ requires wood
            boolean canAfford = island.getFish()   >= BuildingType.FISHING_PIER.fishCostFor(1)
                             && island.getShells() >= BuildingType.FISHING_PIER.shellsCostFor(1)
                             && island.getWood()   >= BuildingType.FISHING_PIER.woodCostFor(1);
            if (canAfford) buildBtn.setStyle("success");
            kb.row(buildBtn);
        }
        // Если строится — кнопки нет, только текст + назад

        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }

    // ── Static helpers ─────────────────────────────────────────────────────

    private static String remainingText(LocalDateTime finishAt) {
        long totalSec = Math.max(0, Duration.between(LocalDateTime.now(), finishAt).getSeconds());
        long h = totalSec / 3600, m = (totalSec % 3600) / 60;
        if (h > 0) return h + " ч " + m + " мин";
        if (m > 0) return m + " мин";
        return totalSec + " сек";
    }
}

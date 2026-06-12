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
 * Экран «Здания — Берег»: строительство и сбор пассивного производства.
 * Скрин: ZONE_SHORE_BUILDINGS.
 *
 * <p>Статический метод {@link #buildBuildingsScreen(Island, IslandBuilding)} используется
 * ShoreZoneHandler и другими обработчиками для отображения экрана.
 */
@Component
@RequiredArgsConstructor
public class ShoreBuildingsHandler implements GameHandler {

    public static final String BTN_BUILD   = "🔨 Построить";
    public static final String BTN_UPGRADE = "⬆️ Улучшить";
    public static final String BTN_COLLECT = "📦 Собрать рыбу";
    public static final String BTN_BACK    = "◀️ На берег";

    private final BuildingService buildingService;
    private final TideService tideService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE_BUILDINGS;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        // Автофинализация при открытии экрана
        Optional<IslandBuilding> pierOpt = buildingService.find(island, BuildingType.FISHING_PIER);
        if (pierOpt.isPresent() && buildingService.isConstructionReady(pierOpt.get())) {
            pierOpt = Optional.of(buildingService.finalize(pierOpt.get()));
        }
        IslandBuilding pier = pierOpt.orElse(null);

        return switch (text) {
            case BTN_BUILD, BTN_UPGRADE -> tryBuild(player, island, pier);
            case BTN_COLLECT            -> tryCollect(player, island, pier);
            case BTN_BACK               -> goBack(player, island);
            default                     -> buildBuildingsScreen(island, pier);
        };
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private BotResponse tryBuild(Player player, Island island, IslandBuilding pier) {
        if (pier != null && buildingService.isUnderConstruction(pier)) {
            return buildBuildingsScreen(island, pier);
        }

        int currentLevel = (pier == null) ? 0 : pier.getLevel();
        int targetLevel  = currentLevel + 1;

        if (!buildingService.canAfford(island, BuildingType.FISHING_PIER, targetLevel)) {
            RichText rt = new RichText();
            rt.bold("❌ Не хватает ресурсов").add("\n\n")
              .add("Нужно: ").bold(costsText(BuildingType.FISHING_PIER, targetLevel))
              .add("\nЕсть: ").bold(island.getFish() + " 🐟  "
                  + island.getShells() + " 🐚  "
                  + island.getWood() + " 🪵");
            return rt.build().withFollowUp(buildBuildingsScreen(island, pier));
        }

        IslandBuilding updated = buildingService.startBuild(island, BuildingType.FISHING_PIER);
        return buildBuildingsScreen(island, updated);
    }

    private BotResponse tryCollect(Player player, Island island, IslandBuilding pier) {
        if (pier == null || !buildingService.isOperational(pier)) {
            return buildBuildingsScreen(island, pier);
        }
        int fish = buildingService.collectFish(island, pier);
        if (fish <= 0) {
            return buildBuildingsScreen(island, pier);
        }

        // Обновляем pier из БД после сбора (productionCollectedAt обновился)
        IslandBuilding refreshed = buildingService.find(island, BuildingType.FISHING_PIER).orElse(pier);

        RichText rt = new RichText();
        rt.bold("📦 Собрано с помоста").add("\n\n")
          .add("+").bold(fish + " 🐟").add("\n")
          .add("Всего рыбы: ").bold(String.valueOf(island.getFish())); // island mutated in-place by service
        return rt.build().withFollowUp(buildBuildingsScreen(island, refreshed));
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return ShoreZoneHandler.buildZoneScreen(player, tideService);
    }

    // ── Static screen builder (вызывается из ShoreZoneHandler тоже) ────────

    public static BotResponse buildBuildingsScreen(Island island, IslandBuilding pier) {
        RichText rt = new RichText();
        rt.bold("🏗 Здания — Берег").add("\n\n");
        appendPierBlock(rt, island, pier);
        return rt.build(keyboard(island, pier));
    }

    private static void appendPierBlock(RichText rt, Island island, IslandBuilding pier) {
        if (pier == null) {
            // Не построен
            rt.bold("🎣 " + BuildingType.FISHING_PIER.nameAt(1)).add(" — не построен\n")
              .add("Пассивный доход рыбы.\n\n")
              .add("Стоимость: ").bold(costsText(BuildingType.FISHING_PIER, 1)).add("\n")
              .add("Время: ").bold(minutesToText(BuildingType.FISHING_PIER.buildMinutesFor(1))).add("\n")
              .add("Производство: ").bold("+" + BuildingType.FISHING_PIER.productionPerHourAt(1)
                  + " 🐟/ч").add(" (потолок " + BuildingType.CAP_HOURS + " ч)");

        } else if (buildingService_isUnderConstruction(pier)) {
            // В процессе строительства / апгрейда
            int targetLevel = pier.getLevel() + 1;
            String action = pier.getLevel() == 0
                ? "Строится..."
                : "Улучшается до ур." + targetLevel + "...";
            rt.bold("🎣 " + BuildingType.FISHING_PIER.nameAt(Math.max(1, targetLevel))).add("\n")
              .add("⏳ " + action + "\n")
              .add("Готово через: ").bold(remainingText(pier.getBuildFinishAt()));

            if (pier.getLevel() > 0) {
                int acc = calcAccumulated(pier);
                int cap = BuildingType.FISHING_PIER.capAt(pier.getLevel());
                rt.add("\n\nТекущий ур." + pier.getLevel() + " работает: ")
                  .bold(acc + " / " + cap + " 🐟 накоплено");
            }

        } else {
            // Работает
            int lvl = pier.getLevel();
            int acc  = calcAccumulated(pier);
            int cap  = BuildingType.FISHING_PIER.capAt(lvl);
            int prod = BuildingType.FISHING_PIER.productionPerHourAt(lvl);

            rt.bold("🎣 " + BuildingType.FISHING_PIER.nameAt(lvl)).add(" (ур. " + lvl + ")\n")
              .add("+" + prod + " 🐟/ч  |  потолок " + BuildingType.CAP_HOURS + " ч\n\n")
              .bold("Накоплено: " + acc + " / " + cap + " 🐟");

            // Инфо про апгрейд
            int nextLvl = lvl + 1;
            rt.add("\n\n")
              .bold("Апгрейд → " + BuildingType.FISHING_PIER.nameAt(nextLvl))
                .add(" (ур." + nextLvl + ")\n")
              .add("Стоимость: ").bold(costsText(BuildingType.FISHING_PIER, nextLvl)).add("\n")
              .add("Время: ").bold(minutesToText(BuildingType.FISHING_PIER.buildMinutesFor(nextLvl))).add("\n")
              .add("Производство: ").bold("+" + BuildingType.FISHING_PIER.productionPerHourAt(nextLvl) + " 🐟/ч");
        }
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    private static ReplyKeyboard keyboard(Island island, IslandBuilding pier) {
        KeyboardBuilder kb = KeyboardBuilder.builder();

        if (pier == null) {
            // Не построен
            KeyboardButton buildBtn = new KeyboardButton(BTN_BUILD);
            if (canAffordStatic(island, BuildingType.FISHING_PIER, 1)) buildBtn.setStyle("success");
            kb.row(buildBtn);

        } else if (buildingService_isUnderConstruction(pier)) {
            // Строится — если уже работает на предыдущем уровне, показываем сбор
            if (pier.getLevel() > 0 && calcAccumulated(pier) > 0) {
                KeyboardButton collectBtn = new KeyboardButton(BTN_COLLECT);
                collectBtn.setStyle("success");
                kb.row(collectBtn);
            }

        } else {
            // Работает
            if (calcAccumulated(pier) > 0) {
                KeyboardButton collectBtn = new KeyboardButton(BTN_COLLECT);
                collectBtn.setStyle("success");
                kb.row(collectBtn);
            }
            int nextLvl = pier.getLevel() + 1;
            KeyboardButton upgradeBtn = new KeyboardButton(BTN_UPGRADE);
            if (canAffordStatic(island, BuildingType.FISHING_PIER, nextLvl)) upgradeBtn.setStyle("success");
            kb.row(upgradeBtn);
        }

        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }

    // ── Pure-static helpers ────────────────────────────────────────────────

    /** Дублирует BuildingService.isUnderConstruction() для static-контекста. */
    private static boolean buildingService_isUnderConstruction(IslandBuilding b) {
        return b.getBuildFinishAt() != null && LocalDateTime.now().isBefore(b.getBuildFinishAt());
    }

    /** Дублирует BuildingService.canAfford() для static-контекста. */
    private static boolean canAffordStatic(Island island, BuildingType type, int targetLevel) {
        return island.getFish()   >= type.fishCostFor(targetLevel)
            && island.getShells() >= type.shellsCostFor(targetLevel)
            && island.getWood()   >= type.woodCostFor(targetLevel);
    }

    /** Дублирует BuildingService.getAccumulatedFish() для static-контекста. */
    static int calcAccumulated(IslandBuilding b) {
        if (b.getBuildFinishAt() != null || b.getLevel() == 0) return 0;
        LocalDateTime from = b.getProductionCollectedAt();
        if (from == null) from = LocalDateTime.now().minusHours(BuildingType.CAP_HOURS);
        double elapsedHours = Duration.between(from, LocalDateTime.now()).toMinutes() / 60.0;
        int produced = (int)(b.getBuildingType().productionPerHourAt(b.getLevel()) * elapsedHours);
        return Math.min(produced, b.getBuildingType().capAt(b.getLevel()));
    }

    private static String costsText(BuildingType type, int level) {
        StringBuilder sb = new StringBuilder();
        sb.append(type.fishCostFor(level)).append(" 🐟");
        if (type.shellsCostFor(level) > 0) sb.append("  ").append(type.shellsCostFor(level)).append(" 🐚");
        if (type.woodCostFor(level)   > 0) sb.append("  ").append(type.woodCostFor(level)).append(" 🪵");
        return sb.toString();
    }

    static String minutesToText(int minutes) {
        if (minutes < 60) return minutes + " мин";
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + " ч" : h + " ч " + m + " мин";
    }

    private static String remainingText(LocalDateTime finishAt) {
        long totalSec = Math.max(0, Duration.between(LocalDateTime.now(), finishAt).getSeconds());
        long h = totalSec / 3600, m = (totalSec % 3600) / 60;
        if (h > 0) return h + " ч " + m + " мин";
        if (m > 0) return m + " мин";
        return totalSec + " сек";
    }
}

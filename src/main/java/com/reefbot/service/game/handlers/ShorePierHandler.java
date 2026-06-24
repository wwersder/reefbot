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
 * Экран конкретного здания — Рыбацкий помост.
 * Скрин: ZONE_SHORE_PIER.
 *
 * <p>Погружающий экран: флейвор-текст, состояние производства,
 * сбор рыбы, апгрейд. Навигация: ZONE_SHORE → ZONE_SHORE_PIER.
 */
@Component
@RequiredArgsConstructor
public class ShorePierHandler implements GameHandler {

    public static final String BTN_COLLECT          = "🫳 Собрать рыбу";
    public static final String BTN_UPGRADE          = "⬆️ Улучшить";
    public static final String BTN_UPGRADE_CONFIRM  = "🔨 Возвести улучшение";
    public static final String BTN_STORAGE          = "🗄 Хранилище";
    public static final String BTN_STORAGE_UPGRADE  = "⬆️ Расширить";
    public static final String BTN_SUBMENU_BACK     = "◀️ Назад";
    public static final String BTN_BACK             = "◀️ На берег";

    private final BuildingService buildingService;
    private final TideService tideService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE_PIER;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        // Автофинализация: апгрейд завершён — переходим на новый уровень
        Optional<IslandBuilding> pierOpt = buildingService.find(island, BuildingType.FISHING_PIER);
        if (pierOpt.isPresent() && buildingService.isConstructionReady(pierOpt.get())) {
            pierOpt = Optional.of(buildingService.finalize(pierOpt.get()));
        }
        IslandBuilding pier = pierOpt.orElse(null);

        // Если помоста нет или ещё строится — возврат в зону
        if (pier == null || pier.getLevel() == 0) {
            return goBack(player, island, null);
        }

        // Auto-finalize storage upgrade if ready
        if (buildingService.isStorageConstructionReady(pier)) {
            pier = buildingService.finalizeStorage(pier);
        }

        return switch (text) {
            case BTN_COLLECT         -> tryCollect(player, island, pier);
            case BTN_UPGRADE         -> showUpgradeMenu(island, pier);
            case BTN_UPGRADE_CONFIRM -> tryUpgradeConfirm(player, island, pier);
            case BTN_STORAGE         -> showStorage(island, pier);
            case BTN_STORAGE_UPGRADE -> tryStorageUpgrade(island, pier);
            case BTN_SUBMENU_BACK    -> buildPierScreen(island, pier);
            case BTN_BACK            -> goBack(player, island, pier);
            default                  -> buildPierScreen(island, pier);
        };
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private BotResponse tryCollect(Player player, Island island, IslandBuilding pier) {
        if (!buildingService.isOperational(pier)) {
            return buildPierScreen(island, pier);
        }
        int fish = buildingService.collectFish(island, pier);
        if (fish <= 0) return buildPierScreen(island, pier);

        IslandBuilding refreshed = buildingService.find(island, BuildingType.FISHING_PIER).orElse(pier);

        RichText rt = new RichText();
        rt.bold("🫳 Улов собран").add("\n\n")
          .add("Рыба из воды — в твои руки.\n")
          .add("+").bold(fish + " 🐟")
          .add("  |  всего: ").bold(Fmt.n(island.getFish()));
        return rt.build().withFollowUp(buildPierScreen(island, refreshed));
    }

    /** Opens the upgrade sub-menu showing costs and options. */
    private BotResponse showUpgradeMenu(Island island, IslandBuilding pier) {
        if (buildingService.isUnderConstruction(pier)) {
            return buildPierScreen(island, pier);
        }
        int lvl       = pier.getLevel();
        int nextLvl   = lvl + 1;
        int prod      = BuildingType.FISHING_PIER.productionPerHourAt(lvl);
        int prodNext  = BuildingType.FISHING_PIER.productionPerHourAt(nextLvl);
        boolean canAfford = canAffordStatic(island, BuildingType.FISHING_PIER, nextLvl);

        RichText rt = new RichText();
        rt.bold("⬆️ Улучшение помоста").add("\n\n");
        rt.add("До уровня ").bold(String.valueOf(nextLvl))
          .add(" · ").bold(BuildingType.FISHING_PIER.nameAt(nextLvl)).add("\n\n");
        rt.add("Производство: ").bold("+" + prod + " → +" + prodNext + " 🐟/ч").add("\n");
        rt.add("Стоимость: ").bold(costsText(BuildingType.FISHING_PIER, nextLvl)).add("\n");
        rt.add("Время: ").bold(minutesToText(BuildingType.FISHING_PIER.buildMinutesFor(nextLvl)));

        if (!canAfford) {
            rt.add("\n\n").bold("❌ Не хватает: ")
              .add(Fmt.n(island.getFish()) + " 🐟  "
                 + Fmt.n(island.getShells()) + " 🐚  "
                 + Fmt.n(island.getWood()) + " 🪵  в наличии");
        }

        KeyboardBuilder kb = KeyboardBuilder.builder();
        KeyboardButton confirmBtn = new KeyboardButton(BTN_UPGRADE_CONFIRM);
        if (canAfford) confirmBtn.setStyle("success");
        kb.row(confirmBtn, new KeyboardButton(BTN_STORAGE));
        kb.row(new KeyboardButton(BTN_SUBMENU_BACK));
        return rt.build(kb.build());
    }

    /** Executes the actual upgrade after confirmation. */
    private BotResponse tryUpgradeConfirm(Player player, Island island, IslandBuilding pier) {
        if (buildingService.isUnderConstruction(pier)) {
            return buildPierScreen(island, pier);
        }
        int targetLevel = pier.getLevel() + 1;
        if (!buildingService.canAfford(island, BuildingType.FISHING_PIER, targetLevel)) {
            return showUpgradeMenu(island, pier);
        }
        IslandBuilding updated = buildingService.startBuild(island, BuildingType.FISHING_PIER);
        return buildPierScreen(island, updated);
    }

    /** Storage sub-screen: capacity info + independent upgrade. */
    private BotResponse showStorage(Island island, IslandBuilding pier) {
        int storageLvl = pier.getStorageLevel() != null ? pier.getStorageLevel() : 1;
        int cap  = BuildingType.FISHING_PIER.storageCapAt(storageLvl);
        int prod = BuildingType.FISHING_PIER.productionPerHourAt(pier.getLevel());
        boolean expanding = buildingService.isStorageUnderConstruction(pier);

        RichText rt = new RichText();
        rt.bold("🗄 Хранилище помоста").add("\n\n");
        rt.add("Вместительность: ").bold(cap + " 🐟")
          .add("  (ур. " + storageLvl + ")\n");
        if (prod > 0) {
            int fillHours = cap / prod;
            rt.add("Заполнится за: ").bold(fillHours + " ч")
              .add(" (при +" + prod + " 🐟/ч)\n");
            int acc = calcAccumulated(pier);
            if (acc < cap) {
                int minsLeft = prod > 0 ? ((cap - acc) * 60 / prod) : 0;
                rt.add("До заполнения: ").bold("~" + minutesToText(minsLeft));
            } else {
                rt.bold("Хранилище заполнено!");
            }
        }

        if (expanding) {
            rt.add("\n\n⏳ Расширяется... осталось ")
              .bold(remainingText(pier.getStorageBuildFinishAt()));
        } else {
            int nextStorageLvl = storageLvl + 1;
            int cost = BuildingType.FISHING_PIER.storageFishCostFor(nextStorageLvl);
            int time = BuildingType.FISHING_PIER.storageBuildMinutesFor(nextStorageLvl);
            int nextCap = BuildingType.FISHING_PIER.storageCapAt(nextStorageLvl);
            rt.add("\n\n").bold("⬆️ До ур. " + nextStorageLvl + ":  " + nextCap + " 🐟")
              .add("  ·  ").add(cost + " 🐟  ·  " + minutesToText(time));
        }

        KeyboardBuilder kb = KeyboardBuilder.builder();
        if (!expanding) {
            int nextStorageLvl = storageLvl + 1;
            KeyboardButton expandBtn = new KeyboardButton(BTN_STORAGE_UPGRADE);
            if (island.getFish() >= BuildingType.FISHING_PIER.storageFishCostFor(nextStorageLvl)) {
                expandBtn.setStyle("success");
            }
            kb.row(expandBtn, new KeyboardButton(BTN_SUBMENU_BACK));
        } else {
            kb.row(new KeyboardButton(BTN_SUBMENU_BACK));
        }
        return rt.build(kb.build());
    }

    /** Execute storage upgrade. */
    private BotResponse tryStorageUpgrade(Island island, IslandBuilding pier) {
        if (buildingService.isStorageUnderConstruction(pier)) {
            return showStorage(island, pier);
        }
        int storageLvl = pier.getStorageLevel() != null ? pier.getStorageLevel() : 1;
        if (!buildingService.canAffordStorageUpgrade(island, BuildingType.FISHING_PIER, storageLvl + 1)) {
            return showStorage(island, pier);
        }
        IslandBuilding updated = buildingService.startStorageBuild(island, pier);
        return showStorage(island, updated);
    }

    private BotResponse goBack(Player player, Island island, IslandBuilding pier) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return ShoreZoneHandler.buildZoneScreen(player, tideService, pier);
    }

    // ── Static screen builder ──────────────────────────────────────────────

    /**
     * Main screen builder — called from ShoreZoneHandler.routePier() and after actions.
     */
    public static BotResponse buildPierScreen(Island island, IslandBuilding pier) {
        int lvl  = pier.getLevel();
        boolean upgrading = pier.getBuildFinishAt() != null
                && LocalDateTime.now().isBefore(pier.getBuildFinishAt());
        int storageLvl = pier.getStorageLevel() != null ? pier.getStorageLevel() : 1;
        int acc  = calcAccumulated(pier);
        int cap  = BuildingType.FISHING_PIER.storageCapAt(storageLvl);
        int prod = BuildingType.FISHING_PIER.productionPerHourAt(lvl);

        RichText rt = new RichText();

        // Header: name + level
        rt.bold("⚓ " + BuildingType.FISHING_PIER.nameAt(lvl) + " (ур. " + lvl + ")").add("\n\n");

        // Accumulated fish — bold when full
        rt.add("Накоплено: ");
        if (acc >= cap) {
            rt.bold(acc + "/" + cap + " 🐟");
        } else {
            rt.add(acc + "/" + cap + " 🐟");
        }
        rt.add("\n");

        // Production rate
        rt.add("Производительность: ").bold("+" + prod + " 🐟/ч");

        // Upgrade timer if active
        if (upgrading) {
            rt.add("\n\n⏳ Улучшается... осталось ").bold(remainingText(pier.getBuildFinishAt()));
        }

        return rt.build(keyboard(island, pier, upgrading, acc, cap));
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    private static ReplyKeyboard keyboard(Island island, IslandBuilding pier,
                                          boolean upgrading, int acc, int cap) {
        KeyboardBuilder kb = KeyboardBuilder.builder();

        // Collect — only when fish is ready
        if (acc > 0) {
            KeyboardButton collectBtn = new KeyboardButton(BTN_COLLECT);
            collectBtn.setStyle("success");
            kb.row(collectBtn);
        }

        // Upgrade — hidden while upgrading
        if (!upgrading) {
            int nextLvl = pier.getLevel() + 1;
            KeyboardButton upgradeBtn = new KeyboardButton(BTN_UPGRADE);
            if (canAffordStatic(island, BuildingType.FISHING_PIER, nextLvl)) {
                upgradeBtn.setStyle("success");
            }
            kb.row(upgradeBtn);
        }

        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }

    // ── Static helpers (используются также ShoreZoneHandler) ──────────────

    /** Накопленная рыба (0 если строится или уровень 0). */
    public static int calcAccumulated(IslandBuilding b) {
        if (b.getBuildFinishAt() != null || b.getLevel() == 0) return 0;
        LocalDateTime from = b.getProductionCollectedAt();
        if (from == null) from = LocalDateTime.now().minusHours(BuildingType.CAP_HOURS);
        double elapsedHours = Duration.between(from, LocalDateTime.now()).toMinutes() / 60.0;
        int produced = (int) (b.getBuildingType().productionPerHourAt(b.getLevel()) * elapsedHours);
        int storageLvl = b.getStorageLevel() != null ? b.getStorageLevel() : 1;
        return Math.min(produced, b.getBuildingType().storageCapAt(storageLvl));
    }

    private static boolean canAffordStatic(Island island, BuildingType type, int targetLevel) {
        return island.getFish()   >= type.fishCostFor(targetLevel)
            && island.getShells() >= type.shellsCostFor(targetLevel)
            && island.getWood()   >= type.woodCostFor(targetLevel);
    }

    public static String costsText(BuildingType type, int level) {
        StringBuilder sb = new StringBuilder();
        sb.append(type.fishCostFor(level)).append(" 🐟");
        if (type.shellsCostFor(level) > 0) sb.append("  ").append(type.shellsCostFor(level)).append(" 🐚");
        if (type.woodCostFor(level)   > 0) sb.append("  ").append(type.woodCostFor(level)).append(" 🪵");
        return sb.toString();
    }

    public static String minutesToText(int minutes) {
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

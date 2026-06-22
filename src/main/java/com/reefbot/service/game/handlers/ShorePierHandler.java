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

    public static final String BTN_COLLECT = "📦 Собрать рыбу";
    public static final String BTN_UPGRADE = "⬆️ Улучшить";
    public static final String BTN_BACK    = "◀️ На берег";

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

        return switch (text) {
            case BTN_COLLECT -> tryCollect(player, island, pier);
            case BTN_UPGRADE -> tryUpgrade(player, island, pier);
            case BTN_BACK    -> goBack(player, island, pier);
            default          -> buildPierScreen(island, pier);
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
        rt.bold("📦 Улов собран").add("\n\n")
          .add("Рыба из воды — в твои руки.\n")
          .add("+").bold(fish + " 🐟")
          .add("  |  всего: ").bold(Fmt.n(island.getFish()));
        return rt.build().withFollowUp(buildPierScreen(island, refreshed));
    }

    private BotResponse tryUpgrade(Player player, Island island, IslandBuilding pier) {
        if (buildingService.isUnderConstruction(pier)) {
            return buildPierScreen(island, pier); // уже идёт апгрейд
        }

        int targetLevel = pier.getLevel() + 1;

        if (!buildingService.canAfford(island, BuildingType.FISHING_PIER, targetLevel)) {
            RichText rt = new RichText();
            rt.bold("❌ Не хватает ресурсов").add("\n\n")
              .add("Нужно: ").bold(costsText(BuildingType.FISHING_PIER, targetLevel)).add("\n")
              .add("Есть:  ").bold(Fmt.n(island.getFish()) + " 🐟  "
                  + Fmt.n(island.getShells()) + " 🐚  "
                  + Fmt.n(island.getWood()) + " 🪵");
            return rt.build().withFollowUp(buildPierScreen(island, pier));
        }

        IslandBuilding updated = buildingService.startBuild(island, BuildingType.FISHING_PIER);
        return buildPierScreen(island, updated);
    }

    private BotResponse goBack(Player player, Island island, IslandBuilding pier) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return ShoreZoneHandler.buildZoneScreen(player, tideService, pier);
    }

    // ── Static screen builder ──────────────────────────────────────────────

    /**
     * Главный статический билдер экрана — вызывается из ShoreZoneHandler.routePier().
     */
    public static BotResponse buildPierScreen(Island island, IslandBuilding pier) {
        int lvl  = pier.getLevel();
        boolean upgrading = pier.getBuildFinishAt() != null
                && LocalDateTime.now().isBefore(pier.getBuildFinishAt());
        int acc  = calcAccumulated(pier);
        int cap  = BuildingType.FISHING_PIER.capAt(lvl);
        int prod = BuildingType.FISHING_PIER.productionPerHourAt(lvl);

        RichText rt = new RichText();

        // Заголовок с текущим именем уровня
        rt.bold("⚓ " + BuildingType.FISHING_PIER.nameAt(lvl)).add("\n\n");

        // Флейвор — описание места
        rt.add(flavorText(lvl)).add("\n\n");

        // Производство
        rt.add("Уровень ").bold(String.valueOf(lvl))
          .add("  ·  ").bold("+" + prod + " 🐟/ч")
          .add("  ·  потолок " + BuildingType.CAP_HOURS + " ч\n");

        // Накопленная рыба
        if (acc >= cap) {
            rt.bold("⚡ Хранилище полно: " + acc + " 🐟").add(" — забирай скорее!\n");
        } else if (acc > 0) {
            rt.bold("Готово к сбору: " + acc + " / " + cap + " 🐟\n");
        } else {
            rt.add("Накоплено: " + acc + " / " + cap + " 🐟\n");
        }

        // Статус апгрейда или информация о следующем уровне
        if (upgrading) {
            int targetLevel = lvl + 1;
            rt.add("\n⏳ Улучшается до ")
              .bold(BuildingType.FISHING_PIER.nameAt(targetLevel))
              .add("\nОсталось: ").bold(remainingText(pier.getBuildFinishAt()));
        } else {
            int nextLvl = lvl + 1;
            rt.add("\n")
              .bold("→ " + BuildingType.FISHING_PIER.nameAt(nextLvl))
              .add("  ур." + nextLvl + "\n")
              .add("Производство: ").bold("+" + BuildingType.FISHING_PIER.productionPerHourAt(nextLvl) + " 🐟/ч")
              .add("  ·  ").add(costsText(BuildingType.FISHING_PIER, nextLvl))
              .add("  ·  ").add(minutesToText(BuildingType.FISHING_PIER.buildMinutesFor(nextLvl)));
        }

        return rt.build(keyboard(island, pier, upgrading, acc, cap));
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    private static ReplyKeyboard keyboard(Island island, IslandBuilding pier,
                                          boolean upgrading, int acc, int cap) {
        KeyboardBuilder kb = KeyboardBuilder.builder();

        if (acc > 0) {
            KeyboardButton collectBtn = new KeyboardButton(BTN_COLLECT);
            collectBtn.setStyle("success");
            kb.row(collectBtn);
        }

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
        return Math.min(produced, b.getBuildingType().capAt(b.getLevel()));
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

    // ── Flavor texts ───────────────────────────────────────────────────────

    private static String flavorText(int level) {
        return switch (level) {
            case 1 -> """
                    Скрипучие доски над водой, обмотанные старым тросом.
                    Рыба сходится к теням свай — больше и не нужно.""";
            case 2 -> """
                    Навес из парусины укрыл от брызг. Крючки на перилах,
                    вёдра в углу — теперь здесь можно работать всерьёз.""";
            case 3 -> """
                    Настоящая пристань. Лодка у борта покачивается на волнах,
                    рыбаки с соседних островов иногда причаливают сюда.""";
            default -> "Помост тянется далеко в море. Сваи уходят в глубину.";
        };
    }
}

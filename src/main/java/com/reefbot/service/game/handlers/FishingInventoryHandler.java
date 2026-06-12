package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerFishing;
import com.reefbot.enums.ConsumableItem;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.TideService;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.time.LocalDateTime;

/**
 * Экран «🎒 Рюкзак» — просмотр и применение расходников из прилива.
 * Экран: FISHING_INVENTORY.
 *
 * Предметы:
 *   📜 Свиток ускорения — следующий бросок −50% времени
 *   🪱 Морская наживка  — следующий улов +50% рыбы
 *   🪝 Старый крюк      — +40 XP к следующему улову
 *   🫙 Склянка прилива  — мгновенно завершает рыбалку
 */
@Component
@RequiredArgsConstructor
public class FishingInventoryHandler implements GameHandler {

    public static final String BTN_USE_SCROLL = "📜 Применить свиток";
    public static final String BTN_USE_BAIT   = "🪱 Применить наживку";
    public static final String BTN_USE_HOOK   = "🪝 Применить крюк";
    public static final String BTN_USE_VIAL   = "🫙 Применить склянку";
    public static final String BTN_BACK       = "◀️ Назад";

    private final TideService tideService;
    private final FishingService fishingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.FISHING_INVENTORY;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_USE_SCROLL -> apply(player, island, ConsumableItem.SPEED_SCROLL);
            case BTN_USE_BAIT   -> apply(player, island, ConsumableItem.BAIT);
            case BTN_USE_HOOK   -> apply(player, island, ConsumableItem.FISHING_HOOK);
            case BTN_USE_VIAL   -> applyVial(player, island);
            case BTN_BACK       -> goBack(player);
            default             -> buildScreen(player);
        };
    }

    // ── Action handlers ────────────────────────────────────────────────────

    private BotResponse apply(Player player, Island island, ConsumableItem item) {
        if (tideService.getItemCount(player, item) <= 0 || isEffectActive(player, item)) {
            return buildScreen(player);
        }
        tideService.consumeItem(player, item);
        setEffect(player, item, true);
        playerRepository.save(player);
        return buildScreen(player);
    }

    /**
     * Склянка прилива — особый предмет:
     *  • если рыбалка активна → немедленно завершить её
     *  • иначе → поставить в очередь (следующий бросок будет мгновенным)
     */
    private BotResponse applyVial(Player player, Island island) {
        if (tideService.getItemCount(player, ConsumableItem.TIDE_VIAL) <= 0) {
            return buildScreen(player);
        }
        tideService.consumeItem(player, ConsumableItem.TIDE_VIAL);

        if (fishingService.isActive(player)) {
            // Instantly finish current fishing
            player.getFishing().setFishingFinishAt(LocalDateTime.now().minusSeconds(1));
            player.getState().setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        } else {
            // Queue for next cast — startFishing will set duration=0
            player.getFishing().setEffectInstantNext(true);
            playerRepository.save(player);
            return buildScreen(player);
        }
    }

    private BotResponse goBack(Player player) {
        player.getState().setCurrentScreen(PlayerScreen.FISHING_MENU);
        playerRepository.save(player);
        return FishingMenuHandler.buildFishingMenu(player);
    }

    // ── Screen builder ─────────────────────────────────────────────────────

    public BotResponse buildScreen(Player player) {
        int scrollCount = tideService.getItemCount(player, ConsumableItem.SPEED_SCROLL);
        int baitCount   = tideService.getItemCount(player, ConsumableItem.BAIT);
        int hookCount   = tideService.getItemCount(player, ConsumableItem.FISHING_HOOK);
        int vialCount   = tideService.getItemCount(player, ConsumableItem.TIDE_VIAL);

        boolean hasAny = scrollCount + baitCount + hookCount + vialCount > 0;

        PlayerFishing f = player.getFishing();
        boolean scrollActive  = Boolean.TRUE.equals(f.getEffectSpeedCast());
        boolean baitActive    = Boolean.TRUE.equals(f.getEffectYieldBonus());
        boolean hookActive    = Boolean.TRUE.equals(f.getEffectXpBonus());
        boolean vialQueued    = Boolean.TRUE.equals(f.getEffectInstantNext());

        RichText rt = new RichText();
        rt.beginBold().add("🎒 Рюкзак рыбака").endBold().add("\n\n");

        if (!hasAny && !scrollActive && !baitActive && !hookActive && !vialQueued) {
            rt.add("Рюкзак пуст.\nПолучить предметы можно во время прилива 🌊");
        } else {
            // ── Item list ─────────────────────────────────────────────
            if (scrollCount > 0 || scrollActive) {
                rt.add("📜 Свиток ускорения");
                if (scrollCount > 0) rt.add(" ×" + scrollCount);
                if (scrollActive) rt.add(" — ").beginBold().add("✅ активен").endBold();
                rt.add("\n   Следующий заброс −50% времени\n\n");
            }
            if (baitCount > 0 || baitActive) {
                rt.add("🪱 Морская наживка");
                if (baitCount > 0) rt.add(" ×" + baitCount);
                if (baitActive) rt.add(" — ").beginBold().add("✅ активна").endBold();
                rt.add("\n   Следующий улов +50% рыбы\n\n");
            }
            if (hookCount > 0 || hookActive) {
                rt.add("🪝 Старый крюк");
                if (hookCount > 0) rt.add(" ×" + hookCount);
                if (hookActive) rt.add(" — ").beginBold().add("✅ активен").endBold();
                rt.add("\n   +40 XP к следующему улову\n\n");
            }
            if (vialCount > 0 || vialQueued) {
                rt.add("🫙 Склянка прилива");
                if (vialCount > 0) rt.add(" ×" + vialCount);
                if (vialQueued) rt.add(" — ").beginBold().add("✅ в очереди").endBold();
                rt.add("\n   Мгновенно завершает рыбалку\n\n");
            }
        }

        // ── Keyboard ──────────────────────────────────────────────────
        KeyboardBuilder kb = KeyboardBuilder.builder();

        if (scrollCount > 0 && !scrollActive) kb.row(new KeyboardButton(BTN_USE_SCROLL));
        if (baitCount   > 0 && !baitActive)   kb.row(new KeyboardButton(BTN_USE_BAIT));
        if (hookCount   > 0 && !hookActive)   kb.row(new KeyboardButton(BTN_USE_HOOK));
        if (vialCount   > 0 && !vialQueued)   kb.row(new KeyboardButton(BTN_USE_VIAL));

        kb.row(new KeyboardButton(BTN_BACK));

        return rt.build(kb.build());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static boolean isEffectActive(Player player, ConsumableItem item) {
        PlayerFishing f = player.getFishing();
        return switch (item) {
            case SPEED_SCROLL -> Boolean.TRUE.equals(f.getEffectSpeedCast());
            case BAIT         -> Boolean.TRUE.equals(f.getEffectYieldBonus());
            case FISHING_HOOK -> Boolean.TRUE.equals(f.getEffectXpBonus());
            case TIDE_VIAL    -> Boolean.TRUE.equals(f.getEffectInstantNext());
        };
    }

    private static void setEffect(Player player, ConsumableItem item, boolean value) {
        PlayerFishing f = player.getFishing();
        switch (item) {
            case SPEED_SCROLL -> f.setEffectSpeedCast(value);
            case BAIT         -> f.setEffectYieldBonus(value);
            case FISHING_HOOK -> f.setEffectXpBonus(value);
            case TIDE_VIAL    -> f.setEffectInstantNext(value);
        }
    }
}

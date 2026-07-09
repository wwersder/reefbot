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

    public static final String BTN_USE_SCROLL        = "📜 Свиток ускорения";
    public static final String BTN_USE_BAIT           = "🪱 Морская наживка";
    public static final String BTN_USE_HOOK           = "🪝 Старый крюк";
    public static final String BTN_USE_VIAL           = "🫙 Склянка прилива";
    // Кнопки с ✅ — показываются когда эффект выбран; повторное нажатие снимает выбор
    public static final String BTN_USE_SCROLL_ACTIVE  = "✅ 📜 Свиток ускорения";
    public static final String BTN_USE_BAIT_ACTIVE    = "✅ 🪱 Морская наживка";
    public static final String BTN_USE_HOOK_ACTIVE    = "✅ 🪝 Старый крюк";
    public static final String BTN_USE_VIAL_ACTIVE    = "✅ 🫙 Склянка прилива";
    public static final String BTN_BACK               = "◀️ Назад";

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
            case BTN_USE_SCROLL, BTN_USE_SCROLL_ACTIVE -> toggle(player, ConsumableItem.SPEED_SCROLL);
            case BTN_USE_BAIT,   BTN_USE_BAIT_ACTIVE   -> toggle(player, ConsumableItem.BAIT);
            case BTN_USE_HOOK,   BTN_USE_HOOK_ACTIVE   -> toggle(player, ConsumableItem.FISHING_HOOK);
            case BTN_USE_VIAL,   BTN_USE_VIAL_ACTIVE   -> toggleVial(player, island);
            case BTN_BACK                              -> goBack(player, island);
            default                                    -> buildScreen(player);
        };
    }

    // ── Action handlers ────────────────────────────────────────────────────

    /**
     * Тогл эффекта: выбрать → кнопка становится ✅; повторно → снять выбор.
     * Предмет НЕ списывается здесь — он списывается в момент заброса/сбора улова.
     */
    private BotResponse toggle(Player player, ConsumableItem item) {
        if (isEffectActive(player, item)) {
            // Снимаем выбор
            setEffect(player, item, false);
        } else {
            // Выбираем только если предмет есть в наличии
            if (tideService.getItemCount(player, item) <= 0) return buildScreen(player);
            setEffect(player, item, true);
        }
        playerRepository.save(player);
        return buildScreen(player);
    }

    /**
     * Склянка прилива:
     *  • если рыбалка уже идёт → списать немедленно и завершить
     *  • иначе → тогл (предмет спишется при следующем забросе)
     */
    private BotResponse toggleVial(Player player, Island island) {
        if (fishingService.isActive(player)) {
            // Используем немедленно — списываем сразу
            if (tideService.getItemCount(player, ConsumableItem.TIDE_VIAL) <= 0) return buildScreen(player);
            tideService.consumeItem(player, ConsumableItem.TIDE_VIAL);
            player.getFishing().setFishingFinishAt(LocalDateTime.now().minusSeconds(1));
            // Clear queued flag in case it was set before fishing started
            player.getFishing().setEffectInstantNext(false);
            player.getState().setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        }
        // Не рыбачим — тогл в очередь (списание при забросе)
        return toggle(player, ConsumableItem.TIDE_VIAL);
    }

    /**
     * Назад из рюкзака — экран назначения зависит от состояния рыбалки:
     *  • ещё идёт  → FISHING_ACTIVE (не ломаем активную сессию)
     *  • готова    → FISHING_RESULT (не пропускаем улов)
     *  • свободна  → FISHING_MENU
     */
    private BotResponse goBack(Player player, Island island) {
        if (fishingService.isActive(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_ACTIVE);
            playerRepository.save(player);
            return FishingActiveHandler.buildStatusScreen(player, fishingService,
                    FishingActiveHandler.activeKeyboard());
        }
        if (fishingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        }
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
                rt.bold("📜 Свиток ускорения");
                if (scrollCount > 0) rt.add("  (" + scrollCount + " шт.)");
                if (scrollActive) rt.add("  ✅ активен");
                rt.add("\n").blockquote("Следующий заброс удочки −50% времени. Расходуется при забросе.").add("\n\n");
            }
            if (baitCount > 0 || baitActive) {
                rt.bold("🪱 Морская наживка");
                if (baitCount > 0) rt.add("  (" + baitCount + " шт.)");
                if (baitActive) rt.add("  ✅ активна");
                rt.add("\n").blockquote("Следующий улов +50% рыбы. Расходуется при сборе улова.").add("\n\n");
            }
            if (hookCount > 0 || hookActive) {
                rt.bold("🪝 Старый крюк");
                if (hookCount > 0) rt.add("  (" + hookCount + " шт.)");
                if (hookActive) rt.add("  ✅ активен");
                rt.add("\n").blockquote("+40 XP к следующему улову. Расходуется при сборе улова.").add("\n\n");
            }
            if (vialCount > 0 || vialQueued) {
                rt.bold("🫙 Склянка прилива");
                if (vialCount > 0) rt.add("  (" + vialCount + " шт.)");
                if (vialQueued) rt.add("  ✅ в очереди");
                rt.add("\n").blockquote("Мгновенно завершает активную рыбалку. Можно применить во время ожидания.").add("\n\n");
            }
        }

        // ── Keyboard ──────────────────────────────────────────────────
        // Активный эффект → ✅-кнопка (повторный клик снимает выбор)
        // Предмет есть, но не выбран → обычная кнопка
        KeyboardBuilder kb = KeyboardBuilder.builder();

        if (scrollActive) {
            KeyboardButton b = new KeyboardButton(BTN_USE_SCROLL_ACTIVE);
            b.setStyle("success");
            kb.row(b);
        } else if (scrollCount > 0) {
            kb.row(new KeyboardButton(BTN_USE_SCROLL));
        }

        if (baitActive) {
            KeyboardButton b = new KeyboardButton(BTN_USE_BAIT_ACTIVE);
            b.setStyle("success");
            kb.row(b);
        } else if (baitCount > 0) {
            kb.row(new KeyboardButton(BTN_USE_BAIT));
        }

        if (hookActive) {
            KeyboardButton b = new KeyboardButton(BTN_USE_HOOK_ACTIVE);
            b.setStyle("success");
            kb.row(b);
        } else if (hookCount > 0) {
            kb.row(new KeyboardButton(BTN_USE_HOOK));
        }

        if (vialQueued) {
            KeyboardButton b = new KeyboardButton(BTN_USE_VIAL_ACTIVE);
            b.setStyle("success");
            kb.row(b);
        } else if (vialCount > 0) {
            kb.row(new KeyboardButton(BTN_USE_VIAL));
        }

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

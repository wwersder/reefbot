package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.ConsumableItem;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.TideService;
import com.reefbot.service.game.TideService.RoundResult;
import com.reefbot.service.game.TideService.TideReward;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.util.Map;

/**
 * Мини-игра «Прилив»: три броска кубика с угадыванием (высокое/низкое).
 * Экран: ZONE_SHORE_TIDE.
 */
@Component
@RequiredArgsConstructor
public class TideGameHandler implements GameHandler {

    // ── Button labels ──────────────────────────────────────────────────────

    public static final String BTN_OPEN = "🗝 Открыть";
    public static final String BTN_HIGH = "⬆️ Высокое (4–6)";
    public static final String BTN_LOW  = "⬇️ Низкое (1–3)";
    public static final String BTN_NEXT = "🎲 Следующий бросок";
    public static final String BTN_TAKE = "🎁 Забрать";
    public static final String BTN_BACK = "◀️ На берег";

    private final TideService tideService;
    private final PlayerRepository playerRepository;
    private final IslandRepository islandRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE_TIDE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_OPEN -> buildGuessScreen(player);
            case BTN_HIGH -> handleGuess(player, true);
            case BTN_LOW  -> handleGuess(player, false);
            case BTN_NEXT -> buildGuessScreen(player);
            case BTN_TAKE -> handleTake(player, island);
            case BTN_BACK -> goBack(player, island);
            default       -> buildDefaultScreen(player);
        };
    }

    // ── Screen builders ────────────────────────────────────────────────────

    /**
     * Entry screen — shown when player taps 🌊 Прилив! on shore.
     * Called statically from ShoreZoneHandler.
     */
    public static BotResponse buildEntryScreen(Player player, TideService tideService) {
        String narrative = tideService.getNarrative(player);
        RichText rt = new RichText();
        rt.beginBold().add("🌊 Прилив").endBold()
          .add("\n\n")
          .add(narrative)
          .add("\n\n")
          .add("Что-то лежит у воды. Угадай три броска кубика — ")
          .add("попаданий больше, награда лучше.");

        return rt.build(guessKeyboard(true));
    }

    /** Guess screen for current round. */
    private BotResponse buildGuessScreen(Player player) {
        int round = player.getTide().getTideRoundIndex() + 1; // 1-based for display
        int hits  = player.getTide().getTideHits();

        RichText rt = new RichText();
        rt.beginBold().add("🎲 Бросок " + round + " из 3").endBold()
          .add("\n")
          .add(hitsLine(hits, round - 1))
          .add("\n\n")
          .add("Кубик крутится... Угадай: высокое (4–6) или низкое (1–3)?");

        return rt.build(guessKeyboard(round == 1));
    }

    /** Called after a guess — shows the dice result + hit/miss. */
    private BotResponse buildResultScreen(Player player, RoundResult result, int roundJustPlayed) {
        int hits       = player.getTide().getTideHits();
        boolean done   = tideService.isCompleted(player);
        int nextRound  = player.getTide().getTideRoundIndex() + 1; // for display (1-based)

        RichText rt = new RichText();
        rt.beginBold().add("🎲 Кубик: " + result.roll()).endBold()
          .add("  ")
          .add(result.correct() ? "✅ Верно!" : "❌ Промах")
          .add("\n")
          .add(hitsLine(hits, roundJustPlayed))
          .add("\n\n");

        if (!done) {
            rt.add("Бросок " + nextRound + " из 3. Продолжишь?");
        } else {
            rt.add("Три броска сделано! Забирай, что выбросило море.");
        }

        ReplyKeyboard keyboard = done
                ? KeyboardBuilder.builder().row(btn(BTN_TAKE, true)).row(new KeyboardButton(BTN_BACK)).build()
                : KeyboardBuilder.builder()
                    .row(new KeyboardButton(BTN_NEXT))
                    .row(btn(BTN_TAKE, false))
                    .row(new KeyboardButton(BTN_BACK))
                    .build();

        return rt.build(keyboard);
    }

    /** Reward screen — shown after finishGame(). */
    private BotResponse buildRewardScreen(Player player, TideReward reward) {
        RichText rt = new RichText();
        rt.beginBold().add("🌊 Прилив закончился").endBold()
          .add("\n\n");

        if (reward.hasItems()) {
            rt.add("Море принесло:\n");
            for (Map.Entry<ConsumableItem, Integer> entry : reward.items().entrySet()) {
                ConsumableItem item = entry.getKey();
                int qty = entry.getValue();
                rt.add("  " + item.getDisplayName());
                if (qty > 1) rt.add(" ×" + qty);
                rt.add("\n");
            }
            rt.add("\n").add("Предметы добавлены в инвентарь.");
        } else {
            rt.add("Улов не ахти — ");
            rt.beginBold().add("+5 🐚 Ракушки").endBold();
            rt.add(" за попытку.");
        }

        rt.add("\n\nСледующий прилив придёт через несколько часов.");

        return rt.build(KeyboardBuilder.builder().row(new KeyboardButton(BTN_BACK)).build());
    }

    // ── Action handlers ────────────────────────────────────────────────────

    private BotResponse handleGuess(Player player, boolean high) {
        int roundJustPlayed = player.getTide().getTideRoundIndex() + 1; // before increment
        RoundResult result  = tideService.resolveRound(player, high);
        return buildResultScreen(player, result, roundJustPlayed);
    }

    private BotResponse handleTake(Player player, Island island) {
        // Give consolation shells if 0 hits
        TideReward reward = tideService.finishGame(player);
        if (!reward.hasItems() && reward.consolationShells() > 0) {
            island.setShells(island.getShells() + reward.consolationShells());
            islandRepository.save(island);
        }
        // Set screen back to shore
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return buildRewardScreen(player, reward);
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return ShoreZoneHandler.buildZoneScreen(player, tideService);
    }

    private BotResponse buildDefaultScreen(Player player) {
        if (tideService.isCompleted(player)) {
            // All 3 rounds done — show "take reward" prompt
            int hits = player.getTide().getTideHits();
            RichText rt = new RichText();
            rt.add("Три броска сделано! ")
              .add(hitsLine(hits, 3))
              .add("\n\nЗабирай, что выбросило море.");
            return rt.build(KeyboardBuilder.builder()
                    .row(btn(BTN_TAKE, true))
                    .row(new KeyboardButton(BTN_BACK))
                    .build());
        }
        int roundIndex = player.getTide().getTideRoundIndex();
        if (roundIndex == 0) {
            return buildEntryScreen(player, tideService);
        }
        return buildGuessScreen(player);
    }

    // ── Static helpers ─────────────────────────────────────────────────────

    private static String hitsLine(int hits, int roundsDone) {
        if (roundsDone == 0) return "";
        StringBuilder sb = new StringBuilder("Попадания: ");
        for (int i = 0; i < roundsDone; i++) {
            sb.append(i < hits ? "✅" : "❌");
        }
        return sb.toString();
    }

    private static KeyboardButton btn(String text, boolean success) {
        KeyboardButton b = new KeyboardButton(text);
        if (success) b.setStyle("success");
        return b;
    }

    private static ReplyKeyboard guessKeyboard(boolean firstRound) {
        KeyboardBuilder kb = KeyboardBuilder.builder()
                .row(new KeyboardButton(BTN_HIGH), new KeyboardButton(BTN_LOW));
        if (!firstRound) {
            // allow taking reward mid-game
            kb.row(btn(BTN_TAKE, false));
        }
        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }
}

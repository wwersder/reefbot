package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.ConsumableItem;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.enums.BuildingType;
import com.reefbot.service.game.BuildingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.TideService;
import com.reefbot.service.game.TideService.TideReward;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendDice;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Мини-игра «Прилив»: до трёх бросков кубика с угадыванием (больше/меньше трёх).
 * Экран: ZONE_SHORE_TIDE.
 *
 * Правила:
 *  - угадал → раунд выигран, можно рискнуть ещё раз или забрать
 *  - не угадал → игра проиграна, лут «унесло в море»
 *  - забрать можно только если выиграл хотя бы 1 раунд
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TideGameHandler implements GameHandler {

    // ── Button labels ──────────────────────────────────────────────────────

    public static final String BTN_OPEN = "🗝 Попробовать удачу";
    public static final String BTN_HIGH = "⬆️ Больше трёх";
    public static final String BTN_LOW  = "⬇️ Три или меньше";
    public static final String BTN_NEXT = "🎲 Рискнуть ещё раз";
    public static final String BTN_TAKE = "🎁 Забрать улов";
    public static final String BTN_BACK = "◀️ На берег";

    private final TideService tideService;
    private final TelegramClient telegramClient;
    private final PlayerRepository playerRepository;
    private final IslandRepository islandRepository;
    private final BuildingService buildingService;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE_TIDE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (!tideService.isActive(player)) {
            // Tide expired mid-session — route back to shore
            return goBack(player, island);
        }
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

    // ── Entry screen (called statically from ShoreZoneHandler) ────────────

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
          .add("Море вынесло кое-что на берег. Угадай бросок кубика — ")
          .beginBold().add("верно").endBold()
          .add(" значит можешь рискнуть ещё раз или взять уже есть. ")
          .beginBold().add("Промахнёшься — лут унесёт волной.")
          .endBold();

        ReplyKeyboard kb = KeyboardBuilder.builder()
                .row(new KeyboardButton(BTN_OPEN))
                .row(new KeyboardButton(BTN_BACK))
                .build();

        return rt.build(kb);
    }

    // ── Screen builders ────────────────────────────────────────────────────

    /** Экран выбора: высокое или низкое? */
    private BotResponse buildGuessScreen(Player player) {
        int round = player.getTide().getTideRoundIndex() + 1; // 1-based
        int hits  = player.getTide().getTideHits();

        RichText rt = new RichText();
        rt.beginBold().add("🎲 Бросок " + round + " из 3").endBold();
        if (hits > 0) {
            rt.add("  ").add(hitsDots(hits, round - 1));
        }
        rt.add("\n\n")
          .add(roundPrompt(round));

        return rt.build(guessKeyboard(hits));
    }

    /** Экран победы в раунде — кубик уже отправлен Telegram'ом отдельным сообщением. */
    private BotResponse buildSuccessScreen(Player player, int diceValue, int roundJustWon) {
        int hits = player.getTide().getTideHits(); // уже обновлён
        boolean done = tideService.isCompleted(player);

        RichText rt = new RichText();
        rt.beginBold().add("🎲 Выпало " + diceValue).endBold()
          .add(" — ✅ Угадал!\n\n")
          .add(roundSuccessText(roundJustWon))
          .add("\n\n")
          .add(hitsDots(hits, roundJustWon));

        if (!done) {
            rt.add("\n\n").add("Продолжишь или заберёшь?");
        }

        ReplyKeyboard kb;
        if (done) {
            kb = KeyboardBuilder.builder()
                    .row(btn(BTN_TAKE, true))
                    .row(new KeyboardButton(BTN_BACK))
                    .build();
        } else {
            kb = KeyboardBuilder.builder()
                    .row(btn(BTN_TAKE, false), new KeyboardButton(BTN_NEXT))
                    .row(new KeyboardButton(BTN_BACK))
                    .build();
        }

        return rt.build(kb);
    }

    /** Экран поражения — игра окончена, лут унесло. */
    private BotResponse buildFailureScreen(int diceValue, int roundNumber, int hitsBefore) {
        RichText rt = new RichText();
        rt.beginBold().add("🎲 Выпало " + diceValue).endBold()
          .add(" — ❌ Не угадал\n\n")
          .add(failureText(roundNumber, hitsBefore));

        rt.add("\n\nСледующий прилив придёт через несколько часов.");

        return rt.build(KeyboardBuilder.builder()
                .row(new KeyboardButton(BTN_BACK))
                .build());
    }

    /** Экран награды после «Забрать». */
    private BotResponse buildRewardScreen(TideReward reward) {
        RichText rt = new RichText();
        rt.beginBold().add("🌊 Прилив схлынул").endBold()
          .add("\n\n");

        if (reward.hasItems()) {
            rt.add("Море принесло:\n");
            for (Map.Entry<ConsumableItem, Integer> entry : reward.items().entrySet()) {
                ConsumableItem item = entry.getKey();
                int qty = entry.getValue();
                rt.add("  ").add(item.getDisplayName());
                if (qty > 1) rt.add(" ×" + qty);
                rt.add("\n");
            }
            if (reward.shells() > 0) {
                rt.add("  🐚 ×" + reward.shells() + "\n");
            }
            rt.add("\nПредметы добавлены в инвентарь.");
        } else {
            // 0 попаданий — только утешительные ракушки
            rt.add("Замки не поддались — улов унесло волной.\n");
            rt.add("Но ").beginBold().add("+").add(String.valueOf(reward.shells())).add(" 🐚").endBold()
              .add(" остались на берегу.");
        }

        rt.add("\n\nСледующий прилив придёт через несколько часов.");

        return rt.build(KeyboardBuilder.builder()
                .row(new KeyboardButton(BTN_BACK))
                .build());
    }

    // ── Action handlers ────────────────────────────────────────────────────

    private BotResponse handleGuess(Player player, boolean guessHigh) {
        // Защита от двойного нажатия: если кубик уже в воздухе — игнорируем
        if (Boolean.TRUE.equals(player.getTide().getRollPending())) {
            log.warn("Duplicate guess ignored for player {} — roll already pending", player.getId());
            return null;
        }

        int roundNumber = player.getTide().getTideRoundIndex() + 1; // 1-based, до инкремента
        int hitsBefore  = player.getTide().getTideHits();

        // Выставляем флаг ДО броска — блокирует «Забрать улов» и повторные нажатия
        player.getTide().setRollPending(true);
        playerRepository.save(player);

        // Бросаем настоящий кубик через Telegram (внутри — Thread.sleep 4с)
        int diceValue = sendDice(player.getTelegramId());

        // После 4-секундного ожидания перезагружаем игрока из БД —
        // за это время конкурентный поток мог изменить состояние игры
        Player fresh = playerRepository.findByTelegramId(player.getTelegramId())
                .orElse(player);

        if (fresh.getState().getCurrentScreen() != PlayerScreen.ZONE_SHORE_TIDE
                || !tideService.isActive(fresh)) {
            // Игра уже завершена другим потоком — снимаем флаг и молчим
            fresh.getTide().setRollPending(false);
            playerRepository.save(fresh);
            log.warn("Tide dice orphaned for player {} (value={}) — game already ended concurrently",
                    player.getId(), diceValue);
            return null;
        }

        // Флаг снимается внутри resolveCorrectRound / failGame
        boolean correct = guessHigh ? (diceValue > 3) : (diceValue <= 3);
        if (correct) {
            tideService.resolveCorrectRound(fresh);
            return buildSuccessScreen(fresh, diceValue, roundNumber);
        } else {
            tideService.failGame(fresh);
            return buildFailureScreen(diceValue, roundNumber, hitsBefore);
        }
    }

    private BotResponse handleTake(Player player, Island island) {
        // Кубик в воздухе — нельзя забирать
        if (Boolean.TRUE.equals(player.getTide().getRollPending())) {
            return new BotResponse("⏳ Подожди — кубик ещё в воздухе!");
        }
        TideReward reward = tideService.finishGame(player);
        if (reward.shells() > 0) {
            island.setShells(island.getShells() + reward.shells());
            islandRepository.save(island);
        }
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return buildRewardScreen(reward);
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        IslandBuilding pier = buildingService.find(island, BuildingType.FISHING_PIER).orElse(null);
        return ShoreZoneHandler.buildZoneScreen(player, tideService, pier);
    }

    private BotResponse buildDefaultScreen(Player player) {
        if (tideService.isCompleted(player)) {
            int hits = player.getTide().getTideHits();
            RichText rt = new RichText();
            rt.beginBold().add("Три броска сделано!").endBold()
              .add("  ").add(hitsDots(hits, 3))
              .add("\n\nЗабирай, что принёс прилив.");
            return rt.build(KeyboardBuilder.builder()
                    .row(btn(BTN_TAKE, true))
                    .row(new KeyboardButton(BTN_BACK))
                    .build());
        }
        if (player.getTide().getTideRoundIndex() == 0) {
            return buildEntryScreen(player, tideService);
        }
        return buildGuessScreen(player);
    }

    // ── Telegram dice ──────────────────────────────────────────────────────

    /**
     * Отправляет анимированный кубик в чат игрока и возвращает выпавшее значение (1–6).
     * При ошибке возвращает случайное значение-заглушку.
     */
    private int sendDice(Long chatId) {
        int value = ThreadLocalRandom.current().nextInt(6) + 1; // fallback
        try {
            Message msg = telegramClient.execute(
                    SendDice.builder().chatId(String.valueOf(chatId)).emoji("🎲").build()
            );
            value = msg.getDice().getValue();
            Thread.sleep(4000); // ждём пока анимация кубика завершится
        } catch (TelegramApiException e) {
            log.warn("Не удалось отправить кубик игроку {}, используем fallback", chatId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // восстанавливаем флаг прерывания
        }
        return value;
    }

    // ── Text helpers ───────────────────────────────────────────────────────

    private static String roundPrompt(int round) {
        return switch (round) {
            case 1 -> "Слышишь, как плещется? Первый замок ждёт.\nЧто выпадет на кубике?";
            case 2 -> "Второй замок. Испытай удачу ещё раз.\nЧто выпадет на кубике?";
            case 3 -> "Последний. Не торопись.\nЧто выпадет на кубике?";
            default -> "Что выпадет на кубике?";
        };
    }

    private static String roundSuccessText(int round) {
        return switch (round) {
            case 1 -> "Первый замок поддался.";
            case 2 -> "Второй замок открылся.";
            case 3 -> "Последний замок снят. Ящик твой!";
            default -> "Попадание!";
        };
    }

    private static String failureText(int round, int hitsBefore) {
        if (hitsBefore == 0) {
            return switch (round) {
                case 1 -> "Замок не поддался.\nВолна подхватила ящик и унесла в море.\nНичего не осталось.";
                case 2 -> "Второй замок устоял.\nПрибой смыл всё. Зря рисковал.";
                case 3 -> "Последний замок не дался.\nЯщик ушёл под воду вместе с содержимым.";
                default -> "Промах. Море забрало своё.";
            };
        }
        // hits > 0 but still lost on this round — already had some wins but lost them all
        return switch (round) {
            case 2 -> "Второй замок устоял.\nВолна подхватила ящик и унесла в море. Всё, что было — пропало.";
            case 3 -> "Последний замок не поддался.\nЯщик смыло волной. Так близко...";
            default -> "Промах. Море забрало всё.";
        };
    }

    private static String hitsDots(int hits, int roundsDone) {
        if (roundsDone == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < roundsDone; i++) {
            sb.append(i < hits ? "✅" : "❌");
        }
        return sb.toString();
    }

    // ── Keyboard helpers ───────────────────────────────────────────────────

    private static ReplyKeyboard guessKeyboard(int hits) {
        KeyboardBuilder kb = KeyboardBuilder.builder()
                .row(new KeyboardButton(BTN_HIGH), new KeyboardButton(BTN_LOW));
        if (hits > 0) {
            kb.row(btn(BTN_TAKE, false));
        }
        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }

    private static KeyboardButton btn(String text, boolean success) {
        KeyboardButton b = new KeyboardButton(text);
        if (success) b.setStyle("success");
        return b;
    }
}

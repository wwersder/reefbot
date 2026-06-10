package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class MainMenuHandler implements GameHandler {

    public static final String BTN_ISLAND = "🏝 Мой остров";
    public static final String BTN_BUILD  = "🏗 Строить";
    public static final String BTN_INV    = "📦 Инвентарь";
    /** Кнопка зоны «Берег» (бывшая «Рыбалка») — текст берётся из ZoneType. */
    public static final String BTN_SHORE  = "🏖 Берег";

    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.MAIN;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        // Route button presses
        return switch (text) {
            case BTN_SHORE  -> enterShoreZone(player);
            case BTN_ISLAND -> new BotResponse("⚙️ Экран острова — в разработке.", null, keyboard(player));
            case BTN_BUILD  -> new BotResponse("⚙️ Строительство — скоро!", null, keyboard(player));
            case BTN_INV    -> new BotResponse("⚙️ Инвентарь — скоро!", null, keyboard(player));
            default         -> showMainMenu(player, island);
        };
    }

    private BotResponse enterShoreZone(Player player) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return ShoreZoneHandler.buildZoneScreen(player);
    }

    /** Main menu text + keyboard. */
    public static BotResponse showMainMenu(Player player, Island island) {
        String stage = stageFor(island.getDevPoints());
        StringBuilder text = new StringBuilder(String.format("""
                🏝 Остров «%s»
                %s · %d ОР
                """, island.getName(), stage, island.getDevPoints()));

        String digest = digestLine(player);
        if (digest != null) {
            text.append("\n").append(digest).append("\n");
        }

        return new BotResponse(text.toString(), null, keyboard(player));
    }

    /** Дайджест: что происходит в зонах (пока — только рыбалка на Берегу). */
    private static String digestLine(Player player) {
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        if (finishAt == null) return null;
        if (!LocalDateTime.now().isBefore(finishAt)) {
            return "🏖 Улов готов!";
        }
        long minutes = Math.max(1,
                (Duration.between(LocalDateTime.now(), finishAt).getSeconds() + 59) / 60);
        return "🏖 Удочка заброшена — ещё ~" + minutes + " мин";
    }

    public static ReplyKeyboard keyboard(Player player) {
        return KeyboardBuilder.builder()
                .row(BTN_ISLAND, BTN_BUILD)
                .row(new KeyboardButton(BTN_INV), buildShoreButton(player))
                .build();
    }

    /** Кнопка зоны зелёная, если внутри что-то готово (пока — улов). */
    private static KeyboardButton buildShoreButton(Player player) {
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        KeyboardButton btn = new KeyboardButton(ZoneType.SHORE.getDisplayName());
        if (finishAt != null && !LocalDateTime.now().isBefore(finishAt)) {
            btn.setStyle("success");
        }
        return btn;
    }

    public static String stageFor(int devPoints) {
        if (devPoints >= 61) return "🏙 Процветающий город";
        if (devPoints >= 21) return "⚓ Развивающийся порт";
        if (devPoints >= 7)  return "🏘 Рыбацкая деревня";
        return "🌱 Дикий островок";
    }
}

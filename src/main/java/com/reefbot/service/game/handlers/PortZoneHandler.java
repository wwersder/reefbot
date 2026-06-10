package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class PortZoneHandler implements GameHandler {

    public static final String BTN_BACK = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "Скрип снастей, запах дёгтя и солёного моря.\nКорабли готовятся к отплытию.",
            "Чайки кричат над пустым причалом.\nЗдесь когда-то кипела морская торговля.",
            "Старый маяк смотрит в море.\nГоризонт манит далёкими островами.",
            "Волны плещутся о сваи причала.\nВетер несёт запах дальних берегов.",
            "Ржавые кольца в камне — всё, что осталось от флота.\nПорт ждёт возрождения."
    );

    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_PORT;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (BTN_BACK.equals(text)) {
            player.getState().setCurrentScreen(PlayerScreen.MAIN);
            playerRepository.save(player);
            return MainMenuHandler.showMainMenu(player, island);
        }
        return buildZoneScreen(player);
    }

    public static BotResponse buildZoneScreen(Player player) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));
        RichText rt = new RichText();
        rt.bold("⚓ Порт")
          .add("\n\n").add(flavor)
          .add("\n\n⚙️ Экспедиции и морская торговля — в разработке.");
        ReplyKeyboard kb = KeyboardBuilder.builder().row(BTN_BACK).build();
        return rt.build(ZoneType.PORT.getBannerPath(), kb);
    }
}

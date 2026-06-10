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
public class HillsZoneHandler implements GameHandler {

    public static final String BTN_BACK = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "Каменистый склон уходит в туман.\nОткуда-то сверху доносится стук кирки.",
            "Скалы нависают над узкой тропой.\nГде-то в глубине капает вода.",
            "Ветер свистит в расщелинах скал.\nВпереди — темнота пещерного входа.",
            "Под ногами хрустят мелкие камни.\nГоризонт отсюда виден на несколько островов.",
            "Воздух здесь острее и холоднее.\nСлоистые скалы хранят старые секреты."
    );

    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_HILLS;
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
        rt.bold("⛰ Холмы")
          .add("\n\n").add(flavor)
          .add("\n\n⚙️ Шахта и добыча ресурсов — в разработке.");
        ReplyKeyboard kb = KeyboardBuilder.builder().row(BTN_BACK).build();
        return rt.build(ZoneType.HILLS.getBannerPath(), kb);
    }
}

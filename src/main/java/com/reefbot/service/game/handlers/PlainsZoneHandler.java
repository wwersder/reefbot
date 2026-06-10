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
public class PlainsZoneHandler implements GameHandler {

    public static final String BTN_BACK = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "Ветер гонит волны по пшеничному полю.\nГоризонт широкий, небо огромное.",
            "Бескрайние поля тянутся до самого леса.\nПахнет землёй после дождя.",
            "Высокая трава шуршит на ветру.\nПтицы кружат над нетронутыми просторами.",
            "Тропа теряется в зелёных волнах равнины.\nЗдесь можно построить целую ферму.",
            "Солнечный свет падает ровно на поле.\nЭти земли ждут своего хозяина."
    );

    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_PLAINS;
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
        rt.bold("🌾 Равнина")
          .add("\n\n").add(flavor)
          .add("\n\n⚙️ Грядки, ферма и мельница — в разработке.");
        ReplyKeyboard kb = KeyboardBuilder.builder().row(BTN_BACK).build();
        return rt.build(ZoneType.PLAINS.getBannerPath(), kb);
    }
}

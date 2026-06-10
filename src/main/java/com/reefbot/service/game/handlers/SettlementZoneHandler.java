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
public class SettlementZoneHandler implements GameHandler {

    public static final String BTN_BACK = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "На центральной площади гудит народ.\nСлышно, как где-то кует кузнец.",
            "Дымок вьётся над хижинами.\nЗапах свежего хлеба носится в воздухе.",
            "Жители снуют по своим делам.\nВ таверне смеются — день удался.",
            "Дети гоняют деревянный обруч по дороге.\nПоселение живёт своей жизнью.",
            "С рынка доносится шум торга.\nОстров растёт, и поселение вместе с ним."
    );

    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SETTLEMENT;
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
        rt.bold("🏠 Поселение")
          .add("\n\n").add(flavor)
          .add("\n\n⚙️ Таверна, ежедневный бонус и крафт — в разработке.");
        ReplyKeyboard kb = KeyboardBuilder.builder().row(BTN_BACK).build();
        return rt.build(ZoneType.SETTLEMENT.getBannerPath(), kb);
    }
}

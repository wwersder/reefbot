package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.Fmt;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

@Component
@RequiredArgsConstructor
public class MyIslandHandler implements GameHandler {

    public static final String BTN_BACK = "◀️ На остров";

    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.MY_ISLAND;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (BTN_BACK.equals(text)) {
            player.getState().setCurrentScreen(PlayerScreen.MAIN);
            playerRepository.save(player);
            return MainMenuHandler.showMainMenu(player, island);
        }
        return buildScreen(player, island);
    }

    public static BotResponse buildScreen(Player player, Island island) {
        String stage = MainMenuHandler.stageFor(island.getDevPoints());
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("🏝 Остров «").append(island.getName()).append("»\n")
          .append(stage).append(" · ").append(Fmt.n(island.getDevPoints())).append(" ОР\n");

        // Resources
        sb.append("\n📦 Ресурсы\n")
          .append("🐟 Рыба: ").append(Fmt.n(island.getFish()))
          .append("  🐚 Ракушки: ").append(Fmt.n(island.getShells()))
          .append("  🪸 Коралл: ").append(Fmt.n(island.getCoral())).append("\n")
          .append("🪵 Дерево: ").append(Fmt.n(island.getWood()))
          .append("  🪨 Камень: ").append(Fmt.n(island.getStone())).append("\n")
          .append("Хранилище: ").append(Fmt.n(totalResources(island))).append("/").append(Fmt.n(island.getStorageCapacity()));

        // Undiscovered zones
        StringBuilder locked = new StringBuilder();
        boolean firstLocked = true;
        int dp = island.getDevPoints();
        for (ZoneType zone : ZoneType.values()) {
            if (zone.getMinDevPoints() > 0 && !zone.isUnlocked(dp)) {
                if (firstLocked) {
                    // First locked zone: full teaser
                    locked.append(zone.getDisplayName())
                          .append(" — 🔒 ").append(Fmt.n(zone.getMinDevPoints()))
                          .append(" ОР (сейчас: ").append(Fmt.n(dp)).append(")\n")
                          .append("   ").append(zoneTeaser(zone));
                    firstLocked = false;
                } else {
                    locked.append("\n").append(zone.getDisplayName())
                          .append(" — 🔒 ").append(Fmt.n(zone.getMinDevPoints())).append(" ОР");
                }
            }
        }
        if (locked.length() > 0) {
            sb.append("\n\n🗺 Неизведанные земли\n").append(locked);
        }

        ReplyKeyboard kb = KeyboardBuilder.builder().row(BTN_BACK).build();
        return new BotResponse(sb.toString(), null, kb);
    }

    private static int totalResources(Island island) {
        return island.getFish() + island.getShells() + island.getCoral()
                + island.getWood() + island.getStone();
    }

    private static String zoneTeaser(ZoneType zone) {
        return switch (zone) {
            case HILLS      -> "Каменистые склоны в тумане. Говорят, в глубине скрыты древние пещеры...";
            case PLAINS     -> "Бескрайние поля ждут первого урожая. Здесь можно построить настоящую ферму...";
            case PORT       -> "Старый маяк смотрит в море. Говорят, отсюда уходят в дальние экспедиции...";
            default         -> "";
        };
    }
}

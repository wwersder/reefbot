package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.DailyBonusService;
import com.reefbot.service.game.DailyBonusService.DailyBonusResult;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class SettlementZoneHandler implements GameHandler {

    public static final String BTN_BONUS   = "🎁 Ежедневный бонус";
    public static final String BTN_COLLECT = "✅ Забрать бонус";
    public static final String BTN_BACK    = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "На центральной площади гудит народ.\nСлышно, как где-то кует кузнец.",
            "Дымок вьётся над хижинами.\nЗапах свежего хлеба носится в воздухе.",
            "Жители снуют по своим делам.\nВ таверне смеются — день удался.",
            "Дети гоняют деревянный обруч по дороге.\nПоселение живёт своей жизнью.",
            "С рынка доносится шум торга.\nОстров растёт, и поселение вместе с ним."
    );

    private final DailyBonusService dailyBonusService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SETTLEMENT;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_BONUS   -> showBonusScreen(player, island);
            case BTN_COLLECT -> collectBonus(player, island);
            case BTN_BACK    -> goBack(player, island);
            default          -> buildZoneScreen(player, dailyBonusService);
        };
    }

    private BotResponse showBonusScreen(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SETTLEMENT_BONUS);
        playerRepository.save(player);
        return buildBonusScreen(player, island, dailyBonusService);
    }

    private BotResponse collectBonus(Player player, Island island) {
        if (!dailyBonusService.canClaim(player)) {
            return buildBonusScreen(player, island, dailyBonusService);
        }
        DailyBonusResult result = dailyBonusService.claim(player, island);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SETTLEMENT);
        playerRepository.save(player);

        BotResponse resultMsg  = buildClaimedMessage(result);
        BotResponse zoneScreen = buildZoneScreen(player, dailyBonusService);
        return resultMsg.withFollowUp(zoneScreen);
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);
        return MainMenuHandler.showMainMenu(player, island);
    }

    // ── Static builders ───────────────────────────────────────────────────────

    public static BotResponse buildZoneScreen(Player player, DailyBonusService dailyBonusService) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));
        RichText rt = new RichText();
        rt.bold("🏠 Поселение").add("\n\n").add(flavor).add("\n\n");

        boolean canClaim = dailyBonusService.canClaim(player);
        int streak = player.getState().getDailyStreak();
        if (streak > 0) {
            rt.bold("Серия:").add(" " + streak + " " + streakEmoji(streak) + "\n\n");
        }
        if (canClaim) {
            rt.bold("🎁 Ежедневный бонус").add(" — готов к получению!");
        } else {
            rt.add("Следующий бонус через " + dailyBonusService.hoursUntilNextClaim(player) + " ч.");
        }

        return rt.build(ZoneType.SETTLEMENT.getBannerPath(), zoneKeyboard(player, dailyBonusService));
    }

    private static ReplyKeyboard zoneKeyboard(Player player, DailyBonusService dailyBonusService) {
        KeyboardBuilder kb = KeyboardBuilder.builder();
        boolean canClaim = dailyBonusService.canClaim(player);
        KeyboardButton bonusBtn = new KeyboardButton(BTN_BONUS);
        if (canClaim) bonusBtn.setStyle("success");
        kb.row(bonusBtn);
        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }

    public static BotResponse buildBonusScreen(Player player, Island island, DailyBonusService dailyBonusService) {
        boolean canClaim = dailyBonusService.canClaim(player);
        int streak = player.getState().getDailyStreak();

        RichText rt = new RichText();
        rt.bold("🎁 Ежедневный бонус").add("\n\n");

        if (streak > 0) {
            rt.bold("Серия дней:").add(" " + streak + " " + streakEmoji(streak) + "\n\n");
        }

        if (canClaim) {
            double mult = 1.0 + streak * 0.35;
            int shells = (int) Math.round(10 * mult);
            int fish   = (int) Math.round(5  * mult);
            rt.add("Сегодня тебе полагается:\n")
              .bold("🐚 Ракушки: ×" + shells).add("\n")
              .bold("🐟 Рыба: ×" + fish).add("\n\n");
            if (streak >= 7) {
                rt.add("🌟 Максимальная серия! Так держать!");
            } else {
                rt.add("Заходи каждый день — серия увеличивает награду.");
            }
        } else {
            rt.add("Бонус уже получен сегодня.\n")
              .add("Следующий через ").bold(dailyBonusService.hoursUntilNextClaim(player) + " ч.").add("\n\n")
              .add("Не пропускай — серия сгорает через 48 ч.");
        }

        KeyboardBuilder kb = KeyboardBuilder.builder();
        if (canClaim) {
            KeyboardButton collect = new KeyboardButton(BTN_COLLECT);
            collect.setStyle("success");
            kb.row(collect);
        }
        kb.row(BTN_BACK);
        return rt.build(kb.build());
    }

    private static BotResponse buildClaimedMessage(DailyBonusResult result) {
        StringBuilder sb = new StringBuilder("🎉 <b>Бонус получен!</b>\n\n");
        sb.append("<b>🐚 Ракушки:</b> +").append(result.shellsGained()).append("\n");
        sb.append("<b>🐟 Рыба:</b> +").append(result.fishGained()).append("\n");
        sb.append("<b>Серия:</b> ").append(result.streak()).append(" дн. ").append(streakEmoji(result.streak()));
        if (result.streak() >= 7) sb.append("\n\n🌟 <b>Максимальная серия!</b>");
        return BotResponse.html(sb.toString());
    }

    private static String streakEmoji(int streak) {
        if (streak >= 7) return "🔥🔥🔥";
        if (streak >= 5) return "🔥🔥";
        if (streak >= 3) return "🔥";
        return "✨";
    }
}

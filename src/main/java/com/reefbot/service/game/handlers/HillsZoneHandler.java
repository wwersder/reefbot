package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.MineDepth;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.MineService;
import com.reefbot.service.game.MineService.MineResult;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import com.reefbot.util.XpBar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hills zone handler. Mine depth selection stays on ZONE_HILLS (no screen change needed).
 */
@Component
@RequiredArgsConstructor
public class HillsZoneHandler implements GameHandler {

    public static final String BTN_MINE         = "⛏ Шахта";
    public static final String BTN_COLLECT      = "✅ Забрать добычу";
    public static final String BTN_MINE_SHALLOW = "🔦 Поверхностный (~15 мин)";
    public static final String BTN_MINE_MEDIUM  = "⛏ Средний (~35 мин)";
    public static final String BTN_MINE_DEEP    = "💎 Глубокий (~70 мин)";
    public static final String BTN_BACK         = "◀️ На остров";
    public static final String BTN_MINE_BACK    = "◀️ Назад";

    private static final List<String> FLAVOR = List.of(
            "Каменистый склон уходит в туман.\nОткуда-то сверху доносится стук кирки.",
            "Скалы нависают над узкой тропой.\nГде-то в глубине капает вода.",
            "Ветер свистит в расщелинах скал.\nВпереди — темнота пещерного входа.",
            "Под ногами хрустят мелкие камни.\nГоризонт отсюда виден на несколько островов.",
            "Воздух здесь острее и холоднее.\nСлоистые скалы хранят старые секреты.",
            "Туман между утёсами пахнет мхом и сыростью.\nГде-то капает вода.",
            "Узкая тропа вьётся меж острых камней.\nСлышен отдалённый гул."
    );

    private final MineService mineService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_HILLS;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_MINE         -> enterMineSelection(player);
            case BTN_MINE_SHALLOW -> startMining(player, MineDepth.SHALLOW);
            case BTN_MINE_MEDIUM  -> startMining(player, MineDepth.MEDIUM);
            case BTN_MINE_DEEP    -> startMining(player, MineDepth.DEEP);
            case BTN_COLLECT      -> collectYield(player, island);
            case BTN_MINE_BACK    -> buildZoneScreen(player, mineService);
            case BTN_BACK         -> goBack(player, island);
            default               -> buildZoneScreen(player, mineService);
        };
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private BotResponse enterMineSelection(Player player) {
        if (!mineService.isIdle(player)) return buildZoneScreen(player, mineService);
        return buildMineMenu(player);
    }

    private BotResponse startMining(Player player, MineDepth depth) {
        if (!mineService.isIdle(player)) return buildZoneScreen(player, mineService);
        mineService.startMining(player, depth);
        return buildZoneScreen(player, mineService);
    }

    private BotResponse collectYield(Player player, Island island) {
        if (!mineService.isReady(player)) return buildZoneScreen(player, mineService);

        MineResult result    = mineService.collectYield(player, island);
        BotResponse zoneScreen = buildZoneScreen(player, mineService);
        BotResponse resultMsg  = buildResultMessage(result);

        // Chain: result → [event] → [level-up] → zone
        BotResponse tail = zoneScreen;
        if (result.leveledUp()) {
            tail = buildLevelUpMessage(result).withFollowUp(tail);
        }
        if (result.hasEvent()) {
            tail = buildEventMessage(result).withFollowUp(tail);
        }
        return resultMsg.withFollowUp(tail);
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);
        return MainMenuHandler.showMainMenu(player, island);
    }

    // ── Static builders ───────────────────────────────────────────────────────

    public static BotResponse buildZoneScreen(Player player, MineService mineService) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));
        RichText rt = new RichText();
        rt.bold("⛰ Холмы").add("\n\n").add(flavor).add("\n\n");

        int level  = player.getMine().getLevel();
        int xp     = player.getMine().getXp();
        int xpNext = mineService.xpForNextLevel(level);
        int xpPrev = mineService.xpForLevel(level);

        rt.bold("Шахтёр:").add(" " + MineService.levelName(level) + " (ур. " + level + ")\n");
        rt.code(XpBar.render(xp - xpPrev, xpNext - xpPrev > 0 ? xpNext - xpPrev : xpNext == 0 ? 0 : xpNext));
        rt.add("\n\n");

        appendMineStatus(rt, player, mineService);

        return rt.build(ZoneType.HILLS.getBannerPath(), zoneKeyboard(player, mineService));
    }

    private static void appendMineStatus(RichText rt, Player player, MineService mineService) {
        if (mineService.isActive(player)) {
            Integer depth = player.getMine().getDepth();
            MineDepth d = depth != null ? MineDepth.fromDepthValue(depth) : MineDepth.SHALLOW;
            rt.bold("⏳ " + d.getDisplayName() + ":").add(" ещё " + mineService.timeRemainingText(player));
        } else if (mineService.isReady(player)) {
            rt.bold("✅ Добыча ждёт!").add(" Нажми — забери.");
        } else {
            rt.add("Шахта свободна. Выбери глубину.");
        }
    }

    private static ReplyKeyboard zoneKeyboard(Player player, MineService mineService) {
        KeyboardBuilder kb = KeyboardBuilder.builder();
        if (mineService.isActive(player)) {
            kb.row(new KeyboardButton(BTN_BACK));
        } else if (mineService.isReady(player)) {
            KeyboardButton collect = new KeyboardButton(BTN_COLLECT);
            collect.setStyle("success");
            kb.row(collect);
            kb.row(new KeyboardButton(BTN_BACK));
        } else {
            kb.row(new KeyboardButton(BTN_MINE));
            kb.row(new KeyboardButton(BTN_BACK));
        }
        return kb.build();
    }

    private BotResponse buildMineMenu(Player player) {
        int level = player.getMine().getLevel();
        boolean mediumUnlocked = level >= 2;
        boolean deepUnlocked   = level >= 4;

        RichText rt = new RichText();
        rt.bold("⛏ Выбор глубины").add("\n\n");
        rt.bold("🔦 Поверхностный").add(" — ~15 мин\n   🪨 5–12 камня\n\n");

        if (mediumUnlocked) {
            rt.bold("⛏ Средний").add(" — ~35 мин\n   🪨 10–22 камня · 🪸 шанс коралла\n\n");
        } else {
            rt.bold("⛏ Средний").add(" — 🔒 открывается на ур. 2\n\n");
        }

        if (deepUnlocked) {
            rt.bold("💎 Глубокий").add(" — ~70 мин\n   🪨 18–35 камня · 🪸🪸 коралл чаще\n\n");
        } else {
            rt.bold("💎 Глубокий").add(" — 🔒 открывается на ур. 4\n\n");
        }

        rt.add("Чем глубже — тем дольше и богаче.");

        KeyboardBuilder kb = KeyboardBuilder.builder().row(BTN_MINE_SHALLOW);
        if (mediumUnlocked) kb.row(BTN_MINE_MEDIUM);
        if (deepUnlocked)   kb.row(BTN_MINE_DEEP);
        kb.row(BTN_MINE_BACK);
        return rt.build(ZoneType.HILLS.getBannerPath(), kb.build());
    }

    // ── Message builders ──────────────────────────────────────────────────────

    private static BotResponse buildResultMessage(MineResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("⛰ <b>Шахта пройдена</b>\n\n");
        sb.append("<i>").append(result.narrative()).append("</i>\n\n");
        sb.append("Глубина: ").append(result.depth().getDisplayName()).append("\n");

        if (result.stoneGained() > 0) {
            sb.append("🪨 Камень: <b>+").append(result.stoneGained()).append("</b>\n");
        }
        if (result.coralGained() > 0) {
            sb.append("🪸 Коралл: <b>+").append(result.coralGained()).append("</b>\n");
        }
        sb.append("⭐ Опыт: <b>+").append(result.xpEarned()).append("</b>");

        return BotResponse.html(sb.toString());
    }

    private static BotResponse buildEventMessage(MineResult result) {
        return BotResponse.html("🎲 <b>Событие в шахте</b>\n\n" + result.event().text());
    }

    private static BotResponse buildLevelUpMessage(MineResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎉 <b>Новый уровень шахтёра!</b>\n\n");
        sb.append("Теперь ты — <b>").append(MineService.levelName(result.newLevel())).append("</b>");
        sb.append(" (ур. ").append(result.newLevel()).append(")");
        String unlock = MineService.levelUnlockText(result.newLevel());
        if (unlock != null) sb.append("\n\n✨ ").append(unlock);
        return BotResponse.html(sb.toString());
    }
}

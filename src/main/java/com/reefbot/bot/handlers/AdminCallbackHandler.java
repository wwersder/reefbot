package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.entity.Player;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Инлайн-справка по админ-командам.
 * Prefix: "admin"
 *
 * <p>Payload-ы:
 * <ul>
 *   <li>{@code admin:menu}      — главное меню (список секций)</li>
 *   <li>{@code admin:give}      — /give подробности</li>
 *   <li>{@code admin:del}       — /del подробности</li>
 *   <li>{@code admin:tide}      — /tide подробности</li>
 *   <li>{@code admin:buildings} — /buildings подробности</li>
 *   <li>{@code admin:speedup}   — /speedup подробности</li>
 *   <li>{@code admin:produce}   — /produce подробности</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminCallbackHandler implements CallbackHandler {

    private final TelegramClient telegramClient;

    @Override
    public String getPrefix() {
        return "admin";
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        String text = switch (payload) {
            case "give"      -> textGive();
            case "del"       -> textDel();
            case "tide"      -> textTide();
            case "buildings" -> textBuildings();
            case "speedup"   -> textSpeedup();
            case "produce"   -> textProduce();
            default          -> textMenu();
        };

        InlineKeyboardMarkup kb = payload.equals("menu")
                ? menuKeyboard()
                : backKeyboard();

        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(kb)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit admin help message", e);
        }
    }

    // ── Тексты ────────────────────────────────────────────────────────────

    public static String textMenu() {
        return """
                🛠 <b>Админ-панель ReefBot</b>

                Выбери категорию команд:

                👤  <b>Игроки</b> — удалить, выдать ресурсы и предметы
                🌊  <b>Прилив</b> — принудительно активировать
                🏗  <b>Здания</b> — статус, ускорение, производство

                Все ID — внутренние (БД), не Telegram.""";
    }

    private static String textGive() {
        return """
                💰 <b>/give — выдать или списать</b>

                <code>/give &lt;id&gt; &lt;ресурс&gt; &lt;кол&gt;</code>

                ━━━━━━━━━━━━━━━━
                📦 <b>Ресурсы острова</b>

                🐟 <code>fish</code>   — рыба, основная валюта
                🐚 <code>shells</code> — ракушки, из прилива и пляжа
                🪵 <code>wood</code>   — дерево, нужно для строительства
                🪨 <code>stone</code>  — камень
                🪸 <code>coral</code>  — коралл
                ⭐ <code>dp</code>     — очки разработчика
                ⚡ <code>xp</code>     — XP рыбака (уровень пересчитывается)

                ━━━━━━━━━━━━━━━━
                🎒 <b>Предметы в рюкзак</b>

                📜 <code>scroll</code> — Свиток ускорения
                                 следующий заброс −50% времени
                🪱 <code>bait</code>   — Морская наживка
                                 следующий улов +50% рыбы
                🪝 <code>hook</code>   — Старый крюк
                                 +40 XP к следующему улову
                🫙 <code>vial</code>   — Склянка прилива
                                 мгновенно завершает рыбалку

                ━━━━━━━━━━━━━━━━
                Количество может быть <b>отрицательным</b> — для списания.

                <b>Примеры:</b>
                <code>/give 3 fish 100</code>
                <code>/give 3 vial 2</code>
                <code>/give 3 xp -500</code>""";
    }

    private static String textDel() {
        return """
                🗑 <b>/del — удалить игрока</b>

                <code>/del &lt;id&gt;</code>

                Полностью удаляет игрока и все связанные данные:
                остров, рыбалку, прилив, инвентарь, здания.

                ⚠️ <b>Необратимо.</b>

                <b>Пример:</b>
                <code>/del 7</code>""";
    }

    private static String textTide() {
        return """
                🌊 <b>/tide — активировать прилив</b>

                <code>/tide &lt;id&gt;</code>

                Принудительно запускает прилив прямо сейчас.
                Окно открыто 40 минут, затем закрывается стандартно.

                Полезно для тестирования мини-игры прилива
                без ожидания 5–10 часов.

                <b>Пример:</b>
                <code>/tide 3</code>""";
    }

    private static String textBuildings() {
        return """
                🏗 <b>/buildings — статус зданий</b>

                <code>/buildings &lt;id&gt;</code>

                Показывает все постройки игрока с их статусами:

                🔨 <b>строится</b>   — идёт строительство, время до конца
                ✅ <b>готово</b>     — построено, ждёт финализации
                🏠 <b>работает</b>  — уровень, производство в час,
                                  накоплено / потолок

                <b>Пример:</b>
                <code>/buildings 3</code>""";
    }

    private static String textSpeedup() {
        return """
                ⚡ <b>/speedup — ускорить строительство</b>

                <code>/speedup &lt;id&gt; [BUILDING_TYPE]</code>

                Переводит время окончания на <b>сейчас + 10 секунд</b>.
                Если тип не указан — ускоряет <b>все</b> активные стройки.

                Доступные типы: <code>FISHING_PIER</code>

                <b>Примеры:</b>
                <code>/speedup 3</code>            — все стройки
                <code>/speedup 3 FISHING_PIER</code>""";
    }

    private static String textProduce() {
        return """
                📦 <b>/produce — добавить накопленное производство</b>

                <code>/produce &lt;id&gt; &lt;BUILDING_TYPE&gt; &lt;кол&gt;</code>

                Откатывает <code>productionCollectedAt</code> назад так,
                чтобы здание «накопило» указанное количество.

                Ограничено потолком здания — больше потолка не войдёт.

                Доступные типы: <code>FISHING_PIER</code>

                <b>Пример:</b>
                <code>/produce 3 FISHING_PIER 20</code>
                → помост будет показывать 20 🐟 готово к сбору""";
    }

    // ── Клавиатуры ────────────────────────────────────────────────────────

    public static InlineKeyboardMarkup menuKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        btn("💰 /give", "admin:give"),
                        btn("🗑 /del", "admin:del")
                ))
                .keyboardRow(new InlineKeyboardRow(
                        btn("🌊 /tide", "admin:tide")
                ))
                .keyboardRow(new InlineKeyboardRow(
                        btn("🏗 /buildings", "admin:buildings"),
                        btn("⚡ /speedup", "admin:speedup"),
                        btn("📦 /produce", "admin:produce")
                ))
                .build();
    }

    private static InlineKeyboardMarkup backKeyboard() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        btn("◀️ Назад", "admin:menu")
                ))
                .build();
    }

    private static InlineKeyboardButton btn(String text, String data) {
        return InlineKeyboardButton.builder()
                .text(text)
                .callbackData(data)
                .build();
    }
}

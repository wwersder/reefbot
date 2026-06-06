package com.reefbot.service.registration;

import com.reefbot.dto.BotResponse;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.service.IslandService;
import com.reefbot.service.PlayerService;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import com.reefbot.entity.Player;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OnboardingService1 {

    private final PlayerService playerService;
    private final IslandService islandService;

    public Player startOnboarding(Long telegramId) {
        Player player = playerService.getOrCreatePlayer(telegramId);

        if (player.getOnboardingStep() == null) {
            player.setOnboardingStep(OnboardingStep.WELCOME);
            return playerService.save(player);
        }

        return player;
    }

    public void nextStep(Player player) {
        switch (player.getOnboardingStep()) {
            case WELCOME -> player.setOnboardingStep(OnboardingStep.CLAIM_ISLAND);
            case CLAIM_ISLAND -> player.setOnboardingStep(OnboardingStep.ISLAND_OVERVIEW);
            case ISLAND_OVERVIEW -> player.setOnboardingStep(OnboardingStep.ENTER_ISLAND_NAME);
            case ENTER_ISLAND_NAME -> player.setOnboardingStep(OnboardingStep.RECEIVE_STARTER_PACK);
            case RECEIVE_STARTER_PACK -> player.setOnboardingStep(OnboardingStep.BUILD_FIRST_CAMP);
            case BUILD_FIRST_CAMP -> player.setOnboardingStep(OnboardingStep.START_FIRST_QUEST);
            case START_FIRST_QUEST -> player.setOnboardingStep(OnboardingStep.FINISHED);
            case FINISHED -> {
                // do nothing
            }
        }
    }

    public String getCurrentText(Player player) {
        return switch (player.getOnboardingStep()) {
            case WELCOME ->
                    """
                    🏝 Добро пожаловать в архипелаг.
    
                    Много лет эти острова оставались забытыми.
    
                    Сегодня один из них станет твоим.
                    """;
            case CLAIM_ISLAND ->
                    """
                    🏝 Перед тобой десятки островов.
    
                    Но свободным остался только один.
    
                    Небольшой.
                    Дикий.
                    Неосвоенный.
    
                    Но теперь он принадлежит тебе.
                    """;
            case ISLAND_OVERVIEW ->
                    """
                    После короткого осмотра становится понятно:
    
                    На острове есть всё необходимое для начала.
    
                    Но придётся много работать.
                    """;
            default -> "";
        };
    }

    public boolean isPlayerOnboarding(Long telegramId) {
        Player player = playerService.getOrCreatePlayer(telegramId);
        return player.getOnboardingStep() != null && player.getOnboardingStep() != OnboardingStep.FINISHED;
    }

    public BotResponse processMessage(Long telegramId, String message) {
        Player player = playerService.getOrCreatePlayer(telegramId);

        if (player.getOnboardingStep() == OnboardingStep.FINISHED) {
            return new BotResponse("разработка..", null, null);
        }

        if (player.getOnboardingStep() == OnboardingStep.WELCOME) {
            nextStep(player);
            playerService.save(player);
            String text = "🌴 Где-то среди бескрайнего архипелага находится небольшой остров.\n\nНа картах он ничем не примечателен.\nНа нём нет богатств и величественных построек.\n\nПока что.\n\n⚡️ С этого дня остров принадлежит тебе.";

            List<MessageEntity> entities = List.of(
                    MessageEntity.builder()
                            .type("custom_emoji")
                            .offset(0)
                            .length(2)
                            .customEmojiId("5807538646030489502")
                            .build(),
                    MessageEntity.builder()
                            .type("custom_emoji")
                            .offset(text.indexOf("⚡️"))
                            .length(2)
                            .customEmojiId("5807485199457458084")
                            .build()
            );

            return new BotResponse(
                    text,
                    "/Users/akhivyk/Documents/my/reefbot/img/reg/reg1.png",
                    KeyboardBuilder.builder().row("Продолжить").oneTime(true).build(),
                    entities
            );
        }

        String text = "🏝 Владелец острова назначен.\n\nПричал подготовлен.\nПрипасы доставлены.\nКоманда ожидает начала работ.\n\n⏳ Подготовка острова продолжается…\n\n📰 Новые записи журнала публикуются в @reefbotnews";

        List<MessageEntity> entities = List.of(
                MessageEntity.builder()
                        .type("custom_emoji")
                        .offset(0)
                        .length(2)
                        .customEmojiId("5390809183459222083")
                        .build(),
                MessageEntity.builder()
                        .type("custom_emoji")
                        .offset(102)
                        .length(1)
                        .customEmojiId("5451732530048802485")
                        .build(),
                MessageEntity.builder()
                        .type("custom_emoji")
                        .offset(138)
                        .length(2)
                        .customEmojiId("5429332991404952140")
                        .build()
        );

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text("📰 Следить за новостями")
                                .url("https://t.me/reefbotnews")
                                .style("success")
                                .build()
                ))
                .build();

        return new BotResponse(text, null, keyboard, entities);
    }

//    private String getImageForStep(OnboardingStep step) {
//        // TODO: add proper switch
//    }

}

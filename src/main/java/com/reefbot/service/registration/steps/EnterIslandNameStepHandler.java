package com.reefbot.service.registration.steps;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.service.IslandService;
import com.reefbot.service.PlayerService;
import com.reefbot.service.registration.OnboardingStepHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import static com.reefbot.util.EmojiUtil.e;
import static com.reefbot.util.EmojiUtil.entities;

@Component
@RequiredArgsConstructor
public class EnterIslandNameStepHandler implements OnboardingStepHandler {

    private final PlayerService playerService;
    private final IslandService islandService;

    private static final String TEXT = """
            🏝 Название выбрано.
            
            Остров «%s» официально зарегистрирован и нанесён на карты архипелага.
            
            С этого момента начинается его история.""";

    @Override
    public OnboardingStep getStep() {
        return OnboardingStep.ENTER_ISLAND_NAME;
    }

    @Override
    public BotResponse process(Player player, String message) {
        if (!StringUtils.hasText(message) || message.startsWith("/")) {
            return new BotResponse("Введите название острова. Допустимая длина: от 2 до 50 символов.", null, null);
        }

        String islandName = message.trim();
        if (islandName.length() < 2 || islandName.length() > 50) {
            return new BotResponse("Название острова должно содержать от 2 до 50 символов.", null, null);
        }

        if (player.getIsland() == null) {
            islandService.createIsland(player, islandName);
        } else {
            islandService.renameIsland(player.getIsland(), islandName);
        }

        player.setOnboardingStep(OnboardingStep.RECEIVE_STARTER_PACK);
        playerService.save(player);

        String formatted = TEXT.formatted(islandName);
        return new BotResponse(
                formatted,
                null,
                KeyboardBuilder.builder().row("Далее").oneTime(true).build(),
                entities(formatted, e("🏝", "5807538646030489502"))
        );
    }
}

package com.reefbot;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.registration.OnboardingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ReefbotApplicationTests {

    @Autowired
    private OnboardingService onboardingService;

    @Autowired
    private PlayerRepository playerRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void onboardingFlowMatchesSpecification() {
        Long telegramId = 101L;
        String username = "player_one";

        BotResponse welcome = onboardingService.processMessage(telegramId, username, "/start");
        assertThat(welcome.photoPath()).isEqualTo("img/reg/reg1.png");
        assertThat(welcome.text()).contains("Добро пожаловать в архипелаг");

        BotResponse claimIsland = onboardingService.processMessage(telegramId, username, "Продолжить");
        assertThat(claimIsland.photoPath()).isNull();
        assertThat(claimIsland.text()).contains("свободным остался только один");

        BotResponse overview = onboardingService.processMessage(telegramId, username, "Осмотреть остров");
        assertThat(overview.photoPath()).isEqualTo("img/reg/reg2.png");
        assertThat(overview.text()).contains("На острове есть всё необходимое для начала");

        BotResponse askName = onboardingService.processMessage(telegramId, username, "Продолжить");
        assertThat(askName.text()).isEqualTo("Необходимо назвать остров. Введите название:");

        BotResponse namedIsland = onboardingService.processMessage(telegramId, username, "Sunrise");
        assertThat(namedIsland.text()).contains("Остров \"Sunrise\"");
        assertThat(namedIsland.text()).contains("зарегистрирован на карте архипелага");

        BotResponse starterPack = onboardingService.processMessage(telegramId, username, "Далее");
        assertThat(starterPack.text()).contains("🎁 Стартовые ресурсы");
        assertThat(starterPack.text()).contains("🪵 Древесина x20");
        assertThat(starterPack.text()).contains("🪨 Камень x10");
        assertThat(starterPack.text()).contains("🎣 Простая удочка");

        Player player = playerRepository.findByTelegramId(telegramId).orElseThrow();
        Island island = player.getIsland();

        assertThat(player.getOnboardingStep()).isEqualTo(OnboardingStep.FINISHED);
        assertThat(island).isNotNull();
        assertThat(island.getName()).isEqualTo("Sunrise");
    }
}

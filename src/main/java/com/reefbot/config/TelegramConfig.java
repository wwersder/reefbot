package com.reefbot.config;

import com.reefbot.bot.ReefBot;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
@RequiredArgsConstructor
public class TelegramConfig {

    private final TelegramProperties properties;

    @Bean
    public TelegramClient telegramClient() {
        return new OkHttpTelegramClient(properties.getToken());
    }

    @Bean
    public TelegramBotsLongPollingApplication telegramApplication(ReefBot reefBot) throws Exception {
        TelegramBotsLongPollingApplication application = new TelegramBotsLongPollingApplication();
        application.registerBot(properties.getToken(), reefBot);
        return application;
    }
}

package com.reefbot.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "telegram.bot")
public class TelegramProperties {

    private String token;
    private String username;

    @PostConstruct
    public void init() {
        System.out.println("Currently used profile: " + username);
    }


}

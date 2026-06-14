package com.reefbot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "reefbot.support")
public class SupportProperties {

    /** Telegram chat ID of the support group. */
    private Long groupChatId;

    /** Base URL for admin panel links in bot messages. */
    private String adminUrl = "https://reefbot.online/admin";
}

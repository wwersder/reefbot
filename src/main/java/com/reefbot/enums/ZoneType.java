package com.reefbot.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Зоны острова — единый источник правды: название, эмодзи, порог ОР, баннер.
 * Плацдарм под картинки: bannerPath по конвенции img/zones/{zone}_{stage}.png,
 * пока null — уходит обычный текст (photoPath поддержан в BotResponse).
 */
@Getter
@RequiredArgsConstructor
public enum ZoneType {

    FOREST("🌲 Лес", "🌲", 0, null),
    SHORE("🏖 Берег", "🏖", 0, null),
    SETTLEMENT("🏠 Поселение", "🏠", 0, null),
    HILLS("⛰ Холмы", "⛰", 10, null),
    PLAINS("🌾 Равнина", "🌾", 20, null),
    PORT("⚓ Порт", "⚓", 35, null);

    /** Название зоны = текст кнопки в главном меню. */
    private final String displayName;
    private final String emoji;
    /** Порог ОР для разблокировки (0 = открыта сразу). */
    private final int minDevPoints;
    /** Путь к баннеру зоны; null = без картинки. */
    private final String bannerPath;

    public boolean isUnlocked(int devPoints) {
        return devPoints >= minDevPoints;
    }
}

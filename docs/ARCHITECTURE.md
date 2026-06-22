# ReefBot — архитектура кода

> Детальный справочник. Краткий индекс — в корневом `CLAUDE.md`.

## Структура файлов

```
src/main/java/com/reefbot/
├── bot/
│   ├── ReefBot.java                    # LongPolling consumer, роутит callbacks
│   ├── CallbackDispatcher.java         # prefix:payload → CallbackHandler
│   ├── CallbackHandler.java            # интерфейс
│   └── handlers/
│       ├── LevelsCallbackHandler.java  # "levels:N" → показ уровня, инлайн ◀️▶️
│       ├── BonusCallbackHandler.java   # устаревший, dead code
│       └── CastCallbackHandler.java    # устаревший, dead code
├── dto/
│   └── BotResponse.java                # record(text, photoPath, keyboard, entities, followUp, parseMode)
├── entity/
│   ├── Player.java                     # @OneToOne: PlayerState, PlayerFishing
│   ├── PlayerFishing.java              # level, xp, spot, finishAt, notified
│   ├── PlayerState.java                # currentScreen, onboardingStep
│   ├── Island.java                     # ресурсы острова, devPoints
│   └── InventoryItem.java
├── enums/
│   ├── PlayerScreen.java               # MAIN, ZONE_SHORE, FISHING_*, NPC_ENCOUNTER
│   ├── ZoneType.java                   # 6 зон: displayName, emoji, minDevPoints, bannerPath
│   ├── FishingSpot.java                # SHORE, REEF, OPEN_SEA (minLevel, duration, fish, xp, bonus)
│   └── ResourceType.java               # FISH, SHELLS, CORAL, WOOD, STONE
├── service/
│   ├── NotificationScheduler.java      # @Scheduled(30s), @Transactional, fishingNotified
│   ├── MessageDispatcher.java          # admin → onboarding/game по PlayerStatus
│   └── game/
│       ├── GameService.java            # screen → GameHandler (auto-map через Spring DI)
│       ├── FishingService.java         # startFishing, collectFish, уровни, XP
│       ├── FishingResult.java          # record результата улова
│       └── handlers/                   # 1 экран = 1 handler
│           ├── MainMenuHandler.java    # дайджест + кнопки зон
│           ├── ShoreZoneHandler.java   # экран зоны Берег, routeFishing
│           ├── FishingMenuHandler.java
│           ├── FishingActiveHandler.java
│           ├── FishingResultHandler.java
│           └── FishingBonusesHandler.java
└── util/
    └── KeyboardBuilder.java            # builder Reply-клавиатур
```

## Ключевые паттерны

### Роутинг сообщений
`MessageDispatcher` → по `PlayerStatus`: ONBOARDING → `OnboardingService`, ACTIVE → `GameService`.
`GameService` держит `Map<PlayerScreen, GameHandler>`, собирается автоматически из всех
`@Component implements GameHandler` (дубликат экрана = IllegalStateException на старте).

### GameHandler
```java
public interface GameHandler {
    PlayerScreen getScreen();
    BotResponse handle(Player player, Island island, String text);
}
```
Каждый handler: константы кнопок `BTN_*` + switch по тексту. Переход на другой экран:
`player.getState().setCurrentScreen(...)` + `playerRepository.save(player)` + вернуть экран назначения.
Экраны, куда «приземляют» из других handler'ов, строятся статическим `buildXxxScreen(player)`.

### BotResponse
```java
public record BotResponse(String text, String photoPath, ReplyKeyboard keyboard,
        List<MessageEntity> entities, BotResponse followUp, String parseMode) {
    public static BotResponse html(String text) { ... }          // parseMode=HTML
    public static BotResponse html(String text, ReplyKeyboard keyboard) { ... }
    public BotResponse withFollowUp(BotResponse next) { ... }    // цепочка сообщений
}
```
Level-up: `catchMessage.withFollowUp(levelUpMsg.withFollowUp(zoneScreen))`, level-up текст — HTML bold.

### Навигация (зоны)
`MAIN → ZONE_* → активность`. Правила: глубина ≤ 3, `[◀️ Назад]`/`[◀️ На остров]` — на уровень выше,
после сбора награды игрок приземляется на экран зоны. Кнопка зоны/активности зеленеет при готовой
награде: `KeyboardButton.setStyle("success")` (Bot API 9.4+). Детали: `docs/GDD_navigation_zones.md`.

### Нормализация БД — правило для players
`players` хранит только: идентификаторы (`telegramId`, `username`), статус/онбординг, флаги поддержки,
и **cross-game** данные (VIP — потому что он глобальный для всех игр).

**Любое игровое состояние конкретной мини-игры — в отдельную таблицу:**
| Игра            | Entity            | Таблица             |
|-----------------|-------------------|---------------------|
| Рыбалка         | `PlayerFishing`   | `player_fishing`    |
| Прилив          | `PlayerTide`      | `player_tide`       |
| Slot (ReefHouse)| `PlayerSlotState` | `player_slot_state` |
| Plinko          | *(пока в players)*| — |
| Новая игра      | `PlayerXxxState`  | `player_xxx_state`  |

Plinko-поля (`plinkoDailyLost`, `plinkoDailyDate`, `plinkoLastPlay`) — 3 поля, стабильны, допустимо
оставить в players. При росте (≥ 4–5 полей) — вынести по той же схеме.

Добавляя поля в `Player.java`: если это состояние мини-игры — создай отдельный entity.
`@OneToOne(mappedBy = "player", cascade = ALL, fetch = EAGER, orphanRemoval = true)`.

### JPA / транзакции
- Cascade save: `playerRepository.save(player)` сохраняет PlayerState/PlayerFishing/PlayerSlotState каскадом
- Из `@Scheduled` нужен явный `repository.save(entity)` + `@Transactional`

### TelegramBots 10.0.0
- Inline-клавиатуры: `InlineKeyboardRow`, не `List<InlineKeyboardButton>`
- Reply-клавиатуры: `util/KeyboardBuilder`

## Экраны (PlayerScreen)
`MAIN → ZONE_SHORE → FISHING_MENU → FISHING_ACTIVE → FISHING_RESULT → FISHING_BONUSES`

## Миграции БД (Flyway)
V1 players, V2 islands, V3 remove_claim_island_step, V4 player_status, V5 resources_to_islands,
V6 fishing_fields, V7 fishing_notified, V8 player_state, V9 player_fishing, V10 inventory.
Новая миграция = `V{max+1}__name.sql` в `src/main/resources/db/migration`.

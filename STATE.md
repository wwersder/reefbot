# ReefBot — STATE.md
> Живой документ. Обновлять в конце каждой сессии.
> Последнее обновление: 2026-06-22

---

## Проект

Idle-игра в Telegram: развитие острова в архипелаге.
Бот: `@reefbot_bot` | Канал: `@reefbot_news` | Лендинг: https://reefbot.online

**Стек:** Java 17, Spring Boot 3.5, TelegramBots 10.0.0, MySQL, Flyway, Lombok, Maven  
**Деплой:** GitHub Actions → `dev` (авто) → `main` (прод)  
**VPS:** `13.140.153.168` | **Код:** `C:\my\reefbot` | **IDE:** IntelliJ  
**Сборка:** проверять через IntelliJ (`mvn compile`), в bash-сандбоксе mvn недоступен

---

## Текущие миграции (max = V26)

| Версия | Описание |
|--------|----------|
| V1–V10 | Базовые таблицы: players, islands, fishing, state, inventory |
| V11–V18 | Прилив, эффекты предметов, здания, tide_roll_pending |
| V19–V20 | Система поддержки |
| V21 | Plinko: plinko_logs, поля в players |
| V22 | VIP-система: поля в players |
| V23 | Слот: slot_logs, поля free_spins/multiplier в players |
| V24 | Sticky wilds в players |
| V25 | slot_fs_pending_win в players |
| V26 | **Нормализация:** создан `player_slot_state`, поля слота вынесены из players |

**Следующая миграция: V27**

---

## Что реализовано

### Бот / игровой контур
- Онбординг: Welcome → имя острова → стартовый пак
- Рыбалка: 3 места (SHORE/REEF/OPEN_SEA), 10 уровней, бонусы, уведомления, `/levels`
- Навигация: `MAIN → ZONE_SHORE → FISHING_*` + дайджест в главном меню
- `ZoneType`: 6 зон с порогами ОР (лес, поселение, холмы, равнина, порт — заглушки)
- Прилив (TideService + TideGameHandler) — реализован
- Строительство (BuildingService + ShoreBuildingsHandler + ShorePierHandler) — реализовано для берега
- Система поддержки: тикеты, роутинг в группу, веб-панель (частично)
- VIP-система: тиры NONE/CORAL/PEARL/REEF, кэшбэк, VipService, VipScheduler

### Mini Apps
**Reef Plinko** (`/mini/plinko/`):
- Полноценная игра: 8/12 рядов, 3 риска, pesимistik lock, лидерборд
- VIP-карточка, анимация баланса, haptic, авто-спин, джекпот-флэш

**The Reef House** (`/mini/slot/`) — текущий фокус разработки:
- 5×3 слот, 9 линий, Wild (🌊), Scatter (🏺)
- Sticky wilds, Dog House mechanic: ×2/×3 per wild, per-payline SUM multiplier
- Free Spins: 7/12/20 в зависимости от числа скаттеров (Dog House стандарт)
- Покупка бонуса (100× ставку)
- Авто-spin (100 спинов), Turbo-режим
- FS авто-play при reopening приложения
- FS pending win: накапливается во время бонуса, выдаётся одной суммой в конце
- FS summary popup с count-up анимацией и иксом от ставки
- Sticky wilds overlay (float выше blur через `#sticky-overlay`)
- VIP-карточка, анимация баланса, haptic

---

## Ключевые архитектурные решения (принятые)

### Нормализация БД
`players` хранит только: идентификаторы, статус/онбординг, флаги поддержки, cross-game VIP.  
Игровое состояние → отдельная таблица:

| Игра | Entity | Таблица |
|------|--------|---------|
| Рыбалка | `PlayerFishing` | `player_fishing` |
| Прилив | `PlayerTide` | `player_tide` |
| Slot | `PlayerSlotState` | `player_slot_state` |
| Plinko | *(3 поля в players — допустимо)* | — |
| Новая игра | `PlayerXxxState` | `player_xxx_state` |

**Правило:** при добавлении полей в `Player.java` — если это состояние мини-игры, создавать отдельный entity.  
`@OneToOne(mappedBy="player", cascade=ALL, fetch=EAGER, orphanRemoval=true)`  
Lazy-create через хелпер `ss(player)` в SlotService.

### Форматирование чисел
- **Java:** `Fmt.n(value)` из `com.reefbot.util.Fmt` — немецкая локаль, точки как разделитель тысяч: `1.048.047`
- **JS:** `value.toLocaleString('de-DE')` везде где числа показываются игроку

### Слот — конвенции
- Scatter не может дублироваться вертикально на одном барабане (max 1 per column)
- FS win-bar: всегда показывает накопленный итог `💰 350 🐚`; при выигрыше переключается на сумму спина, через 1.8s возвращается
- Баланс не анимируется во время FS (win накапливается); анимируется при нажатии «Забрать»
- Саммари popup задерживается до конца подсветки сыгровок последнего спина

---

## Что сделано в сессии (2026-06-22, продолжение)

1. **FS авто-play при reopening** — `init()` запускает `startFsAuto()` если `freeSpinsRemaining > 0`
2. **FS pending win** — V25+V26 миграции, `PlayerSlotState` entity, `fsPendingWin` поле
3. **Нормализация players** — V26: `player_slot_state` таблица, поля слота убраны из `players`
4. **ARCHITECTURE.md** — добавлено правило нормализации БД
5. **Слот UI — 4 улучшения:**
   - Кнопка `■` во время спина и FS авто (вместо текста)
   - Win-bar держит накопленный итог бонуса между спинами
   - При выигрыше — показывает сумму спина 1.8s, потом возвращает итог
   - Sticky wilds рендерятся в `#sticky-overlay` (выше blur)
6. **Антиспойлер** — одинаковая задержка 400ms для win и no-win убрана (теперь: win-bar всегда показывает итог, меняется только при выигрыше → нет асимметрии)
7. **Форматирование чисел** — `Fmt.java` + `toLocaleString('de-DE')` во всём проекте
8. **Подсветка сыгровок** — последовательная (300ms между линиями), саммари ждёт конца подсветки

---

### Текущая сессия (2026-06-22 #2)

9. **RTP балансировка v5** (заменена v6)
   - E[FS] = 206× — SUM мультиплаеров, слишком высокая цена бонуски (220×)

10. **RTP балансировка v6 — Dog House механика** — верифицировано симуляцией. Base ~75.3%, total ~96.2%
    - **Изменения механики:**
      - Вайлд-мультиплаеры: ×1/×2/×3/×5 → **×2/×3 (50/50)**
      - Применение мультиплаера: глобальный SUM → **пер-линейный SUM** (только вайлды в выигрышной цепочке)
      - Free Spins: 10/15/20 → **7/12/20** (Dog House стандарт)
    - **Симуляция (1M FS сессий):**
      - E[FS] = **67.71×** ставку
      - Медиана: 14.7×, P75: 56.6×, P90: 174×, P99: 783×
      - Cap 2500× срабатывает в только **0.06%** сессий (vs 0.87% в v5)
    - **Текущие параметры (v6):**
      - Scatter: **0.023** → триггер ~1/360 (каждые ~54мин при 400/ч), вес от FISH_CLOWN (0.200→0.187)
      - Символьные выплаты: без изменений (Акула 22/65/200, Кальмар 11.5/33/80, ...)
      - **BONUS_BUY_MULTIPLIER: 70×** (E[FS]=67.71×, buy RTP=96.7% — на уровне Dog House ~67×)
      - MAX_WIN_MULTIPLIER: 2500× за FS-сессию
10. **Паблтейбл переработана** — slot/index.html v=4: «Как играть» (5×3=9), Wild-пример с emoji, Scatter-таблица 3/4/5, новая сетка выплат с заголовками ×3/×4/×5, 9 SVG-диаграмм линий (генерируются скриптом), Free Spins по шагам (прилипание → множители → пример). CSS: slot.css v=4
11. **rtp-simulator.html** — браузерный симулятор с интерактивными слайдерами pay3, 3 пресета (Оригинал/Умеренно/Агрессивно), живой аналитический RTP, кнопка симуляции

---

## В работе / открытые вопросы

### Слот (ближайшие)
- [ ] **Admin-команда** `/grantbonus <telegram_id> <scatter_count>` — обсуждалась, отложена
- [x] Версионирование JS — обновлено до v=4

### Игровой контур
- [ ] Зоны 2–6: лес, поселение, холмы, равнина, порт — только заглушки
- [ ] Ежедневный бонус + кубик
- [ ] Инвентарь: таблица есть (`inventory_items`), логика не реализована
- [ ] Экспедиции
- [ ] Жемчуг (отдельная валюта?)

### Технический долг
- [ ] Веб-панель поддержки (`AdminController`) — частично реализована
- [ ] Plinko: 3 поля в `players` — при росте вынести в `PlayerPlinkoState`
- [ ] `_maxMult` в slot-ui.js — переменная объявлена но не используется для отображения (мёртвый код)

---

## Структура ключевых файлов (слот)

```
src/main/
├── java/com/reefbot/
│   ├── entity/PlayerSlotState.java          # freeSpinsRemaining, multiplier, stickyWildsJson, fsPendingWin
│   ├── repository/PlayerSlotStateRepository.java
│   ├── service/slot/SlotService.java        # ss(player) хелпер, generateGrid(), логика FS
│   ├── service/slot/SlotBotService.java     # /slot команда в боте
│   ├── dto/slot/SlotSpinResponse.java       # fsPendingWin поле
│   ├── dto/slot/SlotStateResponse.java
│   └── util/Fmt.java                        # Fmt.n(int/long) → "1.048.047"
└── resources/
    ├── db/migration/V26__player_slot_state.sql
    └── static/mini/slot/
        ├── index.html    (v=4)
        ├── slot.css      (v=4)
        ├── slot-ui.js    (v=4)  ← основная логика UI
        └── slot-api.js   (v=2)
```

---

## Конвенции проекта

- **1 экран = 1 `GameHandler`** (`@Component`), роутинг через `GameService`
- **Переход экрана:** `setCurrentScreen` + `playerRepository.save(player)` + вернуть экран
- **Cascade save:** `playerRepository.save(player)` тянет все `@OneToOne(cascade=ALL)`
- **`@Scheduled`:** явный `repository.save()` + `@Transactional`
- **Зелёная кнопка:** `setStyle("success")` при готовой награде
- **Тексты игроку:** русский + эмодзи; level-up — HTML bold через `BotResponse.html()`
- **Комментарии в коде:** только английский
- **После каждого блока работы:** предложить название коммита

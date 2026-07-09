package com.reefbot.service.game;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reefbot.entity.InventoryItem;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerTide;
import com.reefbot.enums.ConsumableItem;
import com.reefbot.entity.Island;
import com.reefbot.repository.InventoryRepository;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class TideService {

    // Tide window: 40 minutes
    private static final int WINDOW_MINUTES = 40;
    // Next tide: 5–10 hours after window closes
    private static final int MIN_NEXT_HOURS = 5;
    private static final int MAX_NEXT_HOURS = 10;

    // Rewards per hit count: List<List<ConsumableItem[]>>
    // Each tier is a list of possible reward bundles (one is picked by rewardIndex)
    private static final List<List<ConsumableItem[]>> REWARD_POOLS = List.of(
        // 0 hits — always empty (shells given separately as consolation)
        List.of(),
        // 1 hit
        List.of(
            new ConsumableItem[]{ConsumableItem.SPEED_SCROLL},
            new ConsumableItem[]{ConsumableItem.BAIT},
            new ConsumableItem[]{ConsumableItem.FISHING_HOOK}
        ),
        // 2 hits
        List.of(
            new ConsumableItem[]{ConsumableItem.SPEED_SCROLL, ConsumableItem.SPEED_SCROLL},
            new ConsumableItem[]{ConsumableItem.TIDE_VIAL},
            new ConsumableItem[]{ConsumableItem.BAIT, ConsumableItem.BAIT},
            new ConsumableItem[]{ConsumableItem.FISHING_HOOK, ConsumableItem.FISHING_HOOK}
        ),
        // 3 hits
        List.of(
            new ConsumableItem[]{ConsumableItem.SPEED_SCROLL, ConsumableItem.SPEED_SCROLL, ConsumableItem.TIDE_VIAL},
            new ConsumableItem[]{ConsumableItem.TIDE_VIAL, ConsumableItem.TIDE_VIAL},
            new ConsumableItem[]{ConsumableItem.SPEED_SCROLL, ConsumableItem.BAIT, ConsumableItem.BAIT}
        )
    );

    private static final String[] NARRATIVES = {
        "Деревянный ящик без маркировки. Замок ржавый, но держит.",
        "Рыбацкая сеть зацепилась за камни. Внутри что-то есть.",
        "Засмолённый бочонок с воском на крышке. Волна несла его издалека.",
        "Тяжёлый мешок со шнуровкой. Намок, но не пустой.",
        "Обломок шлюпки с ящиком под сиденьем."
    };

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final PlayerRepository playerRepository;
    private final InventoryRepository inventoryRepository;
    private final IslandRepository islandRepository;

    // ── State queries ─────────────────────────────────────────────────────

    public boolean isActive(Player player) {
        PlayerTide tide = player.getTide();
        // Guard against null tideExpiresAt — possible for players created before tide init
        if (tide == null || tide.getTideAvailableAt() == null || tide.getTideExpiresAt() == null) return false;
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(tide.getTideAvailableAt()) && now.isBefore(tide.getTideExpiresAt());
    }

    public boolean isInProgress(Player player) {
        return isActive(player) && player.getTide().getTideRoundIndex() > 0;
    }

    public boolean isCompleted(Player player) {
        PlayerTide tide = player.getTide();
        return tide != null && tide.getTideRoundIndex() >= 3;
    }

    public int getRoundsPlayed(Player player) {
        PlayerTide tide = player.getTide();
        return tide == null ? 0 : tide.getTideRoundIndex();
    }

    public String getNarrative(Player player) {
        try {
            TideRollsData data = parseJson(player.getTide().getTideRollsJson());
            return NARRATIVES[data.narrativeIndex() % NARRATIVES.length];
        } catch (Exception e) {
            return NARRATIVES[0];
        }
    }

    // ── Scheduling ────────────────────────────────────────────────────────

    /** Admin: force-activate tide for a player right now. */
    @Transactional
    public void forceTide(Player player) {
        initTide(player, LocalDateTime.now());
        playerRepository.save(player);
    }

    /** First tide for a new player — available in 1–2 hours. */
    @Transactional
    public void scheduleFirstTide(Player player) {
        int minutes = 60 + ThreadLocalRandom.current().nextInt(60); // 1–2h
        initTide(player, LocalDateTime.now().plusMinutes(minutes));
        playerRepository.save(player);
    }

    /** After game completion — schedule next tide in 5–10 hours. */
    @Transactional
    public void scheduleNextTide(Player player) {
        int hours = MIN_NEXT_HOURS + ThreadLocalRandom.current().nextInt(MAX_NEXT_HOURS - MIN_NEXT_HOURS + 1);
        initTide(player, LocalDateTime.now().plusHours(hours));
        // caller must save player
    }

    /** Set timestamps and pick reward/narrative for the tide window starting at {@code available}. */
    private void initTide(Player player, LocalDateTime available) {
        // Sample rewardIndex from the max pool size across all tiers so every bundle is reachable.
        // pool[2] has 4 elements (largest); using pool[3].size()=3 previously made index 3 unreachable.
        int maxPoolSize    = REWARD_POOLS.stream().mapToInt(List::size).max().orElse(1);
        int rewardIndex    = ThreadLocalRandom.current().nextInt(maxPoolSize);
        int narrativeIndex = ThreadLocalRandom.current().nextInt(NARRATIVES.length);

        PlayerTide tide = player.getTide();
        tide.setTideAvailableAt(available);
        tide.setTideExpiresAt(available.plusMinutes(WINDOW_MINUTES));
        tide.setTideRollsJson(toJson(new TideRollsData(rewardIndex, narrativeIndex)));
        tide.setTideRoundIndex(0);
        tide.setTideHits(0);
        tide.setTideNotified(false);
    }

    // ── Game actions ──────────────────────────────────────────────────────

    /**
     * Called when the player guessed correctly (dice value already determined by Telegram).
     * Increments roundIndex and hits.
     */
    @Transactional
    public void resolveCorrectRound(Player player) {
        PlayerTide tide = player.getTide();
        tide.setTideRoundIndex(tide.getTideRoundIndex() + 1);
        tide.setTideHits(tide.getTideHits() + 1);
        tide.setRollPending(false);
        playerRepository.save(player);
    }

    /** Called when the player guessed wrong — resets tide, schedules the next one. */
    @Transactional
    public void failGame(Player player) {
        player.getTide().setRollPending(false);
        scheduleNextTide(player);
        playerRepository.save(player);
    }

    /**
     * Finish the game (player chose "Забрать" or all 3 rounds done).
     * Grants items to inventory. Schedules next tide.
     * Returns the reward that was given.
     */
    @Transactional
    public TideReward finishGame(Player player) {
        int hits = player.getTide().getTideHits();
        TideReward reward = buildReward(player, hits);

        for (Map.Entry<ConsumableItem, Integer> entry : reward.items().entrySet()) {
            giveItem(player, entry.getKey(), entry.getValue());
        }

        player.getTide().setRollPending(false);
        scheduleNextTide(player);
        playerRepository.save(player);
        return reward;
    }

    // ── Inventory helpers ─────────────────────────────────────────────────

    public int getItemCount(Player player, ConsumableItem item) {
        return inventoryRepository
                .findByPlayerAndItemTypeAndItemKey(player, ConsumableItem.ITEM_TYPE, item.name())
                .map(InventoryItem::getQuantity)
                .orElse(0);
    }

    @Transactional
    public void giveItem(Player player, ConsumableItem item, int qty) {
        Optional<InventoryItem> existing = inventoryRepository
                .findByPlayerAndItemTypeAndItemKey(player, ConsumableItem.ITEM_TYPE, item.name());
        if (existing.isPresent()) {
            existing.get().setQuantity(existing.get().getQuantity() + qty);
            inventoryRepository.save(existing.get());
        } else {
            inventoryRepository.save(InventoryItem.builder()
                    .player(player)
                    .itemType(ConsumableItem.ITEM_TYPE)
                    .itemKey(item.name())
                    .quantity(qty)
                    .build());
        }
    }

    @Transactional
    public boolean consumeItem(Player player, ConsumableItem item) {
        Optional<InventoryItem> existing = inventoryRepository
                .findByPlayerAndItemTypeAndItemKey(player, ConsumableItem.ITEM_TYPE, item.name());
        if (existing.isEmpty() || existing.get().getQuantity() <= 0) return false;
        int newQty = existing.get().getQuantity() - 1;
        if (newQty == 0) {
            inventoryRepository.delete(existing.get());
        } else {
            existing.get().setQuantity(newQty);
            inventoryRepository.save(existing.get());
        }
        return true;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private TideReward buildReward(Player player, int hits) {
        if (hits == 0) {
            return new TideReward(Map.of(), 5); // утешение — только ракушки
        }

        List<ConsumableItem[]> pool = REWARD_POOLS.get(Math.min(hits, 3));
        if (pool.isEmpty()) return new TideReward(Map.of(), 0);

        int rewardIndex;
        try {
            TideRollsData data = parseJson(player.getTide().getTideRollsJson());
            rewardIndex = data.rewardIndex() % pool.size();
        } catch (Exception e) {
            rewardIndex = 0;
        }

        ConsumableItem[] bundle = pool.get(rewardIndex);
        Map<ConsumableItem, Integer> items = new LinkedHashMap<>();
        for (ConsumableItem c : bundle) {
            items.merge(c, 1, Integer::sum);
        }

        // Ракушки всегда идут вместе с расходниками — количество растёт с попаданиями
        int shells = switch (hits) {
            case 1 -> 5;
            case 2 -> 10;
            default -> 20; // 3 попадания
        };
        return new TideReward(items, shells);
    }

    private TideRollsData parseJson(String json) {
        try {
            return MAPPER.readValue(json, TideRollsData.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse tide rolls JSON", e);
        }
    }

    private String toJson(TideRollsData data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize tide rolls", e);
        }
    }

    // ── Beach scan ────────────────────────────────────────────────────────

    private static final int BEACH_COOLDOWN_HOURS = 4;

    private static final List<String> BEACH_SMALL = List.of(
            "Пара ракушек у кромки воды.",
            "Волна оставила несколько ракушек.",
            "Среди гальки нашлось немного ракушек.",
            "Мелочь, но всё же — горсть ракушек.",
            "Прибой выбросил немного.",
            "Ракушки под ногами — не пустой поход."
    );

    private static final List<String> BEACH_MEDIUM = List.of(
            "Неплохая находка — целая горка ракушек.",
            "Хороший улов с пляжа.",
            "Прилив постарался — больше обычного.",
            "Повезло — ракушки собрались в одном месте."
    );

    private static final List<String> BEACH_RARE = List.of(
            "🍾 Бутылка с запиской! Внутри — горсть ракушек и старая карта с крестиком.",
            "🍾 Запечатанная бутылка. Послание незнакомца... и горка ракушек на прощание.",
            "🍾 Бутылочная почта! Кто-то богатый писал — внутри полно ракушек."
    );

    public boolean isBeachReady(Player player) {
        PlayerTide tide = player.getTide();
        if (tide == null || tide.getBeachScannedAt() == null) return true;
        return LocalDateTime.now().isAfter(tide.getBeachScannedAt().plusHours(BEACH_COOLDOWN_HOURS));
    }

    public String beachCooldownText(Player player) {
        PlayerTide tide = player.getTide();
        if (tide == null || tide.getBeachScannedAt() == null) return "готово";
        LocalDateTime readyAt = tide.getBeachScannedAt().plusHours(BEACH_COOLDOWN_HOURS);
        long totalSeconds = Math.max(0, java.time.Duration.between(LocalDateTime.now(), readyAt).getSeconds());
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        if (hours > 0) return "через " + hours + " ч " + minutes + " мин";
        return "через " + minutes + " мин";
    }

    @Transactional
    public BeachResult scanBeach(Player player, Island island) {
        player.getTide().setBeachScannedAt(LocalDateTime.now());

        int roll = ThreadLocalRandom.current().nextInt(100);
        int shells;
        boolean rare;
        String flavor;

        if (roll < 5) {                    // 5% — редкость (бутылка)
            shells = 20;
            rare = true;
            flavor = BEACH_RARE.get(ThreadLocalRandom.current().nextInt(BEACH_RARE.size()));
        } else if (roll < 20) {            // 15% — хорошая находка
            shells = 8 + ThreadLocalRandom.current().nextInt(8); // 8–15
            rare = false;
            flavor = BEACH_MEDIUM.get(ThreadLocalRandom.current().nextInt(BEACH_MEDIUM.size()));
        } else {                           // 80% — мелочь
            shells = 2 + ThreadLocalRandom.current().nextInt(4); // 2–5
            rare = false;
            flavor = BEACH_SMALL.get(ThreadLocalRandom.current().nextInt(BEACH_SMALL.size()));
        }

        island.setShells(island.getShells() + shells);
        islandRepository.save(island);
        playerRepository.save(player);

        return new BeachResult(shells, rare, flavor);
    }

    // ── Inner records ─────────────────────────────────────────────────────

    public record TideRollsData(
            @JsonProperty("rewardIndex") int rewardIndex,
            @JsonProperty("narrativeIndex") int narrativeIndex
    ) {}

    public record TideReward(Map<ConsumableItem, Integer> items, int shells) {
        public boolean hasItems() { return !items.isEmpty(); }
    }

    public record BeachResult(int shells, boolean rare, String flavorText) {}
}

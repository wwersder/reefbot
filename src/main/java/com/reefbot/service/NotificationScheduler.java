package com.reefbot.service;

import com.reefbot.entity.Building;
import com.reefbot.entity.Island;
import com.reefbot.enums.BuildingStatus;
import com.reefbot.repository.BuildingRepository;
import com.reefbot.repository.IslandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final BuildingRepository buildingRepository;
    private final IslandRepository   islandRepository;
    private final NotificationSender notificationSender;

    /**
     * Every 60 seconds:
     * 1. Complete IN_PROGRESS builds whose timer has expired.
     *    notificationSent stays false → next step will send push.
     * 2. Send push notifications for BUILT buildings the player hasn't yet seen
     *    (notificationSent = false).
     */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void processBuildCompletions() {
        LocalDateTime now = LocalDateTime.now();

        // Step 1 – Complete expired builds
        List<Building> expired = buildingRepository
                .findByStatusAndFinishAtBefore(BuildingStatus.IN_PROGRESS, now);

        if (!expired.isEmpty()) {
            expired.forEach(b -> {
                b.setStatus(BuildingStatus.BUILT);
                b.setLastCollectedAt(now);
                // notificationSent intentionally left false → notification sent in step 2
            });
            buildingRepository.saveAll(expired);

            // Update island.level for each affected island
            Map<Island, Long> countByIsland = expired.stream()
                    .collect(Collectors.groupingBy(Building::getIsland, Collectors.counting()));
            countByIsland.forEach((island, count) -> {
                island.setLevel(island.getLevel() + count.intValue());
                islandRepository.save(island);
            });

            log.info("Scheduler completed {} build(s)", expired.size());
        }

        // Step 2 – Send notifications for BUILT buildings not yet seen by player
        List<Building> toNotify = buildingRepository.findBuiltPendingNotification();

        toNotify.forEach(b -> {
            Long telegramId = b.getIsland().getPlayer().getTelegramId();
            String text = "🏗 Строительство завершено!\n\n"
                    + b.getType().getDisplayName()
                    + " теперь работает на твоём острове.\n\n"
                    + "Открой бота, чтобы собрать ресурсы.";
            notificationSender.send(telegramId, text);
            b.setNotificationSent(true);
        });

        if (!toNotify.isEmpty()) {
            buildingRepository.saveAll(toNotify);
            log.info("Scheduler sent {} build notification(s)", toNotify.size());
        }
    }
}

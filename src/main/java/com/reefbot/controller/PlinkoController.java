package com.reefbot.controller;

import com.reefbot.dto.plinko.*;
import com.reefbot.service.plinko.PlinkoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/mini/plinko")
@RequiredArgsConstructor
public class PlinkoController {

    private static final String INIT_DATA_HEADER = "X-Telegram-Init-Data";

    private final PlinkoService plinkoService;

    // ── GET /api/mini/plinko/state ────────────────────────────────────────────

    @GetMapping("/state")
    public ResponseEntity<PlinkoStateResponse> state(
            @RequestHeader(INIT_DATA_HEADER) String initData) {
        try {
            PlinkoStateResponse response = plinkoService.getState(initData);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            log.warn("plinko /state auth failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("plinko /state error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── POST /api/mini/plinko/play ────────────────────────────────────────────

    @PostMapping("/play")
    public ResponseEntity<PlinkoPlayResponse> play(
            @RequestHeader(INIT_DATA_HEADER) String initData,
            @RequestBody PlinkoPlayRequest request) {
        try {
            PlinkoPlayResponse response = plinkoService.play(initData, request);
            if (response.error() != null) {
                return ResponseEntity.badRequest().body(response);
            }
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            log.warn("plinko /play auth failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("plinko /play error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── GET /api/mini/plinko/leaderboard ─────────────────────────────────────

    @GetMapping("/leaderboard")
    public ResponseEntity<PlinkoLeaderboardResponse> leaderboard() {
        try {
            return ResponseEntity.ok(plinkoService.getLeaderboard());
        } catch (Exception e) {
            log.error("plinko /leaderboard error", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

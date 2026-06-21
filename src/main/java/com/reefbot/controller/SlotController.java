package com.reefbot.controller;

import com.reefbot.dto.slot.SlotSpinRequest;
import com.reefbot.dto.slot.SlotSpinResponse;
import com.reefbot.dto.slot.SlotStateResponse;
import com.reefbot.service.slot.SlotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/mini/slot")
@RequiredArgsConstructor
public class SlotController {

    private static final String INIT_DATA_HEADER = "X-Telegram-Init-Data";

    private final SlotService slotService;

    // ── GET /api/mini/slot/state ──────────────────────────────────────────────

    @GetMapping("/state")
    public ResponseEntity<SlotStateResponse> state(
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData) {
        if (initData == null || initData.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(slotService.getState(initData));
        } catch (SecurityException e) {
            log.warn("slot /state auth failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("slot /state error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── POST /api/mini/slot/buy-bonus ────────────────────────────────────────

    @PostMapping("/buy-bonus")
    public ResponseEntity<SlotSpinResponse> buyBonus(
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
            @RequestBody SlotSpinRequest request) {
        if (initData == null || initData.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            SlotSpinResponse response = slotService.buyBonus(initData, request);
            if (response.error() != null) return ResponseEntity.badRequest().body(response);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            log.warn("slot /buy-bonus auth failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("slot /buy-bonus error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── POST /api/mini/slot/spin ──────────────────────────────────────────────

    @PostMapping("/spin")
    public ResponseEntity<SlotSpinResponse> spin(
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
            @RequestBody SlotSpinRequest request) {
        if (initData == null || initData.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            SlotSpinResponse response = slotService.spin(initData, request);
            if (response.error() != null) {
                return ResponseEntity.badRequest().body(response);
            }
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            log.warn("slot /spin auth failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("slot /spin error", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

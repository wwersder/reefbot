package com.reefbot.controller;

import com.reefbot.dto.slotwar.SlotWarSpinRequest;
import com.reefbot.dto.slotwar.SlotWarSpinResponse;
import com.reefbot.dto.slotwar.SlotWarStateResponse;
import com.reefbot.service.slotwar.SlotWarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/mini/slot-war")
@RequiredArgsConstructor
public class SlotWarController {

    private static final String INIT_DATA_HEADER = "X-Telegram-Init-Data";

    private final SlotWarService slotWarService;

    @GetMapping("/state")
    public ResponseEntity<SlotWarStateResponse> state(
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData) {
        if (initData == null || initData.isBlank())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            return ResponseEntity.ok(slotWarService.getState(initData));
        } catch (SecurityException e) {
            log.warn("slot-war /state auth failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("slot-war /state error", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/spin")
    public ResponseEntity<SlotWarSpinResponse> spin(
            @RequestHeader(value = INIT_DATA_HEADER, required = false) String initData,
            @RequestBody SlotWarSpinRequest request) {
        if (initData == null || initData.isBlank())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            SlotWarSpinResponse response = slotWarService.spin(initData, request);
            if (response.error() != null) return ResponseEntity.badRequest().body(response);
            return ResponseEntity.ok(response);
        } catch (SecurityException e) {
            log.warn("slot-war /spin auth failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (Exception e) {
            log.error("slot-war /spin error", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

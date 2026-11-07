package com.mannschaft.app.ranch.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.ranch.dto.RanchSlotRequest;
import com.mannschaft.app.ranch.dto.RoomSlotSummary;
import com.mannschaft.app.ranch.service.RanchSlotFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 三枠への本人置物配置と204解除。 */
@RestController
@RequestMapping("/api/v1/me/ranch/room/slots")
@RequiredArgsConstructor
public class RanchSlotController {
    private final PrivateSelfAccessGuard accessGuard;
    private final RanchSlotFacade facade;

    @PutMapping("/{slotKey}")
    public ResponseEntity<ApiResponse<RoomSlotSummary>> place(
            @PathVariable String slotKey,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchSlotRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.of(facade.place(userId, key, slotKey, body)));
    }

    @DeleteMapping("/{slotKey}")
    public ResponseEntity<Void> clear(
            @PathVariable String slotKey,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestHeader(HttpHeaders.IF_MATCH) String version,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        facade.clear(userId, key, slotKey, version);
        // 204 では本文 writer が走らないため、private 指示を Servlet 応答にも確定する。
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "private, no-store").build();
    }
}

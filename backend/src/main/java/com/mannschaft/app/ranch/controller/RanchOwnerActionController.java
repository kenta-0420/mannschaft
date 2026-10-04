package com.mannschaft.app.ranch.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.ranch.dto.InteractionResult;
import com.mannschaft.app.ranch.dto.OwnerSummary;
import com.mannschaft.app.ranch.dto.RanchInteractionRequest;
import com.mannschaft.app.ranch.dto.RanchSettings;
import com.mannschaft.app.ranch.dto.RanchSettingsRequest;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import com.mannschaft.app.ranch.service.RanchOwnerActionFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 本人設定、参加期間、無償TOUCHの私有HTTP入口。 */
@RestController
@RequestMapping("/api/v1/me/ranch")
@RequiredArgsConstructor
public class RanchOwnerActionController {
    private final RanchOwnerActionFacade facade;
    private final PrivateSelfAccessGuard accessGuard;

    @SelfScopedEndpoint("PrivateSelfAccessGuardで本人ID・変身拒否を確定")
    @PutMapping("/settings")
    public ResponseEntity<ApiResponse<RanchSettings>> settings(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchSettingsRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return ok(facade.settings(userId, key, body), response);
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardで本人ID・変身拒否を確定")
    @PostMapping("/pause")
    public ResponseEntity<ApiResponse<OwnerSummary>> pause(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchVersionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return ok(facade.pause(userId, key, body), response);
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardで本人ID・変身拒否を確定")
    @PostMapping("/resume")
    public ResponseEntity<ApiResponse<OwnerSummary>> resume(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchVersionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return ok(facade.resume(userId, key, body), response);
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardで本人ID・変身拒否を確定")
    @PostMapping("/interactions")
    public ResponseEntity<ApiResponse<InteractionResult>> touch(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchInteractionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        var outcome = facade.touch(userId, key, body);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return ResponseEntity.status(outcome.createdNow() ? 201 : 200)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.of(outcome.result()));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T result, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.of(result));
    }
}

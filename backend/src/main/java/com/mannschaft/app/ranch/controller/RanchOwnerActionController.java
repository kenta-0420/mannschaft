package com.mannschaft.app.ranch.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.ranch.dto.InteractionResult;
import com.mannschaft.app.ranch.dto.FeedingResult;
import com.mannschaft.app.ranch.dto.HatchResponse;
import com.mannschaft.app.ranch.dto.RanchHatchRequest;
import com.mannschaft.app.ranch.dto.RanchPurchaseRequest;
import com.mannschaft.app.ranch.dto.RanchPurchaseResult;
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
import java.net.URI;

/** 本人設定、参加期間、孵化、給餌、購入、無償TOUCHの私有HTTP入口。 */
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

    @SelfScopedEndpoint("PrivateSelfAccessGuardで本人ID・変身拒否を確定")
    @PostMapping("/hatch")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の保存済み成功結果へ常時到達し、新規孵化は内側の選定・時刻・命名条件で検証する")
    public ResponseEntity<ApiResponse<HatchResponse>> hatch(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchHatchRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        return ok(facade.hatch(userId, key, body), response);
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardで本人ID・変身拒否を確定")
    @PostMapping("/feeding")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の保存済み成功結果へ常時到達し、新規給餌は内側のcare制御とowner状態で検証する")
    public ResponseEntity<ApiResponse<FeedingResult>> feed(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchVersionRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        var outcome = facade.feedOutcome(userId, key, body);
        return commandResult(outcome.result(), outcome.result().commandId(), outcome.createdNow(), response);
    }

    @SelfScopedEndpoint("PrivateSelfAccessGuardで本人ID・変身拒否を確定")
    @PostMapping("/purchases")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の保存済み成功結果へ常時到達し、新規購入は内側のshop制御と価格・残高で検証する")
    public ResponseEntity<ApiResponse<RanchPurchaseResult>> purchase(
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody RanchPurchaseRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        var outcome = facade.purchase(userId, key, body);
        return commandResult(outcome.result(), outcome.result().commandId(), outcome.createdNow(), response);
    }

    private <T> ResponseEntity<ApiResponse<T>> commandResult(T result, UUID commandId,
            boolean createdNow, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        ResponseEntity.BodyBuilder builder = createdNow
                ? ResponseEntity.created(URI.create("/api/v1/me/ranch/commands/" + commandId))
                : ResponseEntity.ok();
        return builder.header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(ApiResponse.of(result));
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T result, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(ApiResponse.of(result));
    }
}

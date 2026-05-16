package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.pointcard.dto.BalanceEventRequest;
import com.mannschaft.app.pointcard.dto.BalanceEventResponse;
import com.mannschaft.app.pointcard.service.PointCardBalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F18 個人ポイントカードウォレット — 残高型 CHARGE / SPENT / REFUND API（Phase 3 第二陣 2B）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.1 / §6
 *
 * <p>すべて ADMIN または DEPUTY_ADMIN のみ操作可能（Service 層で
 * {@code accessControlService.checkAdminOrAbove} 実行）。
 *
 * <h2>エンドポイント</h2>
 * <ul>
 *   <li>{@code POST /api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events}
 *       — operationType により CHARGE / SPENT / REFUND を分岐</li>
 *   <li>{@code GET /api/v1/organizations/{orgId}/point-cards/balance-events}
 *       — 組織内残高変動履歴（新着順、providerId 絞り込み可）</li>
 *   <li>{@code GET /api/v1/organizations/{orgId}/point-cards/{cardId}/balance-events}
 *       — 単一カードの履歴（IDOR 防止検証あり）</li>
 * </ul>
 *
 * <h2>レート制限</h2>
 * <p>{@code PointCardRateLimitFilter} でパス別に適用:
 * <ul>
 *   <li>{@code POST .../balance-events}: 300/h</li>
 *   <li>{@code GET  .../balance-events}: 120/min</li>
 *   <li>{@code GET  .../{cardId}/balance-events}: 120/min</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/point-cards")
@Tag(name = "ポイントカード 残高型",
        description = "F18 Phase 3 自店発行残高型カードの入金 / 利用 / 返金")
@RequiredArgsConstructor
public class OrgPointCardBalanceController {

    private final PointCardBalanceService balanceService;

    // ─────────────────────────────────────────────
    // 残高イベント記録（CHARGE / SPENT / REFUND）
    // ─────────────────────────────────────────────

    @PostMapping("/{cardId}/balance-events")
    @Operation(summary = "残高イベント記録",
            description = "operationType により CHARGE（入金）/ SPENT（利用）/ REFUND（返金）を分岐。"
                    + "監査ログ POINT_CARD_BALANCE_CHARGED/SPENT/REFUNDED を記録。")
    public ResponseEntity<ApiResponse<BalanceEventResponse>> recordEvent(
            @PathVariable Long orgId,
            @PathVariable UUID cardId,
            @Valid @RequestBody BalanceEventRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SecurityUtils.getCurrentUserId();
        String ipAddress = resolveIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String sessionHash = httpRequest.getHeader("X-Session-Hash");

        BalanceEventResponse response = switch (request.operationType()) {
            case CHARGE -> balanceService.charge(orgId, cardId, userId, request,
                    ipAddress, userAgent, sessionHash);
            case SPENT -> balanceService.spend(orgId, cardId, userId, request,
                    ipAddress, userAgent, sessionHash);
            case REFUND -> balanceService.refund(orgId, cardId, userId, request,
                    ipAddress, userAgent, sessionHash);
        };
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ─────────────────────────────────────────────
    // 組織内残高変動履歴一覧
    // ─────────────────────────────────────────────

    @GetMapping("/balance-events")
    @Operation(summary = "組織内残高変動履歴一覧",
            description = "組織配下の残高変動履歴を新着順に返す。providerId 指定でプロバイダー絞り込み可。")
    public ResponseEntity<Page<BalanceEventResponse>> listOrgEvents(
            @PathVariable Long orgId,
            @RequestParam(required = false) UUID providerId,
            @PageableDefault(size = 20) Pageable pageable) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                balanceService.listOrgEvents(orgId, userId, providerId, pageable));
    }

    // ─────────────────────────────────────────────
    // 単一カード残高変動履歴
    // ─────────────────────────────────────────────

    @GetMapping("/{cardId}/balance-events")
    @Operation(summary = "単一カード残高変動履歴",
            description = "特定カードに対する残高変動履歴を新着順に返す。"
                    + "対象カードのプロバイダーが当該組織発行であるか検証する（IDOR 防止）。")
    public ResponseEntity<ApiResponse<List<BalanceEventResponse>>> listCardEvents(
            @PathVariable Long orgId,
            @PathVariable UUID cardId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                ApiResponse.of(balanceService.listCardEvents(orgId, cardId, userId)));
    }

    /**
     * X-Forwarded-For ヘッダを優先しつつクライアント IP を解決する。
     */
    private String resolveIpAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0) ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}

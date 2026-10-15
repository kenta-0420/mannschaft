package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.pointcard.dto.StampEventResponse;
import com.mannschaft.app.pointcard.dto.StampRequest;
import com.mannschaft.app.pointcard.service.PointCardStampService;
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
 * F18 個人ポイントカードウォレット — 組織スコープのスタンプ押印 / 履歴 API（Phase 2 第二陣 2C）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2 / §12
 *
 * <p>すべて ADMIN または DEPUTY_ADMIN のみ操作可能（Service 層で
 * {@code accessControlService.checkAdminOrAbove} 実行）。
 *
 * <h2>レート制限</h2>
 * <p>{@code PointCardRateLimitFilter} でパス別に適用:
 * <ul>
 *   <li>{@code POST /api/v1/organizations/{orgId}/point-cards/{cardId}/stamps}: 300/h</li>
 *   <li>{@code GET  /api/v1/organizations/{orgId}/point-cards/stamps}:           120/min</li>
 *   <li>{@code GET  /api/v1/organizations/{orgId}/point-cards/{cardId}/stamps}:  120/min</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/point-cards")
@Tag(name = "ポイントカード 店主スタンプ",
        description = "F18 Phase 2 自店スタンプカードへの押印・履歴")
@RequiredArgsConstructor
public class OrgPointCardStampController {

    private final PointCardStampService stampService;

    // ─────────────────────────────────────────────
    // スタンプ押印
    // ─────────────────────────────────────────────

    @PostMapping("/{cardId}/stamps")
    @Operation(summary = "スタンプ押印",
            description = "対象カードに delta 分のスタンプを加算（負値で減算）。"
                    + "監査ログ POINT_CARD_STAMP_ISSUED を記録。")
    public ResponseEntity<ApiResponse<StampEventResponse>> stamp(
            @PathVariable Long orgId,
            @PathVariable UUID cardId,
            @Valid @RequestBody StampRequest request,
            HttpServletRequest httpRequest) {
        Long userId = SecurityUtils.getCurrentUserId();
        String ipAddress = resolveIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        String sessionHash = httpRequest.getHeader("X-Session-Hash");

        StampEventResponse response = stampService.stamp(
                orgId, cardId, userId, request, ipAddress, userAgent, sessionHash);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ─────────────────────────────────────────────
    // 組織内押印履歴一覧
    // ─────────────────────────────────────────────

    @GetMapping("/stamps")
    @Operation(summary = "組織内押印履歴一覧",
            description = "組織配下の押印履歴を新着順に返す。providerId 指定でプロバイダー絞り込み可。")
    public ResponseEntity<Page<StampEventResponse>> listOrgStamps(
            @PathVariable Long orgId,
            @RequestParam(required = false) UUID providerId,
            @PageableDefault(size = 20) Pageable pageable) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                stampService.listOrgStamps(orgId, userId, providerId, pageable));
    }

    // ─────────────────────────────────────────────
    // 単一カード押印履歴
    // ─────────────────────────────────────────────

    @GetMapping("/{cardId}/stamps")
    @Operation(summary = "単一カード押印履歴",
            description = "特定カードに対する押印履歴を新着順に返す。"
                    + "対象カードのプロバイダーが当該組織発行であるか検証する（IDOR 防止）。")
    public ResponseEntity<ApiResponse<List<StampEventResponse>>> listCardStamps(
            @PathVariable Long orgId,
            @PathVariable UUID cardId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                ApiResponse.of(stampService.listCardStamps(orgId, cardId, userId)));
    }

    /**
     * X-Forwarded-For ヘッダを優先しつつクライアント IP を解決する。
     * 監査ログに記録するためのベストエフォート実装。
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

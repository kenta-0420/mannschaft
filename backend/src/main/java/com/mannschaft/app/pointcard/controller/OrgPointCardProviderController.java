package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.pointcard.dto.CreateOrgProviderRequest;
import com.mannschaft.app.pointcard.dto.CustomerQrResponse;
import com.mannschaft.app.pointcard.dto.PointCardProviderResponse;
import com.mannschaft.app.pointcard.dto.UpdateOrgProviderRequest;
import com.mannschaft.app.pointcard.service.OrgPointCardProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F18 Phase 2 S2B — 組織管理者向け自店プロバイダー CRUD コントローラー。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6 / §12 / §3.3 UC-8
 *
 * <p>ベースパス: {@code /api/v1/organizations/{orgId}/point-cards/providers}
 *
 * <h2>エンドポイント一覧</h2>
 * <ul>
 *   <li>GET    {@code /} — 当該組織の自店プロバイダー一覧（{@code ?active=true} で有効化のみ絞り込み）</li>
 *   <li>POST   {@code /} — 新規発行（{@code type=SELF_ISSUED_STAMP} 固定）</li>
 *   <li>GET    {@code /{providerId}} — 詳細</li>
 *   <li>PATCH  {@code /{providerId}} — 編集（{@code type} / {@code organization_id} / {@code code} は不変）</li>
 *   <li>DELETE {@code /{providerId}} — 停止（{@code is_active=false} に更新、物理削除しない）</li>
 *   <li>GET    {@code /{providerId}/customer-qr} — 顧客追加用 QR のディープリンク URL</li>
 * </ul>
 *
 * <h2>認可</h2>
 * <p>一覧・詳細・新規発行・編集・QR は ADMIN または DEPUTY_ADMIN。停止のみ ADMIN 限定。
 * 認可検証は Service 入口の {@code accessControlService.checkAdminOrAbove} / {@code isAdmin} で行う。</p>
 *
 * <h2>レート制限</h2>
 * <p>{@code PointCardRateLimitFilter} に POST / PATCH / DELETE 各 30/h を追加（S2B）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/point-cards/providers")
@Tag(name = "ポイントカード 自店プロバイダー",
        description = "F18 Phase 2 S2B — 組織管理者向け自店プロバイダー CRUD")
@RequiredArgsConstructor
public class OrgPointCardProviderController {

    private final OrgPointCardProviderService service;

    // ─────────────────────────────────────────────
    // 一覧
    // ─────────────────────────────────────────────

    /**
     * 当該組織の自店プロバイダー一覧を返す。
     * {@code ?active=true} クエリパラメータが付くと {@code is_active=true} のみに絞り込む。
     * 既定（パラメータ無し）では停止済を含めて全件を返す（管理画面で停止プロバイダーを確認できるように）。
     */
    @GetMapping
    @Operation(summary = "自店プロバイダー一覧取得",
            description = "当該組織が発行した自店プロバイダー（type=SELF_ISSUED_STAMP）を返す。"
                    + "?active=true で is_active=true のみに絞り込み可能。")
    public ResponseEntity<ApiResponse<List<PointCardProviderResponse>>> listProviders(
            @PathVariable Long orgId,
            @RequestParam(name = "active", required = false) Boolean active) {
        Long userId = SecurityUtils.getCurrentUserId();
        boolean activeOnly = Boolean.TRUE.equals(active);
        return ResponseEntity.ok(ApiResponse.of(
                service.listOrgProviders(orgId, userId, activeOnly)));
    }

    // ─────────────────────────────────────────────
    // 新規発行
    // ─────────────────────────────────────────────

    /**
     * 自店プロバイダーを新規発行する。{@code type=SELF_ISSUED_STAMP} 固定、
     * {@code organization_id} はパスから、{@code code} はサーバー自動生成。
     */
    @PostMapping
    @Operation(summary = "自店プロバイダー新規発行",
            description = "type=SELF_ISSUED_STAMP 固定で発行。code は org_{orgId}_{rand8} で自動生成。"
                    + "1 組織あたり 20 個上限。監査ログ POINT_CARD_PROVIDER_CREATED を 1 件記録。")
    public ResponseEntity<ApiResponse<PointCardProviderResponse>> createProvider(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateOrgProviderRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        PointCardProviderResponse response = service.createOrgProvider(orgId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ─────────────────────────────────────────────
    // 詳細
    // ─────────────────────────────────────────────

    /**
     * プロバイダー詳細を取得する。所属組織不一致は 404 ({@code PROVIDER_NOT_OWNED})。
     */
    @GetMapping("/{providerId}")
    @Operation(summary = "自店プロバイダー詳細取得")
    public ResponseEntity<ApiResponse<PointCardProviderResponse>> getProvider(
            @PathVariable Long orgId,
            @PathVariable UUID providerId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                service.getOrgProvider(orgId, providerId, userId)));
    }

    // ─────────────────────────────────────────────
    // 編集
    // ─────────────────────────────────────────────

    /**
     * プロバイダーを部分更新する。
     * {@code displayName / brandColor / logoUrl / cardNumberRegex / cardNumberLengthHint} を差分適用。
     * {@code type} / {@code organization_id} / {@code code} は不変（DTO に含めない設計）。
     */
    @PatchMapping("/{providerId}")
    @Operation(summary = "自店プロバイダー編集",
            description = "差分更新。type / organization_id / code は不変。"
                    + "監査ログ POINT_CARD_PROVIDER_UPDATED を 1 件記録。")
    public ResponseEntity<ApiResponse<PointCardProviderResponse>> updateProvider(
            @PathVariable Long orgId,
            @PathVariable UUID providerId,
            @Valid @RequestBody UpdateOrgProviderRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                service.updateOrgProvider(orgId, providerId, userId, request)));
    }

    // ─────────────────────────────────────────────
    // 停止（is_active=false への論理削除）
    // ─────────────────────────────────────────────

    /**
     * プロバイダーを停止する（物理削除ではなく {@code is_active=false} に更新）。
     * ADMIN のみ実行可能（DEPUTY_ADMIN は不可）。
     */
    @DeleteMapping("/{providerId}")
    @Operation(summary = "自店プロバイダー停止",
            description = "is_active=false に更新（物理削除しない）。ADMIN のみ実行可能。"
                    + "監査ログ POINT_CARD_PROVIDER_DEACTIVATED を 1 件記録。")
    public ResponseEntity<Void> deactivateProvider(
            @PathVariable Long orgId,
            @PathVariable UUID providerId) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.deactivateOrgProvider(orgId, providerId, userId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────
    // 顧客 QR ディープリンク
    // ─────────────────────────────────────────────

    /**
     * 顧客がアプリ追加に使う QR コード用のディープリンク URL 情報を返す。
     * 実 QR 画像はフロントエンドで {@code qrcode} ライブラリにより生成する。
     */
    @GetMapping("/{providerId}/customer-qr")
    @Operation(summary = "顧客追加用 QR ディープリンク URL 取得",
            description = "deepLinkUrl (mannschaft://...) と webUrl を返却。"
                    + "QR 画像本体はフロントで qrcode ライブラリで生成する。")
    public ResponseEntity<ApiResponse<CustomerQrResponse>> getCustomerQr(
            @PathVariable Long orgId,
            @PathVariable UUID providerId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                service.getCustomerQr(orgId, providerId, userId)));
    }
}

package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.pointcard.dto.CreateUserPointCardRequest;
import com.mannschaft.app.pointcard.dto.ShareTokenResponse;
import com.mannschaft.app.pointcard.dto.UpdateUserPointCardRequest;
import com.mannschaft.app.pointcard.dto.UserPointCardDetailResponse;
import com.mannschaft.app.pointcard.dto.UserPointCardListItemResponse;
import com.mannschaft.app.pointcard.service.PointCardService;
import com.mannschaft.app.pointcard.service.PointCardShareTokenService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * F18 個人ポイントカードウォレット — ユーザー保有カード CRUD コントローラー。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.4
 *
 * <p>認証必須・{@code PointCardRateLimitFilter} でユーザー別レート制限を適用する:
 * <ul>
 *   <li>POST /api/v1/point-cards: 30/h</li>
 *   <li>GET /api/v1/point-cards/{id}: 120/min</li>
 *   <li>POST /api/v1/point-cards/{id}/used: 600/h</li>
 * </ul>
 *
 * <p>IDOR 対策は Service 層 {@code findByIdAndUserId} で実施し、
 * 他人のカードへの参照には {@code POINT_CARD_006 CARD_NOT_FOUND} (404) を返す。
 */
@RestController
@RequestMapping("/api/v1/point-cards")
@Tag(name = "ポイントカード CRUD", description = "F18 個人ポイントカードウォレット — カード追加/取得/更新/削除/利用記録")
@RequiredArgsConstructor
public class PointCardController {

    private final PointCardService pointCardService;
    private final PointCardShareTokenService shareTokenService;

    // ─────────────────────────────────────────────
    // 一覧
    // ─────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "カード一覧取得",
            description = "自分のカード一覧をお気に入り → display_order → created_at 降順で返す。"
                    + "barcodeValue / nickname / memo は返さない（肩越し閲覧防止）。")
    public ResponseEntity<ApiResponse<List<UserPointCardListItemResponse>>> listMyCards() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(pointCardService.listMyCards(userId)));
    }

    // ─────────────────────────────────────────────
    // 作成
    // ─────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "カード追加",
            description = "規約同意 + 保有上限 200 枚チェック後、fuzzy match で provider を解決して保存する")
    public ResponseEntity<ApiResponse<UserPointCardDetailResponse>> createCard(
            @Valid @RequestBody CreateUserPointCardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        UserPointCardDetailResponse response = pointCardService.createCard(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ─────────────────────────────────────────────
    // 詳細
    // ─────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "カード詳細取得",
            description = "提示モードで使う復号値（barcodeValue / nickname / memo）を含めて返す")
    public ResponseEntity<ApiResponse<UserPointCardDetailResponse>> getCard(
            @PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(pointCardService.getCard(id, userId)));
    }

    // ─────────────────────────────────────────────
    // 更新
    // ─────────────────────────────────────────────

    @PatchMapping("/{id}")
    @Operation(summary = "カード部分更新（PATCH）",
            description = "displayName / nickname / memo / favorite / displayOrder を差分適用する。"
                    + "barcodeValue / barcodeFormat の変更は本 API では行えない（削除 → 再作成）。")
    public ResponseEntity<ApiResponse<UserPointCardDetailResponse>> updateCard(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserPointCardRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(
                ApiResponse.of(pointCardService.updateCard(id, userId, request)));
    }

    // ─────────────────────────────────────────────
    // 削除
    // ─────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @Operation(summary = "カード削除", description = "本人のカードを物理削除する。監査ログ POINT_CARD_DELETED を記録")
    public ResponseEntity<Void> deleteCard(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        pointCardService.deleteCard(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────
    // 利用記録
    // ─────────────────────────────────────────────

    @PostMapping("/{id}/used")
    @Operation(summary = "カード利用記録",
            description = "提示モードを閉じた直後に呼び出して last_used_at を更新する。監査ログは記録しない")
    public ResponseEntity<Void> recordUsed(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        pointCardService.recordUsed(id, userId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────
    // 一時トークン発行（QR 自動特定 / Phase 3 第二陣 2A）
    // ─────────────────────────────────────────────

    @PostMapping("/{cardId}/share-tokens")
    @Operation(summary = "QR 自動特定用 一時トークン発行",
            description = "本人のカードに対して 5 分 TTL の UUID トークンを Valkey に発行する。"
                    + "フロントは返却された token を QR コードに変換して店主側に提示し、"
                    + "店主側端末が POST /api/v1/organizations/{orgId}/point-cards/resolve-by-token で消費する。"
                    + "レート制限: 60/h/user。")
    public ResponseEntity<ApiResponse<ShareTokenResponse>> createShareToken(
            @PathVariable UUID cardId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ShareTokenResponse response = shareTokenService.generate(userId, cardId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}

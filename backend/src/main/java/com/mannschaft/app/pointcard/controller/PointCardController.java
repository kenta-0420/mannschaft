package com.mannschaft.app.pointcard.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
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

    @SelfScopedEndpoint("一覧の対象は SecurityUtils.getCurrentUserId() 固定で、"
            + "リクエストに他ユーザーの識別子を指定する項目が無い（listMyCards メソッド本体）")
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

    @SelfScopedEndpoint("作成対象の所有者は SecurityUtils.getCurrentUserId() 固定で、"
            + "リクエストに他ユーザーの識別子を指定する項目が無い（createCard メソッド本体）")
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

    /**
     * 保有カード 1 枚の詳細を返す。
     *
     * <p><b>認可の所在</b>: {@code PointCardService.getCard}
     * （{@code pointcard/service/PointCardService.java:99}）が
     * {@code cardRepository.findByIdAndUserId(cardId, userId)} で
     * 「当該カード ID かつ保有者本人」の複合条件で引き当てる。
     * 保有者以外の cardId は不存在と区別せず {@code CARD_NOT_FOUND}（404）で秘匿する。</p>
     */
    @AuthorizedInService
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

    /**
     * 保有カード 1 枚の表示情報を差分更新する。
     *
     * <p><b>認可の所在</b>: {@code PointCardService.updateCard}
     * （{@code pointcard/service/PointCardService.java:181}）が
     * {@code findByIdAndUserId} で保有者本人のカードのみを引き当て、
     * 不一致は {@code CARD_NOT_FOUND}（404）で秘匿する。更新は引き当てた Entity にのみ適用される。</p>
     */
    @AuthorizedInService
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

    /**
     * 保有カード 1 枚を削除する。
     *
     * <p><b>認可の所在</b>: {@code PointCardService.deleteCard}
     * （{@code pointcard/service/PointCardService.java:228}）が
     * {@code findByIdAndUserId} で保有者本人のカードを引き当ててから削除する。
     * 引き当てに失敗した cardId は {@code CARD_NOT_FOUND}（404）で秘匿し、削除は 1 件も行わない
     * （削除は認可の後にのみ実行される）。</p>
     */
    @AuthorizedInService
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

    /**
     * 保有カード 1 枚の最終利用時刻を更新する。
     *
     * <p><b>認可の所在</b>: {@code PointCardService.recordUsed}
     * （{@code pointcard/service/PointCardService.java:251}）が
     * {@code findByIdAndUserId} で保有者本人のカードを引き当ててから
     * {@code last_used_at} を更新する。不一致は {@code CARD_NOT_FOUND}（404）で秘匿し、
     * 他者のカードの利用時刻は書き換わらない。</p>
     */
    @AuthorizedInService
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

    /**
     * 保有カード 1 枚について、店主側端末が読み取る一時トークンを発行する。
     *
     * <p><b>認可の所在</b>: {@code PointCardShareTokenService.generate}
     * （{@code pointcard/service/PointCardShareTokenService.java:95}）が
     * {@code findByIdAndUserId} で保有者本人のカードを引き当ててからトークンを発行する。
     * 引き当て失敗時は {@code CARD_NOT_FOUND}（404）で秘匿し、トークンは 1 件も発行されない
     * （発行＝副作用は認可の後にのみ実行される）。</p>
     */
    @AuthorizedInService
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

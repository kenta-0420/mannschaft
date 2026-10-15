package com.mannschaft.app.succession.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.succession.dto.DelinquencyEscalationResponse;
import com.mannschaft.app.succession.dto.FreezeEscalationRequest;
import com.mannschaft.app.succession.dto.ResolveEscalationRequest;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.succession.service.DelinquencyEscalationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 滞納エスカレーションコントローラー（F09.15 S5-B）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.7
 *
 * <p>5 段階エスカレーション（STAGE_1_REMINDER 〜 STAGE_5_LEGAL_PREP）の
 * 一覧取得・詳細取得・凍結・解決のエンドポイントを提供する。
 * 操作は ADMIN 権限以上のユーザーのみ実行可能。
 *
 * <p>認可（{@code checkAdminOrAbove}）は Service 層で行う。本 Controller は
 * パスパラメータの組織 ID・escalationId と {@link com.mannschaft.app.common.SecurityUtils#getCurrentUserId()}
 * で解決した操作ユーザー ID の引き渡しのみを行う。
 */
@RestController
@Tag(name = "滞納エスカレーション（F09.15）", description = "F09.15 居住者継承支援 - 滞納エスカレーション管理 API")
@RequiredArgsConstructor
public class DelinquencyEscalationController {

    private final DelinquencyEscalationService delinquencyEscalationService;

    /**
     * 組織内の未解決エスカレーション一覧を取得する（ADMIN 以上）。
     *
     * <p>resolvedAt が null（未解決）のエスカレーションのみを返す。
     *
     * @param orgId テナント組織 ID
     * @return 200 OK + 未解決エスカレーション一覧
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/delinquency-escalations")
    @Operation(
            summary = "未解決エスカレーション一覧（ADMIN 以上）",
            description = "組織内の未解決（resolvedAt = null）滞納エスカレーション一覧を返す。"
    )
    public ResponseEntity<ApiResponse<List<DelinquencyEscalationResponse>>> listActive(
            @PathVariable Long orgId) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        List<DelinquencyEscalationResponse> responses = delinquencyEscalationService
                .listActive(orgId, requestingUserId)
                .stream()
                .map(DelinquencyEscalationResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 特定のエスカレーション詳細を取得する（ADMIN 以上）。
     *
     * @param orgId        テナント組織 ID
     * @param escalationId エスカレーション ID（UUID）
     * @return 200 OK + エスカレーション詳細
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/delinquency-escalations/{escalationId}")
    @Operation(
            summary = "エスカレーション詳細取得（ADMIN 以上）",
            description = "指定した escalationId のエスカレーションを取得する。"
    )
    public ResponseEntity<ApiResponse<DelinquencyEscalationResponse>> getById(
            @PathVariable Long orgId,
            @PathVariable UUID escalationId) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        DelinquencyEscalationEntity entity =
                delinquencyEscalationService.getById(escalationId, orgId, requestingUserId);
        return ResponseEntity.ok(ApiResponse.of(DelinquencyEscalationResponse.fromEntity(entity)));
    }

    /**
     * エスカレーションを凍結する（ADMIN 以上）。
     *
     * <p>弁護士介入・行政手続き等でエスカレーションの自動ステージ進行を一時停止する。
     * 凍結後は手動で解除するまで次のステージへ遷移しない。
     *
     * @param orgId        テナント組織 ID
     * @param escalationId エスカレーション ID（UUID）
     * @param request      凍結リクエスト（凍結理由）
     * @return 200 OK + 処理完了メッセージ
     */
    @PostMapping("/api/v1/organizations/{orgId}/succession/delinquency-escalations/{escalationId}/freeze")
    @Operation(
            summary = "エスカレーション凍結（ADMIN 以上）",
            description = "弁護士介入等の理由でエスカレーションの自動進行を凍結する。"
    )
    public ResponseEntity<ApiResponse<Map<String, String>>> freeze(
            @PathVariable Long orgId,
            @PathVariable UUID escalationId,
            @Valid @RequestBody FreezeEscalationRequest request) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        delinquencyEscalationService.freeze(escalationId, orgId, request.getReason(), requestingUserId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "frozen")));
    }

    /**
     * エスカレーションを解決済みに遷移させる（ADMIN 以上）。
     *
     * <p>滞納が解消された場合（支払い完了・死亡確認・手動クローズ等）に呼び出す。
     * resolvedAt がセットされ、それ以降のステージ遷移は停止する。
     *
     * @param orgId        テナント組織 ID
     * @param escalationId エスカレーション ID（UUID）
     * @param request      解決リクエスト（解決理由コード）
     * @return 200 OK + 処理完了メッセージ
     */
    @PostMapping("/api/v1/organizations/{orgId}/succession/delinquency-escalations/{escalationId}/resolve")
    @Operation(
            summary = "エスカレーション解決（ADMIN 以上）",
            description = "滞納解消（支払い完了・死亡確認・手動クローズ等）でエスカレーションを解決済みに遷移させる。"
    )
    public ResponseEntity<ApiResponse<Map<String, String>>> resolve(
            @PathVariable Long orgId,
            @PathVariable UUID escalationId,
            @Valid @RequestBody ResolveEscalationRequest request) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        delinquencyEscalationService.resolve(escalationId, orgId, request.getResolvedReason(), requestingUserId);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "resolved")));
    }
}

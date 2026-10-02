package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F20.3 ベータ特典: 利用者向け（本人・団体メンバー）の付与項目（設計書 02 §1.1）。
 *
 * <p><b>審査系フィールド（{@code review_flag}/{@code review_reason}/{@code criteria_snapshot}）は含めない</b>
 * （審査中であることを利用者に晒さない・03 §3・AC-A7）。{@code validUntil} は個人特典（INDIVIDUAL）では
 * {@code null}（サービス提供期間中無償・「永久」表現禁止・AC-13）、TEAM_ORG では由来 entitlements の
 * 最大 {@code valid_until}。</p>
 */
@Getter
@Builder
@Schema(name = "BetaPerkGrantItem", description = "F20.3 利用者向け ベータ特典 付与項目")
public class BetaGrantItem {

    @Schema(description = "付与 ID（UUID）")
    private final String grantId;

    @Schema(description = "ベータ段階", example = "2")
    private final int betaPhase;

    @Schema(description = "付与種別（INDIVIDUAL / TEAM_ORG）", example = "INDIVIDUAL")
    private final String grantKind;

    @Schema(description = "付与日時（ISO-8601）")
    private final LocalDateTime grantedAt;

    @Schema(description = "有効期限（INDIVIDUAL は null＝サービス提供期間中無償）", nullable = true)
    private final LocalDateTime validUntil;

    @Schema(description = "取消日時（未取消は null）", nullable = true)
    private final LocalDateTime revokedAt;

    @Schema(description = "付与された機能キー集合")
    private final List<String> featureKeys;

    @Schema(description = "付与時アクティブ人数スナップショット（INDIVIDUAL は null）", nullable = true, example = "34")
    private final Integer activeMemberCountSnapshot;
}

package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F20.3 ベータ特典: シスアド向けの付与詳細（設計書 02 §4.1）。
 *
 * <p>利用者向け {@link BetaGrantItem} のフィールドに加え、審査系（{@code reviewFlag}/{@code reviewReason}/
 * {@code reviewFlaggedAt}/{@code reviewResolvedAt}）・{@code criteriaSnapshot}・{@code grantedBy}/{@code note}・
 * スコープ情報を含む。<b>SYSTEM_ADMIN 専用 EP でのみ返す</b>（利用者向け EP では {@link BetaGrantItem} を使う・03 §3）。</p>
 *
 * <p><b>Phase3 追補（表示名）</b>: {@code scopeDisplayName}/{@code grantedByName} はシスアド審査画面が
 * 「誰への付与か」を ID ではなく名前で判別できるよう追加した。名前解決は
 * {@code com.mannschaft.app.billing.beta.BetaPerkScopeNameResolver} が
 * scope 種別ごとバルクで行い（N+1 回避）、本マッパーは解決済みの文字列を受け取るだけで越境しない。</p>
 */
@Getter
@Builder
@Schema(name = "BetaPerkGrantDetail", description = "F20.3 シスアド向け ベータ特典 付与詳細")
public class BetaGrantDetailResponse {

    @Schema(description = "付与 ID（UUID）")
    private final String grantId;

    @Schema(description = "ベータ段階", example = "2")
    private final int betaPhase;

    @Schema(description = "付与種別（INDIVIDUAL / TEAM_ORG）", example = "TEAM_ORG")
    private final String grantKind;

    @Schema(description = "スコープ種別（USER / TEAM / ORG）", example = "TEAM")
    private final String scopeKind;

    @Schema(description = "スコープ ID", example = "123")
    private final Long scopeId;

    @Schema(description = "スコープ表示名（USER=ユーザー表示名/TEAM=チーム名/ORG=組織名。解決不能なら null）",
            nullable = true, example = "第一少年野球部")
    private final String scopeDisplayName;

    @Schema(description = "テナント組織 ID（USER は null）", nullable = true, example = "45")
    private final Long organizationId;

    @Schema(description = "付与日時（ISO-8601）")
    private final LocalDateTime grantedAt;

    @Schema(description = "有効期限（INDIVIDUAL は null）", nullable = true)
    private final LocalDateTime validUntil;

    @Schema(description = "付与された機能キー集合")
    private final List<String> featureKeys;

    @Schema(description = "付与時アクティブ人数スナップショット（INDIVIDUAL は null）", nullable = true)
    private final Integer activeMemberCountSnapshot;

    @Schema(description = "付与時の実測値/閾値の焼き付け（JSON 相当）", nullable = true)
    private final Object criteriaSnapshot;

    @Schema(description = "審査待ちフラグ", example = "false")
    private final boolean reviewFlag;

    @Schema(description = "審査フラグ事由（未フラグは null）", nullable = true, example = "MANUAL")
    private final String reviewReason;

    @Schema(description = "審査フラグ設定日時", nullable = true)
    private final LocalDateTime reviewFlaggedAt;

    @Schema(description = "審査解決日時", nullable = true)
    private final LocalDateTime reviewResolvedAt;

    @Schema(description = "取消日時（未取消は null）", nullable = true)
    private final LocalDateTime revokedAt;

    @Schema(description = "取消事由（未取消は null）", nullable = true, example = "TERMS_VIOLATION")
    private final String revokeReason;

    @Schema(description = "付与操作者 userId（自動付与バッチは null=SYSTEM）", nullable = true)
    private final Long grantedBy;

    @Schema(description = "付与操作者の表示名（grantedBy が null=自動付与バッチなら null・FE側でSYSTEM表示）",
            nullable = true, example = "山田太郎")
    private final String grantedByName;
}

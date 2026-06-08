package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.Sport;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 試合作成リクエスト（F08.10・02 §F / 03 §C.4a）。
 *
 * <p><b>マスアサインメント防止</b>: {@code teamId}（主体チーム）・{@code createdBy}（作成者）・
 * {@code organizationId}（テナント）は<b>Request DTO に含めない</b>。
 * Controller がパスパラメータ（{@code orgId}/{@code teamId}）と認証主体（{@code SecurityUtils}）から
 * サーバー導出し、{@link com.mannschaft.app.match.service.MatchService.CreateCommand} へ詰める（03 §C.4a）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C.4a / §C.4b</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateMatchRequest {

    /** 競技（任意・既定 SOCCER）。 */
    private Sport sport;

    /** 試合種別（必須・03 §C）。 */
    @NotNull
    private MatchKind kind;

    /** 大会 fixture リンク（任意・tournament ドメイン ID 参照）。 */
    private Long tournamentFixtureId;

    /** カレンダー連携（任意・schedule ドメイン ID 参照）。 */
    private Long scheduleId;

    /** ホーム/アウェイ/中立（任意・既定 HOME）。 */
    private HomeAway homeAway;

    /** 登録相手チーム（任意・team ドメイン ID 参照）。相手未登録なら null とし {@code opponentName} を使う。 */
    private Long opponentTeamId;

    /** 手入力の相手名（{@code opponentTeamId} が null の場合に必須・03 §C）。 */
    @Size(max = 128)
    private String opponentName;

    /** キックオフ日時（サーバー TZ・02 §F.1 TZ 方針）。 */
    private LocalDateTime kickoffAt;

    @Size(max = 200)
    private String venue;

    /** 試合通算分（前後半＋延長を含む・02 §E.1）。 */
    @Min(0)
    private Integer durationMinutes;

    @Size(max = 32)
    private String periodFormat;

    /** 記録モード（true=公式戦＝記録係単独入力 / false=共同記録・03 §C.1）。 */
    private boolean hasScorekeeper;

    /** 記録係ユーザー（公式戦時・user ドメイン ID 参照・03 §C.1）。 */
    private Long scorekeeperUserId;

    @Size(max = 2000)
    private String notes;
}

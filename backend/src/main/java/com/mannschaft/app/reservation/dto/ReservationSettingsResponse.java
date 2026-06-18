package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 予約設定（チームポリシー）のレスポンス DTO。
 *
 * <p>F03.4 残ギャップMVP: 2 つの別テーブル（{@code reservation_team_settings}・
 * {@code reservation_policies}）をコントローラーで束ね、1 レスポンスに統合する。</p>
 *
 * <ul>
 *   <li>{@code allowPublicReservation} … {@code reservation_team_settings.allow_public_reservation}</li>
 *   <li>{@code approvalMode} / {@code cancelDeadlineHours} / {@code remindBeforeHours}
 *       … {@code reservation_policies}（レコード無しは既定値 AUTO / 24 / "24,1"）</li>
 * </ul>
 *
 * <p>テーブルは混ぜず別テーブルのまま維持し、本 DTO で表示用に統合するだけ（アーキ原則維持）。</p>
 */
@Getter
@Builder
@Schema(description = "予約設定（チームポリシー）のレスポンス")
public class ReservationSettingsResponse {

    @Schema(description = "チームID", example = "10")
    private final Long teamId;

    @Schema(description = "営業時間が設定済みか", example = "true")
    private final boolean hasBusinessHours;

    @Schema(description = "一般公開予約を許可するか（true=ログイン済みなら誰でも予約可 / false=チーム所属者のみ）",
            example = "false")
    private final boolean allowPublicReservation;

    @Schema(description = "承認モードの既定値。AUTO=自動承認 / MANUAL=管理者の手動承認",
            example = "AUTO")
    private final ApprovalMode approvalMode;

    @Schema(description = "キャンセル受付の締切（予約開始の何時間前まで）", example = "24")
    private final int cancelDeadlineHours;

    @Schema(description = "リマインド送信タイミング（予約開始の何時間前か）の CSV 文字列",
            example = "24,1")
    private final String remindBeforeHours;
}

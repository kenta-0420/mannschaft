package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.ReservationResourceNameType;
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
 *   <li>{@code allowPublicReservation} / {@code resourceNameType} / {@code resourceNameCustom}
 *       … {@code reservation_team_settings}（レコード無しは既定値 false / DEFAULT / null。
 *       呼称設定は F03.4.5 §5）</li>
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

    @Schema(description = "予約対象の呼称プリセット。DEFAULT=未設定（従来の『予約対象』表示）",
            example = "SEAT")
    private final ReservationResourceNameType resourceNameType;

    @Schema(description = "自由入力の呼称（resourceNameType=CUSTOM のときのみ非 null）",
            example = "施術台", nullable = true)
    private final String resourceNameCustom;

    /**
     * 仮押さえ(PENDING)の自動失効までの時間数（F03.4.5 §6.3・W2-6）。
     *
     * <p>{@code null} = 自動失効しない。ポリシー行が無いチームは既定値 24 を返す
     * （{@code ReservationPolicyService#getOrDefault}）。</p>
     */
    @Schema(description = "仮押さえ(PENDING)を自動キャンセルするまでの時間数（null=自動失効しない）",
            example = "24", nullable = true)
    private final Integer pendingExpireHours;
}

package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 予約設定（チームポリシー）の更新リクエストDTO。ADMIN 限定操作。
 *
 * <p>PATCH の部分更新セマンティクス: 全フィールド任意（null = 据え置き）。</p>
 *
 * <ul>
 *   <li>{@code allowPublicReservation} … {@code reservation_team_settings.allow_public_reservation} を更新</li>
 *   <li>{@code approvalMode} / {@code cancelDeadlineHours} / {@code remindBeforeHours}
 *       … {@code reservation_policies} を upsert 更新</li>
 * </ul>
 *
 * <p>入力検証（Bean Validation・400）:</p>
 * <ul>
 *   <li>{@code approvalMode} … enum 値（AUTO/MANUAL）以外は Jackson のバインドで弾かれる（400）</li>
 *   <li>{@code cancelDeadlineHours} … 0〜8760（最大 1 年）の範囲</li>
 *   <li>{@code remindBeforeHours} … 正の整数のカンマ区切り（CSV of positive ints）</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public class UpdateReservationSettingRequest {

    /** 一般公開予約を許可するか（true=ログイン済みなら誰でも予約可 / false=チーム所属者のみ / null=据え置き）。 */
    @Schema(description = "一般公開予約を許可するか（null=据え置き）", example = "false", nullable = true)
    private final Boolean allowPublicReservation;

    /** 承認モード（AUTO/MANUAL / null=据え置き）。enum 以外は 400。 */
    @Schema(description = "承認モードの既定値（AUTO/MANUAL / null=据え置き）", example = "AUTO", nullable = true)
    private final ApprovalMode approvalMode;

    /** キャンセル締切（予約開始の何時間前まで・0〜8760 / null=据え置き）。 */
    @Min(0)
    @Max(8760)
    @Schema(description = "キャンセル受付の締切（予約開始の何時間前まで・0〜8760 / null=据え置き）",
            example = "24", nullable = true)
    private final Integer cancelDeadlineHours;

    /** リマインド送信タイミングの CSV（正の整数のカンマ区切り / null=据え置き）。 */
    @Pattern(regexp = "^[1-9][0-9]*(,[1-9][0-9]*)*$",
            message = "remindBeforeHours は正の整数のカンマ区切り（例: 24,1）で指定してください")
    @Schema(description = "リマインド送信タイミング（予約開始の何時間前か）の CSV 文字列（null=据え置き）",
            example = "24,1", nullable = true)
    private final String remindBeforeHours;
}

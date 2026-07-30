package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.ReservationResourceNameType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
 *   <li>{@code resourceNameType} / {@code resourceNameCustom} … {@code reservation_team_settings} の
 *       呼称カラムを更新（F03.4.5 §5）。反映後のタイプが {@code CUSTOM} 以外なら custom は
 *       NULL へ正規化・{@code CUSTOM} なら custom 非空必須（Service 層で検証）</li>
 * </ul>
 *
 * <p>入力検証（Bean Validation・400）:</p>
 * <ul>
 *   <li>{@code approvalMode} … enum 値（AUTO/MANUAL）以外は Jackson のバインドで弾かれる（400）</li>
 *   <li>{@code cancelDeadlineHours} … 0〜8760（最大 1 年）の範囲</li>
 *   <li>{@code remindBeforeHours} … 正の整数のカンマ区切り（CSV of positive ints）</li>
 *   <li>{@code resourceNameType} … enum 値以外は Jackson のバインドで弾かれる（400）</li>
 *   <li>{@code resourceNameCustom} … 30 文字以内（UI 幅由来。超過は 400）</li>
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

    /**
     * 予約対象の呼称プリセット（DEFAULT/STAFF/SEAT/COURT/BED/LANE/CUSTOM / null=据え置き）。
     * enum 以外は 400。{@code CUSTOM} を指定する場合は {@code resourceNameCustom} も合わせて指定すること
     * （反映後に custom が空だと 400）。
     */
    @Schema(description = "予約対象の呼称プリセット（null=据え置き）", example = "SEAT", nullable = true)
    private final ReservationResourceNameType resourceNameType;

    /**
     * 自由入力の呼称（{@code resourceNameType=CUSTOM} のときのみ有効・30文字以内 / null=据え置き）。
     * 反映後のタイプが {@code CUSTOM} 以外の場合はサービス層で常に NULL へ正規化される。
     */
    @Size(max = 30, message = "resourceNameCustom は30文字以内で指定してください")
    @Schema(description = "自由入力の呼称（CUSTOM 選択時のみ有効・30文字以内 / null=据え置き）",
            example = "施術台", nullable = true)
    private final String resourceNameCustom;

    /**
     * 仮押さえ(PENDING)の自動失効までの時間数（F03.4.5 §6.3・W2-6）。
     *
     * <p>1〜168（1 時間〜7 日）。範囲外は 400。{@code null} / 未指定は据え置き。
     * 自動失効そのものを止めたい場合は {@link #clearPendingExpireHours} を {@code true} にする
     * （部分更新セマンティクスでは null が「据え置き」を意味するため値としての NULL を送れない）。</p>
     */
    @Min(1)
    @Max(168)
    @Schema(description = "仮押さえ(PENDING)を自動キャンセルするまでの時間数（1〜168 / null=据え置き）",
            example = "24", nullable = true)
    private final Integer pendingExpireHours;

    /**
     * 仮押さえ自動失効を無効化する（任意・{@code UpdateSlotRequest.clearApprovalMode} と同形）。
     *
     * <p>{@code true} の場合 {@code pending_expire_hours} を {@code NULL}（自動失効しない）へ戻す。
     * <b>{@link #pendingExpireHours} と同時に指定された場合は clear を優先</b>する
     * （「無効化したい」という意図の方が強いため）。{@code null} / {@code false} / 未指定は据え置き。</p>
     */
    @Schema(description = "仮押さえ自動失効を無効化するか（true=自動失効しない。pendingExpireHours より優先）",
            example = "false", nullable = true)
    private final Boolean clearPendingExpireHours;
}

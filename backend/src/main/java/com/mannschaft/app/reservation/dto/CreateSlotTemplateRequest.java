package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 週間テンプレート作成リクエストDTO（F03.4.2 §4）。
 *
 * <p>親 §4.B の Jackson 手本（{@code @Getter @RequiredArgsConstructor}＋全 final＋単一コンストラクタ）に倣う。
 * 既定値（capacity=1）は Service 層で null→1 正規化する。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateSlotTemplateRequest {

    /** テンプレ名（管理用メモ・任意）。 */
    @Size(max = 100)
    private final String name;

    /** 対象ライン。NULL = 共通枠テンプレ。不正 ID は 400（LINE_NOT_FOUND=001 再利用）。 */
    private final Long lineId;

    /**
     * 曜日。<b>3文字大文字 {@code MON}..{@code SUN} のみ</b>。
     * 不正値（{@code MONDAY}/小文字/その他）は Jackson の enum デシリアライズ失敗で 400（§4）。
     */
    @NotNull
    private final ReservationDayOfWeek dayOfWeek;

    /** 帯の開始（30分単位・007/022 再利用）。 */
    @NotNull
    private final LocalTime startTime;

    /** 帯の終了（30分単位・start より後・007/022 再利用）。 */
    @NotNull
    private final LocalTime endTime;

    /** 生成する各セル枠の定員（1〜99）。省略時 1。 */
    @Min(1)
    @Max(99)
    private final Integer capacity;

    /** 生成枠の担当スタッフ（任意）。 */
    private final Long staffUserId;

    /** 生成枠の title へコピー（任意）。 */
    @Size(max = 200)
    private final String title;

    /** 生成枠の price へコピー（表示用・任意・0 以上）。 */
    @DecimalMin("0")
    private final BigDecimal price;

    /** 生成枠の枠単位承認モード上書き。NULL = チーム既定継承。 */
    private final ApprovalMode approvalMode;
}

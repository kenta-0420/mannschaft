package com.mannschaft.app.shift.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * メンバー勤務制約の作成・更新リクエスト DTO（F03.5 v2 新規）。
 *
 * <p>全項目 NULL 可能（オプトイン方式）だが、全て NULL のレコードは Service 層で拒否される
 * ({@code ShiftErrorCode.WORK_CONSTRAINT_ALL_NULL})。DB 層にも CHECK 制約があり、二重防御。</p>
 *
 * <p>バリデーション方針: 負値や極端に大きい値のみ弾く。上限値の現実性チェック（月 700 時間等）は
 * 運用判断に委ねるため、ここでは極めて緩いレンジで制限する。</p>
 */
@Getter
@RequiredArgsConstructor
public class MemberWorkConstraintRequest {

    /** 月次労働時間上限（h）。NULL = 制約なし。 */
    @DecimalMin(value = "0.00", message = "月次労働時間上限は 0 以上である必要があります")
    @DecimalMax(value = "9999.99", message = "月次労働時間上限が不正です")
    private final BigDecimal maxMonthlyHours;

    /** 月次勤務日数上限。NULL = 制約なし。 */
    @Min(value = 0, message = "月次勤務日数上限は 0 以上である必要があります")
    @Max(value = 31, message = "月次勤務日数上限は 31 以下である必要があります")
    private final Integer maxMonthlyDays;

    /** 連続勤務日数上限。NULL = 制約なし。 */
    @Min(value = 0, message = "連続勤務日数上限は 0 以上である必要があります")
    @Max(value = 31, message = "連続勤務日数上限は 31 以下である必要があります")
    private final Integer maxConsecutiveDays;

    /** 月次夜勤数上限。NULL = 制約なし。 */
    @Min(value = 0, message = "月次夜勤数上限は 0 以上である必要があります")
    @Max(value = 31, message = "月次夜勤数上限は 31 以下である必要があります")
    private final Integer maxNightShiftsPerMonth;

    /** シフト間の最低休息時間（h）。NULL = 制約なし。 */
    @DecimalMin(value = "0.00", message = "シフト間休息時間は 0 以上である必要があります")
    @DecimalMax(value = "99.99", message = "シフト間休息時間が不正です")
    private final BigDecimal minRestHoursBetweenShifts;

    /** 運用メモ。 */
    @Size(max = 500, message = "メモは 500 文字以内である必要があります")
    private final String note;

    /**
     * 全項目が NULL かどうかを判定する。DTO レベルの「全NULL拒否」ガード用ヘルパ。
     * {@code note} は運用メモのため NULL 判定から除外する。
     *
     * @return {@code true} = 全ての制約項目が NULL
     */
    public boolean isAllConstraintsNull() {
        return maxMonthlyHours == null
                && maxMonthlyDays == null
                && maxConsecutiveDays == null
                && maxNightShiftsPerMonth == null
                && minRestHoursBetweenShifts == null;
    }
}

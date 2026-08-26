package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 予約スロット更新リクエストDTO。
 *
 * <p>部分更新（PATCH）。{@code null} のフィールドは「未指定＝据え置き」を意味する。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateSlotRequest {

    private final Long staffUserId;

    @Size(max = 200)
    private final String title;

    private final LocalDate slotDate;

    private final LocalTime startTime;

    private final LocalTime endTime;

    /**
     * ライン軸の変更（任意・F03.4.2 §4）。
     *
     * <p>{@code null} / 未指定 = 据え置き（他フィールドと同じ部分更新セマンティクス）。
     * 当該チームの active ライン以外は 400（LINE_NOT_FOUND=001 再利用）。</p>
     */
    private final Long lineId;

    private final BigDecimal price;

    @Size(max = 2000)
    private final String note;

    /**
     * 枠単位の承認モード上書き（任意）。
     *
     * <p>値（{@code AUTO} / {@code MANUAL}）を指定するとこの枠の上書きを設定する。
     * {@code null} / 未指定の場合は据え置き（他フィールドと同じ部分更新セマンティクス）。
     * 上書きを解除して「チーム既定に従う」へ戻す場合は {@link #clearApprovalMode} を {@code true} にする。
     * 不正な enum 値は Jackson のデシリアライズ段階で 400 となる。</p>
     */
    private final ApprovalMode approvalMode;

    /**
     * 枠単位の承認モード上書きを解除する（任意）。
     *
     * <p>{@code true} の場合、{@code approvalMode} を {@code null}（チーム既定に従う）へ戻す。
     * {@code true} のとき {@link #approvalMode} は無視される。
     * {@code null} / {@code false} / 未指定の場合は据え置き。</p>
     */
    private final Boolean clearApprovalMode;

    /**
     * 予約枠の定員（任意・部分更新）。
     *
     * <p>{@code null} / 未指定 = 据え置き（他フィールドと同じ部分更新セマンティクス）。
     * 値を指定すると定員を変更し、予約数との関係で満席/空きを再評価する
     * （定員を減らして {@code booked_count >= capacity} なら FULL、増やして下回れば AVAILABLE へ復帰）。
     * 1 以上を指定すること。</p>
     */
    @Min(1)
    private final Integer capacity;
}

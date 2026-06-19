package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
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
}

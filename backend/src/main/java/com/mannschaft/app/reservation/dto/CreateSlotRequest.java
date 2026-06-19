package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 予約スロット作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateSlotRequest {

    private final Long staffUserId;

    @Size(max = 200)
    private final String title;

    @NotNull
    private final LocalDate slotDate;

    @NotNull
    private final LocalTime startTime;

    @NotNull
    private final LocalTime endTime;

    private final String recurrenceRule;

    private final BigDecimal price;

    @Size(max = 2000)
    private final String note;

    /**
     * 枠単位の承認モード上書き（任意）。
     *
     * <p>{@code null} / 未指定 = チーム既定（{@code reservation_policies}）に従う。
     * {@code AUTO} / {@code MANUAL} を指定するとこの枠だけチーム既定を上書きする。
     * 不正な enum 値は Jackson のデシリアライズ段階で 400 となる。</p>
     */
    private final ApprovalMode approvalMode;
}

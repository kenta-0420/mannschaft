package com.mannschaft.app.reservation.dto;

import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 週間テンプレート部分更新リクエストDTO（F03.4.2 §4 PATCH）。
 *
 * <p>null = 据え置き。{@code clearLineId=true} で共通枠テンプレへ戻す
 * （親の {@code clearApprovalMode} と同形）。<b>更新は既生成枠へ遡及しない</b>（§5.4）。</p>
 */
@Getter
@RequiredArgsConstructor
public class UpdateSlotTemplateRequest {

    @Size(max = 100)
    private final String name;

    /** 対象ラインの変更。null = 据え置き（共通枠へ戻すには clearLineId=true）。 */
    private final Long lineId;

    /** true でラインの指定を解除し共通枠テンプレへ戻す。 */
    private final Boolean clearLineId;

    private final ReservationDayOfWeek dayOfWeek;

    private final LocalTime startTime;

    private final LocalTime endTime;

    @Min(1)
    @Max(99)
    private final Integer capacity;

    private final Long staffUserId;

    @Size(max = 200)
    private final String title;

    @DecimalMin("0")
    private final BigDecimal price;

    private final ApprovalMode approvalMode;

    /** isActive の切替（生成対象 ON/OFF）。null = 据え置き。 */
    private final Boolean isActive;
}

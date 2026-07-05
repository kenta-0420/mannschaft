package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 予約スロットレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ReservationSlotResponse {

    Long id;
    Long teamId;
    Long staffUserId;
    /** ライン軸（F03.4.2）。null = 共通枠（既存互換）。 */
    Long lineId;
    /** ライン名（lineId 非 null のとき Service 層で一括解決。null = 共通枠）。 */
    String lineName;
    /** 生成元テンプレート（F03.4.2）。null = 手動作成枠。 */
    java.util.UUID templateId;
    SlotBasicDto basic;
    SlotStatusDto status;
    RecurrenceDto recurrence;
    SlotPricingDto pricing;
    SlotPolicyDto policy;
    SlotAuditDto audit;

    public record SlotBasicDto(String title, LocalDate slotDate, LocalTime startTime, LocalTime endTime) {}

    public record SlotStatusDto(String slotStatus, Integer bookedCount, Integer capacity, Boolean isException, String closedReason, String note) {}

    public record RecurrenceDto(String recurrenceRule, Long parentSlotId) {}

    public record SlotPricingDto(BigDecimal price) {}

    /**
     * 枠単位の承認モード上書き。
     *
     * @param approvalMode この枠で上書きされた承認モード（{@code AUTO} / {@code MANUAL}）。
     *                     {@code null} = 上書きなし（チーム既定に従う）。
     */
    public record SlotPolicyDto(String approvalMode) {}

    public record SlotAuditDto(Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}

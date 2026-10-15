package com.mannschaft.app.facility.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 施設予約詳細レスポンスDTO。
 * フィールドを論理グループ（サブDTO）にネストして整理する。
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookingDetailResponse {

    private Long id;
    private String status;
    private BookingFacilityDto facility;
    private BookingScheduleDto schedule;
    private BookingUsageDto usage;
    private BookingFeeDto fee;
    private BookingApprovalDto approval;
    private BookingLifecycleDto lifecycle;
    private List<BookingEquipmentResponse> equipment;
    private BookingAuditDto audit;

    /** 施設・予約者情報 */
    public record BookingFacilityDto(
            Long facilityId,
            String facilityName,
            Long bookedBy,
            Long createdByAdmin
    ) {}

    /** 日程・時間スロット情報 */
    public record BookingScheduleDto(
            LocalDate bookingDate,
            LocalDate checkOutDate,
            Integer stayNights,
            LocalTime timeFrom,
            LocalTime timeTo,
            Integer slotCount
    ) {}

    /** 利用目的・参加者情報 */
    public record BookingUsageDto(
            String purpose,
            Integer attendeeCount
    ) {}

    /** 料金情報 */
    public record BookingFeeDto(
            BigDecimal usageFee,
            BigDecimal equipmentFee,
            BigDecimal totalFee
    ) {}

    /** 承認情報 */
    public record BookingApprovalDto(
            Long approvedBy,
            LocalDateTime approvedAt,
            String adminComment
    ) {}

    /** ライフサイクル情報（チェックイン・完了・キャンセル） */
    public record BookingLifecycleDto(
            LocalDateTime checkedInAt,
            LocalDateTime completedAt,
            LocalDateTime cancelledAt,
            Long cancelledBy,
            String cancellationReason
    ) {}

    /** 監査情報（作成・更新日時） */
    public record BookingAuditDto(
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    /**
     * 予約備品レスポンス。
     * equipment は MapStruct で ignore=true のため toBuilder() で後設定する。
     */
    @Getter
    @Builder
    public static class BookingEquipmentResponse {
        private final Long equipmentId;
        private final String equipmentName;
        private final Integer quantity;
        private final BigDecimal unitPrice;
        private final BigDecimal subtotal;
    }

    /**
     * equipment を後から設定するファクトリメソッド。
     * mapper では equipment=ignore のため、サービス層でこのメソッドを使って設定する。
     */
    public BookingDetailResponse withEquipment(List<BookingEquipmentResponse> equipment) {
        return this.toBuilder().equipment(equipment).build();
    }
}

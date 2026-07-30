package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.ReminderResponse;
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 予約機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ReservationMapper {

    @Mapping(target = "meta", expression = "java(new com.mannschaft.app.reservation.dto.ReservationLineResponse.LineMetaDto(entity.getName(), entity.getDescription(), entity.getDisplayOrder(), entity.getIsActive(), entity.getDefaultStaffUserId()))")
    @Mapping(target = "audit", expression = "java(new com.mannschaft.app.reservation.dto.ReservationLineResponse.ReservationLineAuditDto(entity.getCreatedAt(), entity.getUpdatedAt()))")
    ReservationLineResponse toLineResponse(ReservationLineEntity entity);

    List<ReservationLineResponse> toLineResponseList(List<ReservationLineEntity> entities);

    // F03.4.2: lineName（ライン名）は NameResolver と同じ発想で Service 層が一括解決して後付けする（ここでは null）。
    @Mapping(target = "lineName", ignore = true)
    @Mapping(target = "basic", expression = "java(new com.mannschaft.app.reservation.dto.ReservationSlotResponse.SlotBasicDto(entity.getTitle(), entity.getSlotDate(), entity.getStartTime(), entity.getEndTime()))")
    @Mapping(target = "status", expression = "java(new com.mannschaft.app.reservation.dto.ReservationSlotResponse.SlotStatusDto(entity.getSlotStatus() != null ? entity.getSlotStatus().name() : null, entity.getBookedCount(), entity.getCapacity(), entity.getIsException(), entity.getClosedReason(), entity.getNote()))")
    @Mapping(target = "recurrence", expression = "java(new com.mannschaft.app.reservation.dto.ReservationSlotResponse.RecurrenceDto(entity.getRecurrenceRule(), entity.getParentSlotId()))")
    @Mapping(target = "pricing", expression = "java(new com.mannschaft.app.reservation.dto.ReservationSlotResponse.SlotPricingDto(entity.getPrice()))")
    @Mapping(target = "policy", expression = "java(new com.mannschaft.app.reservation.dto.ReservationSlotResponse.SlotPolicyDto(entity.getApprovalMode() != null ? entity.getApprovalMode().name() : null))")
    @Mapping(target = "audit", expression = "java(new com.mannschaft.app.reservation.dto.ReservationSlotResponse.SlotAuditDto(entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt()))")
    ReservationSlotResponse toSlotResponse(ReservationSlotEntity entity);

    List<ReservationSlotResponse> toSlotResponseList(List<ReservationSlotEntity> entities);

    @Mapping(target = "slot", ignore = true)
    // F03.4.3: group（予約グループ要約）は代表行の兄弟行集約が必要なため Service 層が一括解決して後付けする（ここでは null）。
    @Mapping(target = "group", ignore = true)
    // F03.4.5 §6.2 W2-5: series 要約・結果明細（recurring / recurringCancel / recurringConfirm）は
    // 「操作の結果」であってエンティティ 1 行から導出できるものではない（複数週の成立/スキップ集計・
    // キャンセル/承認の明細）。Service 層が操作直後に組み立てて後付けするため、ここでは null にする。
    @Mapping(target = "recurring", ignore = true)
    @Mapping(target = "recurringCancel", ignore = true)
    @Mapping(target = "recurringConfirm", ignore = true)
    // 一方 recurringSeriesId は<b>エンティティが持つ属性</b>なので必ず写す（ignore にしない）。
    // これが無いと一覧・詳細 GET から「この予約が定期予約の一部か」を判定できず、
    // FE のキャンセルスコープ 2 択 UI・series 一括承認ボタンが実装不能になる（検分 MUST①）。
    // MapStruct は同名フィールドを自動写像するが、意図を明示して将来の ignore 追加を防ぐ。
    @Mapping(target = "recurringSeriesId", source = "recurringSeriesId")
    @Mapping(target = "identifier", expression = "java(new com.mannschaft.app.reservation.dto.ReservationResponse.ReservationIdentifierDto(entity.getReservationSlotId(), entity.getLineId(), entity.getTeamId(), entity.getUserId(), null))")
    @Mapping(target = "status", expression = "java(new com.mannschaft.app.reservation.dto.ReservationResponse.ReservationStatusDto(entity.getStatus() != null ? entity.getStatus().name() : null, entity.getBookedAt(), entity.getConfirmedAt(), entity.getCompletedAt()))")
    @Mapping(target = "cancellation", expression = "java(new com.mannschaft.app.reservation.dto.ReservationResponse.CancellationDto(entity.getCancelledAt(), entity.getCancelReason(), entity.getCancelledBy() != null ? entity.getCancelledBy().name() : null))")
    @Mapping(target = "notes", expression = "java(new com.mannschaft.app.reservation.dto.ReservationResponse.NotesDto(entity.getUserNote(), entity.getAdminNote()))")
    @Mapping(target = "audit", expression = "java(new com.mannschaft.app.reservation.dto.ReservationResponse.ReservationAuditDto(entity.getCreatedAt(), entity.getUpdatedAt()))")
    ReservationResponse toReservationResponse(ReservationEntity entity);

    List<ReservationResponse> toReservationResponseList(List<ReservationEntity> entities);

    /**
     * 予約に紐付くスロット・ラインのサマリ情報を付与して変換する。
     *
     * @param entity 予約エンティティ
     * @param slot   紐付くスロットエンティティ（null 可）
     * @param line   紐付くラインエンティティ（null 可）
     * @return スロットサマリを含む予約レスポンス
     */
    default ReservationResponse toReservationResponse(
            ReservationEntity entity,
            ReservationSlotEntity slot,
            ReservationLineEntity line) {
        ReservationResponse base = toReservationResponse(entity);
        return base.toBuilder()
                .slot(new ReservationResponse.SlotSummaryDto(
                        line != null ? line.getName() : null,
                        slot != null ? slot.getTitle() : null,
                        slot != null ? slot.getSlotDate() : null,
                        slot != null ? slot.getStartTime() : null,
                        slot != null ? slot.getEndTime() : null))
                .build();
    }

    @Mapping(target = "businessStatus", expression = "java(new com.mannschaft.app.reservation.dto.BusinessHourResponse.BusinessStatusDto(entity.getDayOfWeek(), entity.getIsOpen(), entity.getOpenTime(), entity.getCloseTime()))")
    BusinessHourResponse toBusinessHourResponse(ReservationBusinessHourEntity entity);

    List<BusinessHourResponse> toBusinessHourResponseList(List<ReservationBusinessHourEntity> entities);

    @Mapping(target = "timeSlot", expression = "java(new com.mannschaft.app.reservation.dto.BlockedTimeResponse.TimeSlotDto(entity.getBlockedDate(), entity.getStartTime(), entity.getEndTime()))")
    // 機能B: resourceName（STAFF 時の担当スタッフ表示名）は NameResolver 一括解決のため Service 層で後付けする（ここでは null）。
    @Mapping(target = "resource", expression = "java(new com.mannschaft.app.reservation.dto.BlockedTimeResponse.ResourceDto(entity.getResourceType() != null ? entity.getResourceType().name() : null, entity.getResourceId(), null))")
    @Mapping(target = "audit", expression = "java(new com.mannschaft.app.reservation.dto.BlockedTimeResponse.BlockedAuditDto(entity.getReason(), entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt()))")
    BlockedTimeResponse toBlockedTimeResponse(ReservationBlockedTimeEntity entity);

    List<BlockedTimeResponse> toBlockedTimeResponseList(List<ReservationBlockedTimeEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ReminderResponse toReminderResponse(ReservationReminderEntity entity);

    List<ReminderResponse> toReminderResponseList(List<ReservationReminderEntity> entities);
}

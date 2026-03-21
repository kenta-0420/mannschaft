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

    ReservationLineResponse toLineResponse(ReservationLineEntity entity);

    List<ReservationLineResponse> toLineResponseList(List<ReservationLineEntity> entities);

    @Mapping(target = "slotStatus", expression = "java(entity.getSlotStatus().name())")
    ReservationSlotResponse toSlotResponse(ReservationSlotEntity entity);

    List<ReservationSlotResponse> toSlotResponseList(List<ReservationSlotEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "cancelledBy", expression = "java(entity.getCancelledBy() != null ? entity.getCancelledBy().name() : null)")
    ReservationResponse toReservationResponse(ReservationEntity entity);

    List<ReservationResponse> toReservationResponseList(List<ReservationEntity> entities);

    BusinessHourResponse toBusinessHourResponse(ReservationBusinessHourEntity entity);

    List<BusinessHourResponse> toBusinessHourResponseList(List<ReservationBusinessHourEntity> entities);

    BlockedTimeResponse toBlockedTimeResponse(ReservationBlockedTimeEntity entity);

    List<BlockedTimeResponse> toBlockedTimeResponseList(List<ReservationBlockedTimeEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ReminderResponse toReminderResponse(ReservationReminderEntity entity);

    List<ReminderResponse> toReminderResponseList(List<ReservationReminderEntity> entities);
}

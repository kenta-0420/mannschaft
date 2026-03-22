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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-03-22T15:42:11+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ReservationMapperImpl implements ReservationMapper {

    @Override
    public ReservationLineResponse toLineResponse(ReservationLineEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        String name = null;
        String description = null;
        Integer displayOrder = null;
        Boolean isActive = null;
        Long defaultStaffUserId = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        name = entity.getName();
        description = entity.getDescription();
        displayOrder = entity.getDisplayOrder();
        isActive = entity.getIsActive();
        defaultStaffUserId = entity.getDefaultStaffUserId();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        ReservationLineResponse reservationLineResponse = new ReservationLineResponse( id, teamId, name, description, displayOrder, isActive, defaultStaffUserId, createdAt, updatedAt );

        return reservationLineResponse;
    }

    @Override
    public List<ReservationLineResponse> toLineResponseList(List<ReservationLineEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReservationLineResponse> list = new ArrayList<ReservationLineResponse>( entities.size() );
        for ( ReservationLineEntity reservationLineEntity : entities ) {
            list.add( toLineResponse( reservationLineEntity ) );
        }

        return list;
    }

    @Override
    public ReservationSlotResponse toSlotResponse(ReservationSlotEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        Long staffUserId = null;
        String title = null;
        LocalDate slotDate = null;
        LocalTime startTime = null;
        LocalTime endTime = null;
        Integer bookedCount = null;
        String recurrenceRule = null;
        Long parentSlotId = null;
        Boolean isException = null;
        BigDecimal price = null;
        String closedReason = null;
        String note = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        staffUserId = entity.getStaffUserId();
        title = entity.getTitle();
        slotDate = entity.getSlotDate();
        startTime = entity.getStartTime();
        endTime = entity.getEndTime();
        bookedCount = entity.getBookedCount();
        recurrenceRule = entity.getRecurrenceRule();
        parentSlotId = entity.getParentSlotId();
        isException = entity.getIsException();
        price = entity.getPrice();
        closedReason = entity.getClosedReason();
        note = entity.getNote();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String slotStatus = entity.getSlotStatus().name();

        ReservationSlotResponse reservationSlotResponse = new ReservationSlotResponse( id, teamId, staffUserId, title, slotDate, startTime, endTime, bookedCount, slotStatus, recurrenceRule, parentSlotId, isException, price, closedReason, note, createdBy, createdAt, updatedAt );

        return reservationSlotResponse;
    }

    @Override
    public List<ReservationSlotResponse> toSlotResponseList(List<ReservationSlotEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReservationSlotResponse> list = new ArrayList<ReservationSlotResponse>( entities.size() );
        for ( ReservationSlotEntity reservationSlotEntity : entities ) {
            list.add( toSlotResponse( reservationSlotEntity ) );
        }

        return list;
    }

    @Override
    public ReservationResponse toReservationResponse(ReservationEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long reservationSlotId = null;
        Long lineId = null;
        Long teamId = null;
        Long userId = null;
        LocalDateTime bookedAt = null;
        LocalDateTime confirmedAt = null;
        LocalDateTime cancelledAt = null;
        String cancelReason = null;
        LocalDateTime completedAt = null;
        String userNote = null;
        String adminNote = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        reservationSlotId = entity.getReservationSlotId();
        lineId = entity.getLineId();
        teamId = entity.getTeamId();
        userId = entity.getUserId();
        bookedAt = entity.getBookedAt();
        confirmedAt = entity.getConfirmedAt();
        cancelledAt = entity.getCancelledAt();
        cancelReason = entity.getCancelReason();
        completedAt = entity.getCompletedAt();
        userNote = entity.getUserNote();
        adminNote = entity.getAdminNote();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();
        String cancelledBy = entity.getCancelledBy() != null ? entity.getCancelledBy().name() : null;

        ReservationResponse reservationResponse = new ReservationResponse( id, reservationSlotId, lineId, teamId, userId, status, bookedAt, confirmedAt, cancelledAt, cancelReason, cancelledBy, completedAt, userNote, adminNote, createdAt, updatedAt );

        return reservationResponse;
    }

    @Override
    public List<ReservationResponse> toReservationResponseList(List<ReservationEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReservationResponse> list = new ArrayList<ReservationResponse>( entities.size() );
        for ( ReservationEntity reservationEntity : entities ) {
            list.add( toReservationResponse( reservationEntity ) );
        }

        return list;
    }

    @Override
    public BusinessHourResponse toBusinessHourResponse(ReservationBusinessHourEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        String dayOfWeek = null;
        Boolean isOpen = null;
        LocalTime openTime = null;
        LocalTime closeTime = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        dayOfWeek = entity.getDayOfWeek();
        isOpen = entity.getIsOpen();
        openTime = entity.getOpenTime();
        closeTime = entity.getCloseTime();

        BusinessHourResponse businessHourResponse = new BusinessHourResponse( id, teamId, dayOfWeek, isOpen, openTime, closeTime );

        return businessHourResponse;
    }

    @Override
    public List<BusinessHourResponse> toBusinessHourResponseList(List<ReservationBusinessHourEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BusinessHourResponse> list = new ArrayList<BusinessHourResponse>( entities.size() );
        for ( ReservationBusinessHourEntity reservationBusinessHourEntity : entities ) {
            list.add( toBusinessHourResponse( reservationBusinessHourEntity ) );
        }

        return list;
    }

    @Override
    public BlockedTimeResponse toBlockedTimeResponse(ReservationBlockedTimeEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        LocalDate blockedDate = null;
        LocalTime startTime = null;
        LocalTime endTime = null;
        String reason = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        blockedDate = entity.getBlockedDate();
        startTime = entity.getStartTime();
        endTime = entity.getEndTime();
        reason = entity.getReason();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        BlockedTimeResponse blockedTimeResponse = new BlockedTimeResponse( id, teamId, blockedDate, startTime, endTime, reason, createdBy, createdAt, updatedAt );

        return blockedTimeResponse;
    }

    @Override
    public List<BlockedTimeResponse> toBlockedTimeResponseList(List<ReservationBlockedTimeEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<BlockedTimeResponse> list = new ArrayList<BlockedTimeResponse>( entities.size() );
        for ( ReservationBlockedTimeEntity reservationBlockedTimeEntity : entities ) {
            list.add( toBlockedTimeResponse( reservationBlockedTimeEntity ) );
        }

        return list;
    }

    @Override
    public ReminderResponse toReminderResponse(ReservationReminderEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long reservationId = null;
        LocalDateTime remindAt = null;
        LocalDateTime sentAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        reservationId = entity.getReservationId();
        remindAt = entity.getRemindAt();
        sentAt = entity.getSentAt();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();

        ReminderResponse reminderResponse = new ReminderResponse( id, reservationId, remindAt, status, sentAt, createdAt );

        return reminderResponse;
    }

    @Override
    public List<ReminderResponse> toReminderResponseList(List<ReservationReminderEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ReminderResponse> list = new ArrayList<ReminderResponse>( entities.size() );
        for ( ReservationReminderEntity reservationReminderEntity : entities ) {
            list.add( toReminderResponse( reservationReminderEntity ) );
        }

        return list;
    }
}

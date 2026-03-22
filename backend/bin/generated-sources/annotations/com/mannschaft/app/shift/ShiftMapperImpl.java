package com.mannschaft.app.shift;

import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.dto.ShiftPositionResponse;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
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
    date = "2026-03-22T15:42:34+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ShiftMapperImpl implements ShiftMapper {

    @Override
    public ShiftScheduleResponse toScheduleResponse(ShiftScheduleEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        String title = null;
        LocalDate startDate = null;
        LocalDate endDate = null;
        LocalDateTime requestDeadline = null;
        String note = null;
        Long createdBy = null;
        LocalDateTime publishedAt = null;
        Long publishedBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        title = entity.getTitle();
        startDate = entity.getStartDate();
        endDate = entity.getEndDate();
        requestDeadline = entity.getRequestDeadline();
        note = entity.getNote();
        createdBy = entity.getCreatedBy();
        publishedAt = entity.getPublishedAt();
        publishedBy = entity.getPublishedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String periodType = entity.getPeriodType().name();
        String status = entity.getStatus().name();

        ShiftScheduleResponse shiftScheduleResponse = new ShiftScheduleResponse( id, teamId, title, periodType, startDate, endDate, status, requestDeadline, note, createdBy, publishedAt, publishedBy, createdAt, updatedAt );

        return shiftScheduleResponse;
    }

    @Override
    public List<ShiftScheduleResponse> toScheduleResponseList(List<ShiftScheduleEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ShiftScheduleResponse> list = new ArrayList<ShiftScheduleResponse>( entities.size() );
        for ( ShiftScheduleEntity shiftScheduleEntity : entities ) {
            list.add( toScheduleResponse( shiftScheduleEntity ) );
        }

        return list;
    }

    @Override
    public ShiftPositionResponse toPositionResponse(ShiftPositionEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long teamId = null;
        String name = null;
        Integer displayOrder = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        teamId = entity.getTeamId();
        name = entity.getName();
        displayOrder = entity.getDisplayOrder();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();

        ShiftPositionResponse shiftPositionResponse = new ShiftPositionResponse( id, teamId, name, displayOrder, isActive, createdAt );

        return shiftPositionResponse;
    }

    @Override
    public List<ShiftPositionResponse> toPositionResponseList(List<ShiftPositionEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ShiftPositionResponse> list = new ArrayList<ShiftPositionResponse>( entities.size() );
        for ( ShiftPositionEntity shiftPositionEntity : entities ) {
            list.add( toPositionResponse( shiftPositionEntity ) );
        }

        return list;
    }

    @Override
    public ShiftRequestResponse toRequestResponse(ShiftRequestEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long scheduleId = null;
        Long userId = null;
        Long slotId = null;
        LocalDate slotDate = null;
        String note = null;
        LocalDateTime submittedAt = null;

        id = entity.getId();
        scheduleId = entity.getScheduleId();
        userId = entity.getUserId();
        slotId = entity.getSlotId();
        slotDate = entity.getSlotDate();
        note = entity.getNote();
        submittedAt = entity.getSubmittedAt();

        String preference = entity.getPreference().name();

        ShiftRequestResponse shiftRequestResponse = new ShiftRequestResponse( id, scheduleId, userId, slotId, slotDate, preference, note, submittedAt );

        return shiftRequestResponse;
    }

    @Override
    public List<ShiftRequestResponse> toRequestResponseList(List<ShiftRequestEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ShiftRequestResponse> list = new ArrayList<ShiftRequestResponse>( entities.size() );
        for ( ShiftRequestEntity shiftRequestEntity : entities ) {
            list.add( toRequestResponse( shiftRequestEntity ) );
        }

        return list;
    }

    @Override
    public SwapRequestResponse toSwapResponse(ShiftSwapRequestEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long slotId = null;
        Long requesterId = null;
        Long accepterId = null;
        String reason = null;
        String adminNote = null;
        Long resolvedBy = null;
        LocalDateTime resolvedAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        slotId = entity.getSlotId();
        requesterId = entity.getRequesterId();
        accepterId = entity.getAccepterId();
        reason = entity.getReason();
        adminNote = entity.getAdminNote();
        resolvedBy = entity.getResolvedBy();
        resolvedAt = entity.getResolvedAt();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();

        SwapRequestResponse swapRequestResponse = new SwapRequestResponse( id, slotId, requesterId, accepterId, status, reason, adminNote, resolvedBy, resolvedAt, createdAt );

        return swapRequestResponse;
    }

    @Override
    public List<SwapRequestResponse> toSwapResponseList(List<ShiftSwapRequestEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SwapRequestResponse> list = new ArrayList<SwapRequestResponse>( entities.size() );
        for ( ShiftSwapRequestEntity shiftSwapRequestEntity : entities ) {
            list.add( toSwapResponse( shiftSwapRequestEntity ) );
        }

        return list;
    }

    @Override
    public AvailabilityDefaultResponse toAvailabilityResponse(MemberAvailabilityDefaultEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long teamId = null;
        Integer dayOfWeek = null;
        LocalTime startTime = null;
        LocalTime endTime = null;
        String note = null;

        id = entity.getId();
        userId = entity.getUserId();
        teamId = entity.getTeamId();
        dayOfWeek = entity.getDayOfWeek();
        startTime = entity.getStartTime();
        endTime = entity.getEndTime();
        note = entity.getNote();

        String preference = entity.getPreference().name();

        AvailabilityDefaultResponse availabilityDefaultResponse = new AvailabilityDefaultResponse( id, userId, teamId, dayOfWeek, startTime, endTime, preference, note );

        return availabilityDefaultResponse;
    }

    @Override
    public List<AvailabilityDefaultResponse> toAvailabilityResponseList(List<MemberAvailabilityDefaultEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AvailabilityDefaultResponse> list = new ArrayList<AvailabilityDefaultResponse>( entities.size() );
        for ( MemberAvailabilityDefaultEntity memberAvailabilityDefaultEntity : entities ) {
            list.add( toAvailabilityResponse( memberAvailabilityDefaultEntity ) );
        }

        return list;
    }

    @Override
    public HourlyRateResponse toHourlyRateResponse(ShiftHourlyRateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long teamId = null;
        BigDecimal hourlyRate = null;
        LocalDate effectiveFrom = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        teamId = entity.getTeamId();
        hourlyRate = entity.getHourlyRate();
        effectiveFrom = entity.getEffectiveFrom();
        createdAt = entity.getCreatedAt();

        HourlyRateResponse hourlyRateResponse = new HourlyRateResponse( id, userId, teamId, hourlyRate, effectiveFrom, createdAt );

        return hourlyRateResponse;
    }

    @Override
    public List<HourlyRateResponse> toHourlyRateResponseList(List<ShiftHourlyRateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<HourlyRateResponse> list = new ArrayList<HourlyRateResponse>( entities.size() );
        for ( ShiftHourlyRateEntity shiftHourlyRateEntity : entities ) {
            list.add( toHourlyRateResponse( shiftHourlyRateEntity ) );
        }

        return list;
    }
}

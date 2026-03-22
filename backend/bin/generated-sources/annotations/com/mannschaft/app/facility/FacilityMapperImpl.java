package com.mannschaft.app.facility;

import com.mannschaft.app.facility.dto.BookingDetailResponse;
import com.mannschaft.app.facility.dto.BookingPaymentResponse;
import com.mannschaft.app.facility.dto.BookingResponse;
import com.mannschaft.app.facility.dto.CalendarBookingResponse;
import com.mannschaft.app.facility.dto.EquipmentResponse;
import com.mannschaft.app.facility.dto.FacilityDetailResponse;
import com.mannschaft.app.facility.dto.FacilityResponse;
import com.mannschaft.app.facility.dto.FacilitySettingsResponse;
import com.mannschaft.app.facility.dto.TimeRateResponse;
import com.mannschaft.app.facility.dto.UsageRuleResponse;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.FacilityBookingPaymentEntity;
import com.mannschaft.app.facility.entity.FacilityEquipmentEntity;
import com.mannschaft.app.facility.entity.FacilitySettingsEntity;
import com.mannschaft.app.facility.entity.FacilityTimeRateEntity;
import com.mannschaft.app.facility.entity.FacilityUsageRuleEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
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
    date = "2026-03-22T15:42:09+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class FacilityMapperImpl implements FacilityMapper {

    @Override
    public FacilityResponse toFacilityResponse(SharedFacilityEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        String name = null;
        String facilityTypeLabel = null;
        Integer capacity = null;
        String floor = null;
        BigDecimal ratePerSlot = null;
        BigDecimal ratePerNight = null;
        Boolean autoApprove = null;
        Boolean isActive = null;
        Integer displayOrder = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        name = entity.getName();
        facilityTypeLabel = entity.getFacilityTypeLabel();
        capacity = entity.getCapacity();
        floor = entity.getFloor();
        ratePerSlot = entity.getRatePerSlot();
        ratePerNight = entity.getRatePerNight();
        autoApprove = entity.getAutoApprove();
        isActive = entity.getIsActive();
        displayOrder = entity.getDisplayOrder();
        createdAt = entity.getCreatedAt();

        String facilityType = entity.getFacilityType().name();

        FacilityResponse facilityResponse = new FacilityResponse( id, scopeType, scopeId, name, facilityType, facilityTypeLabel, capacity, floor, ratePerSlot, ratePerNight, autoApprove, isActive, displayOrder, createdAt );

        return facilityResponse;
    }

    @Override
    public List<FacilityResponse> toFacilityResponseList(List<SharedFacilityEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<FacilityResponse> list = new ArrayList<FacilityResponse>( entities.size() );
        for ( SharedFacilityEntity sharedFacilityEntity : entities ) {
            list.add( toFacilityResponse( sharedFacilityEntity ) );
        }

        return list;
    }

    @Override
    public FacilityDetailResponse toFacilityDetailResponse(SharedFacilityEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        String name = null;
        String facilityTypeLabel = null;
        Integer capacity = null;
        String floor = null;
        String locationDetail = null;
        String description = null;
        BigDecimal ratePerSlot = null;
        BigDecimal ratePerNight = null;
        LocalTime checkInTime = null;
        LocalTime checkOutTime = null;
        Integer cleaningBufferMinutes = null;
        Boolean autoApprove = null;
        Boolean isActive = null;
        Integer displayOrder = null;
        Long createdBy = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        name = entity.getName();
        facilityTypeLabel = entity.getFacilityTypeLabel();
        capacity = entity.getCapacity();
        floor = entity.getFloor();
        locationDetail = entity.getLocationDetail();
        description = entity.getDescription();
        ratePerSlot = entity.getRatePerSlot();
        ratePerNight = entity.getRatePerNight();
        checkInTime = entity.getCheckInTime();
        checkOutTime = entity.getCheckOutTime();
        cleaningBufferMinutes = entity.getCleaningBufferMinutes();
        autoApprove = entity.getAutoApprove();
        isActive = entity.getIsActive();
        displayOrder = entity.getDisplayOrder();
        createdBy = entity.getCreatedBy();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String facilityType = entity.getFacilityType().name();
        List<String> imageUrls = null;

        FacilityDetailResponse facilityDetailResponse = new FacilityDetailResponse( id, scopeType, scopeId, name, facilityType, facilityTypeLabel, capacity, floor, locationDetail, description, imageUrls, ratePerSlot, ratePerNight, checkInTime, checkOutTime, cleaningBufferMinutes, autoApprove, isActive, displayOrder, createdBy, createdAt, updatedAt );

        return facilityDetailResponse;
    }

    @Override
    public UsageRuleResponse toUsageRuleResponse(FacilityUsageRuleEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long facilityId = null;
        BigDecimal maxHoursPerBooking = null;
        BigDecimal minHoursPerBooking = null;
        Integer maxBookingsPerMonthPerUser = null;
        Integer maxConsecutiveSlots = null;
        Integer minAdvanceHours = null;
        Integer maxAdvanceDays = null;
        Integer maxStayNights = null;
        Integer cancellationDeadlineHours = null;
        LocalTime availableTimeFrom = null;
        LocalTime availableTimeTo = null;
        String availableDaysOfWeek = null;
        String blackoutDates = null;
        String notes = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        facilityId = entity.getFacilityId();
        maxHoursPerBooking = entity.getMaxHoursPerBooking();
        minHoursPerBooking = entity.getMinHoursPerBooking();
        maxBookingsPerMonthPerUser = entity.getMaxBookingsPerMonthPerUser();
        maxConsecutiveSlots = entity.getMaxConsecutiveSlots();
        minAdvanceHours = entity.getMinAdvanceHours();
        maxAdvanceDays = entity.getMaxAdvanceDays();
        maxStayNights = entity.getMaxStayNights();
        cancellationDeadlineHours = entity.getCancellationDeadlineHours();
        availableTimeFrom = entity.getAvailableTimeFrom();
        availableTimeTo = entity.getAvailableTimeTo();
        availableDaysOfWeek = entity.getAvailableDaysOfWeek();
        blackoutDates = entity.getBlackoutDates();
        notes = entity.getNotes();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        UsageRuleResponse usageRuleResponse = new UsageRuleResponse( id, facilityId, maxHoursPerBooking, minHoursPerBooking, maxBookingsPerMonthPerUser, maxConsecutiveSlots, minAdvanceHours, maxAdvanceDays, maxStayNights, cancellationDeadlineHours, availableTimeFrom, availableTimeTo, availableDaysOfWeek, blackoutDates, notes, createdAt, updatedAt );

        return usageRuleResponse;
    }

    @Override
    public TimeRateResponse toTimeRateResponse(FacilityTimeRateEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long facilityId = null;
        LocalTime timeFrom = null;
        LocalTime timeTo = null;
        BigDecimal ratePerSlot = null;

        id = entity.getId();
        facilityId = entity.getFacilityId();
        timeFrom = entity.getTimeFrom();
        timeTo = entity.getTimeTo();
        ratePerSlot = entity.getRatePerSlot();

        String dayType = entity.getDayType().name();

        TimeRateResponse timeRateResponse = new TimeRateResponse( id, facilityId, dayType, timeFrom, timeTo, ratePerSlot );

        return timeRateResponse;
    }

    @Override
    public List<TimeRateResponse> toTimeRateResponseList(List<FacilityTimeRateEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<TimeRateResponse> list = new ArrayList<TimeRateResponse>( entities.size() );
        for ( FacilityTimeRateEntity facilityTimeRateEntity : entities ) {
            list.add( toTimeRateResponse( facilityTimeRateEntity ) );
        }

        return list;
    }

    @Override
    public EquipmentResponse toEquipmentResponse(FacilityEquipmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long facilityId = null;
        String name = null;
        String description = null;
        Integer totalQuantity = null;
        BigDecimal pricePerUse = null;
        Boolean isAvailable = null;
        Integer displayOrder = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        facilityId = entity.getFacilityId();
        name = entity.getName();
        description = entity.getDescription();
        totalQuantity = entity.getTotalQuantity();
        pricePerUse = entity.getPricePerUse();
        isAvailable = entity.getIsAvailable();
        displayOrder = entity.getDisplayOrder();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        EquipmentResponse equipmentResponse = new EquipmentResponse( id, facilityId, name, description, totalQuantity, pricePerUse, isAvailable, displayOrder, createdAt, updatedAt );

        return equipmentResponse;
    }

    @Override
    public List<EquipmentResponse> toEquipmentResponseList(List<FacilityEquipmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<EquipmentResponse> list = new ArrayList<EquipmentResponse>( entities.size() );
        for ( FacilityEquipmentEntity facilityEquipmentEntity : entities ) {
            list.add( toEquipmentResponse( facilityEquipmentEntity ) );
        }

        return list;
    }

    @Override
    public BookingResponse toBookingResponse(FacilityBookingEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long facilityId = null;
        Long bookedBy = null;
        LocalDate bookingDate = null;
        LocalDate checkOutDate = null;
        Integer stayNights = null;
        LocalTime timeFrom = null;
        LocalTime timeTo = null;
        Integer slotCount = null;
        String purpose = null;
        BigDecimal totalFee = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        facilityId = entity.getFacilityId();
        bookedBy = entity.getBookedBy();
        bookingDate = entity.getBookingDate();
        checkOutDate = entity.getCheckOutDate();
        stayNights = entity.getStayNights();
        timeFrom = entity.getTimeFrom();
        timeTo = entity.getTimeTo();
        slotCount = entity.getSlotCount();
        purpose = entity.getPurpose();
        totalFee = entity.getTotalFee();
        createdAt = entity.getCreatedAt();

        String facilityName = null;
        String status = entity.getStatus().name();

        BookingResponse bookingResponse = new BookingResponse( id, facilityId, facilityName, bookedBy, bookingDate, checkOutDate, stayNights, timeFrom, timeTo, slotCount, purpose, totalFee, status, createdAt );

        return bookingResponse;
    }

    @Override
    public BookingDetailResponse toBookingDetailResponse(FacilityBookingEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long facilityId = null;
        Long bookedBy = null;
        Long createdByAdmin = null;
        LocalDate bookingDate = null;
        LocalDate checkOutDate = null;
        Integer stayNights = null;
        LocalTime timeFrom = null;
        LocalTime timeTo = null;
        Integer slotCount = null;
        String purpose = null;
        Integer attendeeCount = null;
        BigDecimal usageFee = null;
        BigDecimal equipmentFee = null;
        BigDecimal totalFee = null;
        String adminComment = null;
        Long approvedBy = null;
        LocalDateTime approvedAt = null;
        LocalDateTime checkedInAt = null;
        LocalDateTime completedAt = null;
        LocalDateTime cancelledAt = null;
        Long cancelledBy = null;
        String cancellationReason = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        facilityId = entity.getFacilityId();
        bookedBy = entity.getBookedBy();
        createdByAdmin = entity.getCreatedByAdmin();
        bookingDate = entity.getBookingDate();
        checkOutDate = entity.getCheckOutDate();
        stayNights = entity.getStayNights();
        timeFrom = entity.getTimeFrom();
        timeTo = entity.getTimeTo();
        slotCount = entity.getSlotCount();
        purpose = entity.getPurpose();
        attendeeCount = entity.getAttendeeCount();
        usageFee = entity.getUsageFee();
        equipmentFee = entity.getEquipmentFee();
        totalFee = entity.getTotalFee();
        adminComment = entity.getAdminComment();
        approvedBy = entity.getApprovedBy();
        approvedAt = entity.getApprovedAt();
        checkedInAt = entity.getCheckedInAt();
        completedAt = entity.getCompletedAt();
        cancelledAt = entity.getCancelledAt();
        cancelledBy = entity.getCancelledBy();
        cancellationReason = entity.getCancellationReason();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String facilityName = null;
        String status = entity.getStatus().name();
        List<BookingDetailResponse.BookingEquipmentResponse> equipment = null;

        BookingDetailResponse bookingDetailResponse = new BookingDetailResponse( id, facilityId, facilityName, bookedBy, createdByAdmin, bookingDate, checkOutDate, stayNights, timeFrom, timeTo, slotCount, purpose, attendeeCount, usageFee, equipmentFee, totalFee, status, adminComment, approvedBy, approvedAt, checkedInAt, completedAt, cancelledAt, cancelledBy, cancellationReason, equipment, createdAt, updatedAt );

        return bookingDetailResponse;
    }

    @Override
    public CalendarBookingResponse toCalendarBookingResponse(FacilityBookingEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long facilityId = null;
        LocalDate bookingDate = null;
        LocalDate checkOutDate = null;
        LocalTime timeFrom = null;
        LocalTime timeTo = null;
        Long bookedBy = null;

        id = entity.getId();
        facilityId = entity.getFacilityId();
        bookingDate = entity.getBookingDate();
        checkOutDate = entity.getCheckOutDate();
        timeFrom = entity.getTimeFrom();
        timeTo = entity.getTimeTo();
        bookedBy = entity.getBookedBy();

        String facilityName = null;
        String status = entity.getStatus().name();

        CalendarBookingResponse calendarBookingResponse = new CalendarBookingResponse( id, facilityId, facilityName, bookingDate, checkOutDate, timeFrom, timeTo, status, bookedBy );

        return calendarBookingResponse;
    }

    @Override
    public List<CalendarBookingResponse> toCalendarBookingResponseList(List<FacilityBookingEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<CalendarBookingResponse> list = new ArrayList<CalendarBookingResponse>( entities.size() );
        for ( FacilityBookingEntity facilityBookingEntity : entities ) {
            list.add( toCalendarBookingResponse( facilityBookingEntity ) );
        }

        return list;
    }

    @Override
    public BookingPaymentResponse toBookingPaymentResponse(FacilityBookingPaymentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long bookingId = null;
        Long payerUserId = null;
        BigDecimal amount = null;
        BigDecimal stripeFee = null;
        BigDecimal platformFee = null;
        BigDecimal platformFeeRate = null;
        BigDecimal netAmount = null;
        String failedReason = null;
        LocalDateTime paidAt = null;
        LocalDateTime refundedAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        bookingId = entity.getBookingId();
        payerUserId = entity.getPayerUserId();
        amount = entity.getAmount();
        stripeFee = entity.getStripeFee();
        platformFee = entity.getPlatformFee();
        platformFeeRate = entity.getPlatformFeeRate();
        netAmount = entity.getNetAmount();
        failedReason = entity.getFailedReason();
        paidAt = entity.getPaidAt();
        refundedAt = entity.getRefundedAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String paymentMethod = entity.getPaymentMethod().name();
        String status = entity.getStatus().name();

        BookingPaymentResponse bookingPaymentResponse = new BookingPaymentResponse( id, bookingId, payerUserId, paymentMethod, amount, stripeFee, platformFee, platformFeeRate, netAmount, status, failedReason, paidAt, refundedAt, createdAt, updatedAt );

        return bookingPaymentResponse;
    }

    @Override
    public FacilitySettingsResponse toSettingsResponse(FacilitySettingsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        Boolean requiresApproval = null;
        Integer maxBookingsPerDayPerUser = null;
        Boolean allowStripePayment = null;
        Integer cancellationDeadlineHours = null;
        Boolean noShowPenaltyEnabled = null;
        Integer noShowPenaltyThreshold = null;
        Integer noShowPenaltyDays = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        requiresApproval = entity.getRequiresApproval();
        maxBookingsPerDayPerUser = entity.getMaxBookingsPerDayPerUser();
        allowStripePayment = entity.getAllowStripePayment();
        cancellationDeadlineHours = entity.getCancellationDeadlineHours();
        noShowPenaltyEnabled = entity.getNoShowPenaltyEnabled();
        noShowPenaltyThreshold = entity.getNoShowPenaltyThreshold();
        noShowPenaltyDays = entity.getNoShowPenaltyDays();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        FacilitySettingsResponse facilitySettingsResponse = new FacilitySettingsResponse( id, scopeType, scopeId, requiresApproval, maxBookingsPerDayPerUser, allowStripePayment, cancellationDeadlineHours, noShowPenaltyEnabled, noShowPenaltyThreshold, noShowPenaltyDays, createdAt, updatedAt );

        return facilitySettingsResponse;
    }
}

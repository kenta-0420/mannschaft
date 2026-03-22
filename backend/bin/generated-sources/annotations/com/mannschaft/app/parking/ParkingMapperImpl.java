package com.mannschaft.app.parking;

import com.mannschaft.app.parking.dto.ApplicationResponse;
import com.mannschaft.app.parking.dto.AssignmentResponse;
import com.mannschaft.app.parking.dto.ListingDetailResponse;
import com.mannschaft.app.parking.dto.ListingResponse;
import com.mannschaft.app.parking.dto.ParkingSettingsResponse;
import com.mannschaft.app.parking.dto.PriceHistoryResponse;
import com.mannschaft.app.parking.dto.SpaceResponse;
import com.mannschaft.app.parking.dto.SubleaseApplicationResponse;
import com.mannschaft.app.parking.dto.SubleaseDetailResponse;
import com.mannschaft.app.parking.dto.SubleasePaymentResponse;
import com.mannschaft.app.parking.dto.SubleaseResponse;
import com.mannschaft.app.parking.dto.VehicleResponse;
import com.mannschaft.app.parking.dto.VisitorRecurringResponse;
import com.mannschaft.app.parking.dto.VisitorReservationResponse;
import com.mannschaft.app.parking.dto.WatchlistResponse;
import com.mannschaft.app.parking.entity.ParkingApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingAssignmentEntity;
import com.mannschaft.app.parking.entity.ParkingListingEntity;
import com.mannschaft.app.parking.entity.ParkingSettingsEntity;
import com.mannschaft.app.parking.entity.ParkingSpaceEntity;
import com.mannschaft.app.parking.entity.ParkingSpacePriceHistoryEntity;
import com.mannschaft.app.parking.entity.ParkingSubleaseApplicationEntity;
import com.mannschaft.app.parking.entity.ParkingSubleaseEntity;
import com.mannschaft.app.parking.entity.ParkingSubleasePaymentEntity;
import com.mannschaft.app.parking.entity.ParkingVisitorRecurringEntity;
import com.mannschaft.app.parking.entity.ParkingVisitorReservationEntity;
import com.mannschaft.app.parking.entity.ParkingWatchlistEntity;
import com.mannschaft.app.parking.entity.RegisteredVehicleEntity;
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
    date = "2026-03-22T16:08:46+0900",
    comments = "version: 1.6.3, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ParkingMapperImpl implements ParkingMapper {

    @Override
    public SpaceResponse toSpaceResponse(ParkingSpaceEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        String spaceNumber = null;
        String spaceTypeLabel = null;
        BigDecimal pricePerMonth = null;
        String floor = null;
        String notes = null;
        LocalDateTime applicationDeadline = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        spaceNumber = entity.getSpaceNumber();
        spaceTypeLabel = entity.getSpaceTypeLabel();
        pricePerMonth = entity.getPricePerMonth();
        floor = entity.getFloor();
        notes = entity.getNotes();
        applicationDeadline = entity.getApplicationDeadline();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String spaceType = entity.getSpaceType().name();
        String status = entity.getStatus().name();
        String applicationStatus = entity.getApplicationStatus().name();
        String allocationMethod = entity.getAllocationMethod() != null ? entity.getAllocationMethod().name() : null;

        SpaceResponse spaceResponse = new SpaceResponse( id, scopeType, scopeId, spaceNumber, spaceType, spaceTypeLabel, pricePerMonth, status, floor, notes, applicationStatus, allocationMethod, applicationDeadline, createdAt, updatedAt );

        return spaceResponse;
    }

    @Override
    public List<SpaceResponse> toSpaceResponseList(List<ParkingSpaceEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SpaceResponse> list = new ArrayList<SpaceResponse>( entities.size() );
        for ( ParkingSpaceEntity parkingSpaceEntity : entities ) {
            list.add( toSpaceResponse( parkingSpaceEntity ) );
        }

        return list;
    }

    @Override
    public VehicleResponse toVehicleResponse(RegisteredVehicleEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String nickname = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        nickname = entity.getNickname();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String vehicleType = entity.getVehicleType().name();
        String plateNumber = null;

        VehicleResponse vehicleResponse = new VehicleResponse( id, userId, vehicleType, plateNumber, nickname, createdAt, updatedAt );

        return vehicleResponse;
    }

    @Override
    public List<VehicleResponse> toVehicleResponseList(List<RegisteredVehicleEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<VehicleResponse> list = new ArrayList<VehicleResponse>( entities.size() );
        for ( RegisteredVehicleEntity registeredVehicleEntity : entities ) {
            list.add( toVehicleResponse( registeredVehicleEntity ) );
        }

        return list;
    }

    @Override
    public AssignmentResponse toAssignmentResponse(ParkingAssignmentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long spaceId = null;
        Long vehicleId = null;
        Long userId = null;
        Long assignedBy = null;
        LocalDateTime assignedAt = null;
        LocalDate contractStartDate = null;
        LocalDate contractEndDate = null;
        LocalDateTime releasedAt = null;
        Long releasedBy = null;
        String releaseReason = null;

        id = entity.getId();
        spaceId = entity.getSpaceId();
        vehicleId = entity.getVehicleId();
        userId = entity.getUserId();
        assignedBy = entity.getAssignedBy();
        assignedAt = entity.getAssignedAt();
        contractStartDate = entity.getContractStartDate();
        contractEndDate = entity.getContractEndDate();
        releasedAt = entity.getReleasedAt();
        releasedBy = entity.getReleasedBy();
        releaseReason = entity.getReleaseReason();

        AssignmentResponse assignmentResponse = new AssignmentResponse( id, spaceId, vehicleId, userId, assignedBy, assignedAt, contractStartDate, contractEndDate, releasedAt, releasedBy, releaseReason );

        return assignmentResponse;
    }

    @Override
    public List<AssignmentResponse> toAssignmentResponseList(List<ParkingAssignmentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<AssignmentResponse> list = new ArrayList<AssignmentResponse>( entities.size() );
        for ( ParkingAssignmentEntity parkingAssignmentEntity : entities ) {
            list.add( toAssignmentResponse( parkingAssignmentEntity ) );
        }

        return list;
    }

    @Override
    public ApplicationResponse toApplicationResponse(ParkingApplicationEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long spaceId = null;
        Long userId = null;
        Long vehicleId = null;
        Long listingId = null;
        Integer priority = null;
        String message = null;
        String rejectionReason = null;
        Integer lotteryNumber = null;
        LocalDateTime decidedAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        spaceId = entity.getSpaceId();
        userId = entity.getUserId();
        vehicleId = entity.getVehicleId();
        listingId = entity.getListingId();
        priority = entity.getPriority();
        message = entity.getMessage();
        rejectionReason = entity.getRejectionReason();
        lotteryNumber = entity.getLotteryNumber();
        decidedAt = entity.getDecidedAt();
        createdAt = entity.getCreatedAt();

        String sourceType = entity.getSourceType().name();
        String status = entity.getStatus().name();

        ApplicationResponse applicationResponse = new ApplicationResponse( id, spaceId, userId, vehicleId, sourceType, listingId, status, priority, message, rejectionReason, lotteryNumber, decidedAt, createdAt );

        return applicationResponse;
    }

    @Override
    public List<ApplicationResponse> toApplicationResponseList(List<ParkingApplicationEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ApplicationResponse> list = new ArrayList<ApplicationResponse>( entities.size() );
        for ( ParkingApplicationEntity parkingApplicationEntity : entities ) {
            list.add( toApplicationResponse( parkingApplicationEntity ) );
        }

        return list;
    }

    @Override
    public ListingResponse toListingResponse(ParkingListingEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long spaceId = null;
        Long assignmentId = null;
        Long listedBy = null;
        String reason = null;
        LocalDate desiredTransferDate = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        spaceId = entity.getSpaceId();
        assignmentId = entity.getAssignmentId();
        listedBy = entity.getListedBy();
        reason = entity.getReason();
        desiredTransferDate = entity.getDesiredTransferDate();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();

        ListingResponse listingResponse = new ListingResponse( id, spaceId, assignmentId, listedBy, reason, desiredTransferDate, status, createdAt, updatedAt );

        return listingResponse;
    }

    @Override
    public List<ListingResponse> toListingResponseList(List<ParkingListingEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<ListingResponse> list = new ArrayList<ListingResponse>( entities.size() );
        for ( ParkingListingEntity parkingListingEntity : entities ) {
            list.add( toListingResponse( parkingListingEntity ) );
        }

        return list;
    }

    @Override
    public ListingDetailResponse toListingDetailResponse(ParkingListingEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long spaceId = null;
        Long assignmentId = null;
        Long listedBy = null;
        String reason = null;
        LocalDate desiredTransferDate = null;
        Long transfereeUserId = null;
        Long transfereeVehicleId = null;
        LocalDateTime transferredAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        spaceId = entity.getSpaceId();
        assignmentId = entity.getAssignmentId();
        listedBy = entity.getListedBy();
        reason = entity.getReason();
        desiredTransferDate = entity.getDesiredTransferDate();
        transfereeUserId = entity.getTransfereeUserId();
        transfereeVehicleId = entity.getTransfereeVehicleId();
        transferredAt = entity.getTransferredAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();

        ListingDetailResponse listingDetailResponse = new ListingDetailResponse( id, spaceId, assignmentId, listedBy, reason, desiredTransferDate, status, transfereeUserId, transfereeVehicleId, transferredAt, createdAt, updatedAt );

        return listingDetailResponse;
    }

    @Override
    public VisitorReservationResponse toVisitorReservationResponse(ParkingVisitorReservationEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long spaceId = null;
        Long reservedBy = null;
        String visitorName = null;
        String visitorPlateNumber = null;
        LocalDate reservedDate = null;
        LocalTime timeFrom = null;
        LocalTime timeTo = null;
        String purpose = null;
        String adminComment = null;
        Long approvedBy = null;
        LocalDateTime approvedAt = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        spaceId = entity.getSpaceId();
        reservedBy = entity.getReservedBy();
        visitorName = entity.getVisitorName();
        visitorPlateNumber = entity.getVisitorPlateNumber();
        reservedDate = entity.getReservedDate();
        timeFrom = entity.getTimeFrom();
        timeTo = entity.getTimeTo();
        purpose = entity.getPurpose();
        adminComment = entity.getAdminComment();
        approvedBy = entity.getApprovedBy();
        approvedAt = entity.getApprovedAt();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();

        VisitorReservationResponse visitorReservationResponse = new VisitorReservationResponse( id, spaceId, reservedBy, visitorName, visitorPlateNumber, reservedDate, timeFrom, timeTo, purpose, adminComment, approvedBy, approvedAt, status, createdAt, updatedAt );

        return visitorReservationResponse;
    }

    @Override
    public List<VisitorReservationResponse> toVisitorReservationResponseList(List<ParkingVisitorReservationEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<VisitorReservationResponse> list = new ArrayList<VisitorReservationResponse>( entities.size() );
        for ( ParkingVisitorReservationEntity parkingVisitorReservationEntity : entities ) {
            list.add( toVisitorReservationResponse( parkingVisitorReservationEntity ) );
        }

        return list;
    }

    @Override
    public WatchlistResponse toWatchlistResponse(ParkingWatchlistEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        String scopeType = null;
        Long scopeId = null;
        String floor = null;
        BigDecimal maxPrice = null;
        Boolean isActive = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        floor = entity.getFloor();
        maxPrice = entity.getMaxPrice();
        isActive = entity.getIsActive();
        createdAt = entity.getCreatedAt();

        String spaceType = entity.getSpaceType() != null ? entity.getSpaceType().name() : null;

        WatchlistResponse watchlistResponse = new WatchlistResponse( id, userId, scopeType, scopeId, spaceType, floor, maxPrice, isActive, createdAt );

        return watchlistResponse;
    }

    @Override
    public List<WatchlistResponse> toWatchlistResponseList(List<ParkingWatchlistEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<WatchlistResponse> list = new ArrayList<WatchlistResponse>( entities.size() );
        for ( ParkingWatchlistEntity parkingWatchlistEntity : entities ) {
            list.add( toWatchlistResponse( parkingWatchlistEntity ) );
        }

        return list;
    }

    @Override
    public PriceHistoryResponse toPriceHistoryResponse(ParkingSpacePriceHistoryEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long spaceId = null;
        BigDecimal oldPrice = null;
        BigDecimal newPrice = null;
        Long changedBy = null;
        LocalDateTime changedAt = null;

        id = entity.getId();
        spaceId = entity.getSpaceId();
        oldPrice = entity.getOldPrice();
        newPrice = entity.getNewPrice();
        changedBy = entity.getChangedBy();
        changedAt = entity.getChangedAt();

        PriceHistoryResponse priceHistoryResponse = new PriceHistoryResponse( id, spaceId, oldPrice, newPrice, changedBy, changedAt );

        return priceHistoryResponse;
    }

    @Override
    public List<PriceHistoryResponse> toPriceHistoryResponseList(List<ParkingSpacePriceHistoryEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<PriceHistoryResponse> list = new ArrayList<PriceHistoryResponse>( entities.size() );
        for ( ParkingSpacePriceHistoryEntity parkingSpacePriceHistoryEntity : entities ) {
            list.add( toPriceHistoryResponse( parkingSpacePriceHistoryEntity ) );
        }

        return list;
    }

    @Override
    public VisitorRecurringResponse toVisitorRecurringResponse(ParkingVisitorRecurringEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long userId = null;
        Long spaceId = null;
        Integer dayOfWeek = null;
        Integer dayOfMonth = null;
        LocalTime timeFrom = null;
        LocalTime timeTo = null;
        String visitorName = null;
        String visitorPlateNumber = null;
        String purpose = null;
        Boolean isActive = null;
        LocalDate nextGenerateDate = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        userId = entity.getUserId();
        spaceId = entity.getSpaceId();
        dayOfWeek = entity.getDayOfWeek();
        dayOfMonth = entity.getDayOfMonth();
        timeFrom = entity.getTimeFrom();
        timeTo = entity.getTimeTo();
        visitorName = entity.getVisitorName();
        visitorPlateNumber = entity.getVisitorPlateNumber();
        purpose = entity.getPurpose();
        isActive = entity.getIsActive();
        nextGenerateDate = entity.getNextGenerateDate();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String recurrenceType = entity.getRecurrenceType().name();

        VisitorRecurringResponse visitorRecurringResponse = new VisitorRecurringResponse( id, userId, spaceId, recurrenceType, dayOfWeek, dayOfMonth, timeFrom, timeTo, visitorName, visitorPlateNumber, purpose, isActive, nextGenerateDate, createdAt, updatedAt );

        return visitorRecurringResponse;
    }

    @Override
    public List<VisitorRecurringResponse> toVisitorRecurringResponseList(List<ParkingVisitorRecurringEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<VisitorRecurringResponse> list = new ArrayList<VisitorRecurringResponse>( entities.size() );
        for ( ParkingVisitorRecurringEntity parkingVisitorRecurringEntity : entities ) {
            list.add( toVisitorRecurringResponse( parkingVisitorRecurringEntity ) );
        }

        return list;
    }

    @Override
    public ParkingSettingsResponse toSettingsResponse(ParkingSettingsEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        String scopeType = null;
        Long scopeId = null;
        Integer maxSpacesPerUser = null;
        Integer maxVisitorReservationsPerDay = null;
        Integer visitorReservationMaxDaysAhead = null;
        Boolean visitorReservationRequiresApproval = null;

        id = entity.getId();
        scopeType = entity.getScopeType();
        scopeId = entity.getScopeId();
        maxSpacesPerUser = entity.getMaxSpacesPerUser();
        maxVisitorReservationsPerDay = entity.getMaxVisitorReservationsPerDay();
        visitorReservationMaxDaysAhead = entity.getVisitorReservationMaxDaysAhead();
        visitorReservationRequiresApproval = entity.getVisitorReservationRequiresApproval();

        ParkingSettingsResponse parkingSettingsResponse = new ParkingSettingsResponse( id, scopeType, scopeId, maxSpacesPerUser, maxVisitorReservationsPerDay, visitorReservationMaxDaysAhead, visitorReservationRequiresApproval );

        return parkingSettingsResponse;
    }

    @Override
    public SubleaseResponse toSubleaseResponse(ParkingSubleaseEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long spaceId = null;
        Long offeredBy = null;
        String title = null;
        BigDecimal pricePerMonth = null;
        LocalDate availableFrom = null;
        LocalDate availableTo = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        spaceId = entity.getSpaceId();
        offeredBy = entity.getOfferedBy();
        title = entity.getTitle();
        pricePerMonth = entity.getPricePerMonth();
        availableFrom = entity.getAvailableFrom();
        availableTo = entity.getAvailableTo();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();
        String paymentMethod = entity.getPaymentMethod().name();

        SubleaseResponse subleaseResponse = new SubleaseResponse( id, spaceId, offeredBy, title, pricePerMonth, paymentMethod, availableFrom, availableTo, status, createdAt, updatedAt );

        return subleaseResponse;
    }

    @Override
    public List<SubleaseResponse> toSubleaseResponseList(List<ParkingSubleaseEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SubleaseResponse> list = new ArrayList<SubleaseResponse>( entities.size() );
        for ( ParkingSubleaseEntity parkingSubleaseEntity : entities ) {
            list.add( toSubleaseResponse( parkingSubleaseEntity ) );
        }

        return list;
    }

    @Override
    public SubleaseDetailResponse toSubleaseDetailResponse(ParkingSubleaseEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long spaceId = null;
        Long assignmentId = null;
        Long offeredBy = null;
        String title = null;
        String description = null;
        BigDecimal pricePerMonth = null;
        LocalDate availableFrom = null;
        LocalDate availableTo = null;
        Long matchedApplicationId = null;
        LocalDateTime createdAt = null;
        LocalDateTime updatedAt = null;

        id = entity.getId();
        spaceId = entity.getSpaceId();
        assignmentId = entity.getAssignmentId();
        offeredBy = entity.getOfferedBy();
        title = entity.getTitle();
        description = entity.getDescription();
        pricePerMonth = entity.getPricePerMonth();
        availableFrom = entity.getAvailableFrom();
        availableTo = entity.getAvailableTo();
        matchedApplicationId = entity.getMatchedApplicationId();
        createdAt = entity.getCreatedAt();
        updatedAt = entity.getUpdatedAt();

        String status = entity.getStatus().name();
        String paymentMethod = entity.getPaymentMethod().name();

        SubleaseDetailResponse subleaseDetailResponse = new SubleaseDetailResponse( id, spaceId, assignmentId, offeredBy, title, description, pricePerMonth, paymentMethod, availableFrom, availableTo, status, matchedApplicationId, createdAt, updatedAt );

        return subleaseDetailResponse;
    }

    @Override
    public SubleaseApplicationResponse toSubleaseApplicationResponse(ParkingSubleaseApplicationEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long subleaseId = null;
        Long userId = null;
        Long vehicleId = null;
        String message = null;
        LocalDateTime decidedAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        subleaseId = entity.getSubleaseId();
        userId = entity.getUserId();
        vehicleId = entity.getVehicleId();
        message = entity.getMessage();
        decidedAt = entity.getDecidedAt();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();

        SubleaseApplicationResponse subleaseApplicationResponse = new SubleaseApplicationResponse( id, subleaseId, userId, vehicleId, message, status, decidedAt, createdAt );

        return subleaseApplicationResponse;
    }

    @Override
    public List<SubleaseApplicationResponse> toSubleaseApplicationResponseList(List<ParkingSubleaseApplicationEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SubleaseApplicationResponse> list = new ArrayList<SubleaseApplicationResponse>( entities.size() );
        for ( ParkingSubleaseApplicationEntity parkingSubleaseApplicationEntity : entities ) {
            list.add( toSubleaseApplicationResponse( parkingSubleaseApplicationEntity ) );
        }

        return list;
    }

    @Override
    public SubleasePaymentResponse toSubleasePaymentResponse(ParkingSubleasePaymentEntity entity) {
        if ( entity == null ) {
            return null;
        }

        Long id = null;
        Long subleaseId = null;
        Long payerUserId = null;
        Long payeeUserId = null;
        BigDecimal amount = null;
        BigDecimal stripeFee = null;
        BigDecimal platformFee = null;
        BigDecimal netAmount = null;
        String billingMonth = null;
        LocalDateTime paidAt = null;
        LocalDateTime createdAt = null;

        id = entity.getId();
        subleaseId = entity.getSubleaseId();
        payerUserId = entity.getPayerUserId();
        payeeUserId = entity.getPayeeUserId();
        amount = entity.getAmount();
        stripeFee = entity.getStripeFee();
        platformFee = entity.getPlatformFee();
        netAmount = entity.getNetAmount();
        billingMonth = entity.getBillingMonth();
        paidAt = entity.getPaidAt();
        createdAt = entity.getCreatedAt();

        String status = entity.getStatus().name();

        SubleasePaymentResponse subleasePaymentResponse = new SubleasePaymentResponse( id, subleaseId, payerUserId, payeeUserId, amount, stripeFee, platformFee, netAmount, billingMonth, status, paidAt, createdAt );

        return subleasePaymentResponse;
    }

    @Override
    public List<SubleasePaymentResponse> toSubleasePaymentResponseList(List<ParkingSubleasePaymentEntity> entities) {
        if ( entities == null ) {
            return null;
        }

        List<SubleasePaymentResponse> list = new ArrayList<SubleasePaymentResponse>( entities.size() );
        for ( ParkingSubleasePaymentEntity parkingSubleasePaymentEntity : entities ) {
            list.add( toSubleasePaymentResponse( parkingSubleasePaymentEntity ) );
        }

        return list;
    }
}

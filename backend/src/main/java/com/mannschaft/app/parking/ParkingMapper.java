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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * F09.3 駐車場区画管理の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ParkingMapper {

    // ===================== Space =====================

    @Mapping(target = "spaceType", expression = "java(entity.getSpaceType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "applicationStatus", expression = "java(entity.getApplicationStatus().name())")
    @Mapping(target = "allocationMethod", expression = "java(entity.getAllocationMethod() != null ? entity.getAllocationMethod().name() : null)")
    SpaceResponse toSpaceResponse(ParkingSpaceEntity entity);

    List<SpaceResponse> toSpaceResponseList(List<ParkingSpaceEntity> entities);

    // ===================== Vehicle =====================

    @Mapping(target = "vehicleType", expression = "java(entity.getVehicleType().name())")
    @Mapping(target = "plateNumber", ignore = true)
    VehicleResponse toVehicleResponse(RegisteredVehicleEntity entity);

    List<VehicleResponse> toVehicleResponseList(List<RegisteredVehicleEntity> entities);

    // ===================== Assignment =====================

    AssignmentResponse toAssignmentResponse(ParkingAssignmentEntity entity);

    List<AssignmentResponse> toAssignmentResponseList(List<ParkingAssignmentEntity> entities);

    // ===================== Application =====================

    @Mapping(target = "sourceType", expression = "java(entity.getSourceType().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ApplicationResponse toApplicationResponse(ParkingApplicationEntity entity);

    List<ApplicationResponse> toApplicationResponseList(List<ParkingApplicationEntity> entities);

    // ===================== Listing =====================

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ListingResponse toListingResponse(ParkingListingEntity entity);

    List<ListingResponse> toListingResponseList(List<ParkingListingEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    ListingDetailResponse toListingDetailResponse(ParkingListingEntity entity);

    // ===================== Visitor Reservation =====================

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    VisitorReservationResponse toVisitorReservationResponse(ParkingVisitorReservationEntity entity);

    List<VisitorReservationResponse> toVisitorReservationResponseList(List<ParkingVisitorReservationEntity> entities);

    // ===================== Watchlist =====================

    @Mapping(target = "spaceType", expression = "java(entity.getSpaceType() != null ? entity.getSpaceType().name() : null)")
    WatchlistResponse toWatchlistResponse(ParkingWatchlistEntity entity);

    List<WatchlistResponse> toWatchlistResponseList(List<ParkingWatchlistEntity> entities);

    // ===================== Price History =====================

    PriceHistoryResponse toPriceHistoryResponse(ParkingSpacePriceHistoryEntity entity);

    List<PriceHistoryResponse> toPriceHistoryResponseList(List<ParkingSpacePriceHistoryEntity> entities);

    // ===================== Visitor Recurring =====================

    @Mapping(target = "recurrenceType", expression = "java(entity.getRecurrenceType().name())")
    VisitorRecurringResponse toVisitorRecurringResponse(ParkingVisitorRecurringEntity entity);

    List<VisitorRecurringResponse> toVisitorRecurringResponseList(List<ParkingVisitorRecurringEntity> entities);

    // ===================== Settings =====================

    ParkingSettingsResponse toSettingsResponse(ParkingSettingsEntity entity);

    // ===================== Sublease =====================

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "paymentMethod", expression = "java(entity.getPaymentMethod().name())")
    SubleaseResponse toSubleaseResponse(ParkingSubleaseEntity entity);

    List<SubleaseResponse> toSubleaseResponseList(List<ParkingSubleaseEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "paymentMethod", expression = "java(entity.getPaymentMethod().name())")
    SubleaseDetailResponse toSubleaseDetailResponse(ParkingSubleaseEntity entity);

    // ===================== Sublease Application =====================

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    SubleaseApplicationResponse toSubleaseApplicationResponse(ParkingSubleaseApplicationEntity entity);

    List<SubleaseApplicationResponse> toSubleaseApplicationResponseList(List<ParkingSubleaseApplicationEntity> entities);

    // ===================== Sublease Payment =====================

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    SubleasePaymentResponse toSubleasePaymentResponse(ParkingSubleasePaymentEntity entity);

    List<SubleasePaymentResponse> toSubleasePaymentResponseList(List<ParkingSubleasePaymentEntity> entities);
}

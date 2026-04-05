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
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * 施設予約機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface FacilityMapper {

    @Mapping(target = "facilityType", expression = "java(entity.getFacilityType().name())")
    FacilityResponse toFacilityResponse(SharedFacilityEntity entity);

    List<FacilityResponse> toFacilityResponseList(List<SharedFacilityEntity> entities);

    @Mapping(target = "facilityType", expression = "java(entity.getFacilityType().name())")
    @Mapping(target = "imageUrls", ignore = true)
    FacilityDetailResponse toFacilityDetailResponse(SharedFacilityEntity entity);

    UsageRuleResponse toUsageRuleResponse(FacilityUsageRuleEntity entity);

    @Mapping(target = "dayType", expression = "java(entity.getDayType().name())")
    TimeRateResponse toTimeRateResponse(FacilityTimeRateEntity entity);

    List<TimeRateResponse> toTimeRateResponseList(List<FacilityTimeRateEntity> entities);

    EquipmentResponse toEquipmentResponse(FacilityEquipmentEntity entity);

    List<EquipmentResponse> toEquipmentResponseList(List<FacilityEquipmentEntity> entities);

    @Mapping(target = "facilityName", ignore = true)
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    BookingResponse toBookingResponse(FacilityBookingEntity entity);

    @Mapping(target = "facilityName", ignore = true)
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    @Mapping(target = "equipment", ignore = true)
    BookingDetailResponse toBookingDetailResponse(FacilityBookingEntity entity);

    @Mapping(target = "facilityName", ignore = true)
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    CalendarBookingResponse toCalendarBookingResponse(FacilityBookingEntity entity);

    List<CalendarBookingResponse> toCalendarBookingResponseList(List<FacilityBookingEntity> entities);

    @Mapping(target = "paymentMethod", expression = "java(entity.getPaymentMethod().name())")
    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    BookingPaymentResponse toBookingPaymentResponse(FacilityBookingPaymentEntity entity);

    FacilitySettingsResponse toSettingsResponse(FacilitySettingsEntity entity);
}

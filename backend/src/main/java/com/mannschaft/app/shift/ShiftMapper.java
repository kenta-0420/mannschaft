package com.mannschaft.app.shift;

import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.dto.MemberWorkConstraintResponse;
import com.mannschaft.app.shift.dto.ShiftPositionResponse;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * シフト管理機能の Entity → DTO 変換マッパー。
 */
@Mapper(componentModel = "spring")
public interface ShiftMapper {

    /**
     * ShiftScheduleEntity を ShiftScheduleResponse に変換する。
     * Enum → String の変換（periodType / status）は MapStruct のネストサブメソッド内で
     * expression の変数スコープが変わるため、default メソッドで手動実装する。
     */
    default ShiftScheduleResponse toScheduleResponse(ShiftScheduleEntity entity) {
        if (entity == null) {
            return null;
        }
        return ShiftScheduleResponse.builder()
                .id(entity.getId())
                .teamId(entity.getTeamId())
                .content(new ShiftScheduleResponse.ShiftContentDto(
                        entity.getTitle(),
                        entity.getPeriodType().name(),
                        entity.getNote()))
                .period(new ShiftScheduleResponse.ShiftPeriodDto(
                        entity.getStartDate(),
                        entity.getEndDate(),
                        entity.getRequestDeadline()))
                .status(new ShiftScheduleResponse.ShiftStatusDto(
                        entity.getStatus().name(),
                        entity.getPublishedAt(),
                        entity.getPublishedBy()))
                .audit(new ShiftScheduleResponse.ShiftAuditDto(
                        entity.getCreatedBy(),
                        entity.getCreatedAt(),
                        entity.getUpdatedAt()))
                .build();
    }

    List<ShiftScheduleResponse> toScheduleResponseList(List<ShiftScheduleEntity> entities);

    ShiftPositionResponse toPositionResponse(ShiftPositionEntity entity);

    List<ShiftPositionResponse> toPositionResponseList(List<ShiftPositionEntity> entities);

    @Mapping(target = "preference", expression = "java(entity.getPreference().name())")
    ShiftRequestResponse toRequestResponse(ShiftRequestEntity entity);

    List<ShiftRequestResponse> toRequestResponseList(List<ShiftRequestEntity> entities);

    @Mapping(target = "status", expression = "java(entity.getStatus().name())")
    SwapRequestResponse toSwapResponse(ShiftSwapRequestEntity entity);

    List<SwapRequestResponse> toSwapResponseList(List<ShiftSwapRequestEntity> entities);

    @Mapping(target = "preference", expression = "java(entity.getPreference().name())")
    AvailabilityDefaultResponse toAvailabilityResponse(MemberAvailabilityDefaultEntity entity);

    List<AvailabilityDefaultResponse> toAvailabilityResponseList(List<MemberAvailabilityDefaultEntity> entities);

    HourlyRateResponse toHourlyRateResponse(ShiftHourlyRateEntity entity);

    List<HourlyRateResponse> toHourlyRateResponseList(List<ShiftHourlyRateEntity> entities);

    MemberWorkConstraintResponse toWorkConstraintResponse(MemberWorkConstraintEntity entity);

    List<MemberWorkConstraintResponse> toWorkConstraintResponseList(List<MemberWorkConstraintEntity> entities);
}

package com.mannschaft.app.shift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * シフト管理機能の Entity → DTO 変換マッパー。
 *
 * <p>SwapRequest の変換では JSON 文字列 → {@code List<Long>} の変換が必要なため、
 * abstract class として Jackson の {@link ObjectMapper} を DI する。
 */
@Slf4j
@Mapper(componentModel = "spring")
public abstract class ShiftMapper {

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * ShiftScheduleEntity を ShiftScheduleResponse に変換する。
     * Enum → String の変換（periodType / status）は MapStruct のネストサブメソッド内で
     * expression の変数スコープが変わるため、default メソッドで手動実装する。
     */
    public ShiftScheduleResponse toScheduleResponse(ShiftScheduleEntity entity) {
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

    public abstract List<ShiftScheduleResponse> toScheduleResponseList(List<ShiftScheduleEntity> entities);

    public abstract ShiftPositionResponse toPositionResponse(ShiftPositionEntity entity);

    public abstract List<ShiftPositionResponse> toPositionResponseList(List<ShiftPositionEntity> entities);

    @Mapping(target = "preference", expression = "java(entity.getPreference().name())")
    public abstract ShiftRequestResponse toRequestResponse(ShiftRequestEntity entity);

    public abstract List<ShiftRequestResponse> toRequestResponseList(List<ShiftRequestEntity> entities);

    /**
     * ShiftSwapRequestEntity を SwapRequestResponse に変換する。
     *
     * <p>{@code targetUserIds} は JSON 配列文字列として保存されているため、
     * {@link ObjectMapper} を使って {@code List<Long>} に変換する。
     *
     * @param entity 変換元エンティティ
     * @return レスポンスDTO
     */
    public SwapRequestResponse toSwapResponse(ShiftSwapRequestEntity entity) {
        if (entity == null) {
            return null;
        }
        return SwapRequestResponse.builder()
                .id(entity.getId())
                .slotId(entity.getSlotId())
                .requesterId(entity.getRequesterId())
                .accepterId(entity.getAccepterId())
                .status(entity.getStatus().name())
                .reason(entity.getReason())
                .adminNote(entity.getAdminNote())
                .resolvedBy(entity.getResolvedBy())
                .resolvedAt(entity.getResolvedAt())
                .createdAt(entity.getCreatedAt())
                .recipientMode(entity.getRecipientMode())
                .targetUserIds(parseTargetUserIds(entity.getTargetUserIds()))
                .claimedBy(entity.getClaimedBy())
                .claimedAt(entity.getClaimedAt())
                .build();
    }

    /**
     * ShiftSwapRequestEntity リストを SwapRequestResponse リストに変換する。
     *
     * @param entities 変換元エンティティリスト
     * @return レスポンスDTOリスト
     */
    public List<SwapRequestResponse> toSwapResponseList(List<ShiftSwapRequestEntity> entities) {
        if (entities == null) {
            return Collections.emptyList();
        }
        return entities.stream()
                .map(this::toSwapResponse)
                .collect(Collectors.toList());
    }

    @Mapping(target = "preference", expression = "java(entity.getPreference().name())")
    public abstract AvailabilityDefaultResponse toAvailabilityResponse(MemberAvailabilityDefaultEntity entity);

    public abstract List<AvailabilityDefaultResponse> toAvailabilityResponseList(List<MemberAvailabilityDefaultEntity> entities);

    public abstract HourlyRateResponse toHourlyRateResponse(ShiftHourlyRateEntity entity);

    public abstract List<HourlyRateResponse> toHourlyRateResponseList(List<ShiftHourlyRateEntity> entities);

    public abstract MemberWorkConstraintResponse toWorkConstraintResponse(MemberWorkConstraintEntity entity);

    public abstract List<MemberWorkConstraintResponse> toWorkConstraintResponseList(List<MemberWorkConstraintEntity> entities);

    /**
     * JSON 配列文字列を {@code List<Long>} に変換する。
     *
     * <p>パース失敗時はログ出力のうえ空リストを返す（障害が伝播しないよう fail-safe）。
     *
     * @param json JSON 配列文字列（例: "[1,2,3]"）
     * @return ユーザーIDリスト（null または空文字列の場合は null を返す）
     */
    private List<Long> parseTargetUserIds(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("targetUserIds の JSON パースに失敗しました: json={}, error={}", json, e.getMessage());
            return Collections.emptyList();
        }
    }
}

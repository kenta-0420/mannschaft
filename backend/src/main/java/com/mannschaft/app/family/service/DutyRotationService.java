package com.mannschaft.app.family.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.FamilyErrorCode;
import com.mannschaft.app.family.RotationType;
import com.mannschaft.app.family.dto.DutyRotationRequest;
import com.mannschaft.app.family.dto.DutyRotationResponse;
import com.mannschaft.app.family.dto.DutyTodayResponse;
import com.mannschaft.app.family.entity.DutyRotationEntity;
import com.mannschaft.app.family.repository.DutyRotationRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DutyRotationService {

    private static final int MAX_DUTIES_PER_TEAM = 10;
    private final DutyRotationRepository dutyRotationRepository;
    private final ObjectMapper objectMapper;

    public ApiResponse<List<DutyRotationResponse>> getDuties(Long teamId) {
        List<DutyRotationEntity> duties = dutyRotationRepository.findByTeamIdAndDeletedAtIsNullOrderByCreatedAtAsc(teamId);
        return ApiResponse.of(duties.stream().map(this::toResponse).toList());
    }

    @Transactional
    public ApiResponse<DutyRotationResponse> createDuty(Long teamId, Long userId, DutyRotationRequest request) {
        long count = dutyRotationRepository.countByTeamIdAndDeletedAtIsNull(teamId);
        if (count >= MAX_DUTIES_PER_TEAM) { throw new BusinessException(FamilyErrorCode.FAMILY_017); }
        RotationType rotationType = request.getRotationType() != null
                ? RotationType.valueOf(request.getRotationType().toUpperCase()) : RotationType.DAILY;
        DutyRotationEntity entity = DutyRotationEntity.builder()
                .teamId(teamId).dutyName(request.getDutyName()).rotationType(rotationType)
                .memberOrder(toJson(request.getMemberOrder())).startDate(request.getStartDate())
                .icon(request.getIcon()).isEnabled(request.getIsEnabled() != null ? request.getIsEnabled() : true)
                .createdBy(userId).build();
        return ApiResponse.of(toResponse(dutyRotationRepository.save(entity)));
    }

    @Transactional
    public ApiResponse<DutyRotationResponse> updateDuty(Long teamId, Long dutyId, DutyRotationRequest request) {
        DutyRotationEntity entity = findDutyOrThrow(dutyId);
        RotationType rotationType = request.getRotationType() != null
                ? RotationType.valueOf(request.getRotationType().toUpperCase()) : entity.getRotationType();
        entity.update(request.getDutyName(), rotationType, toJson(request.getMemberOrder()),
                request.getStartDate(), request.getIcon(),
                request.getIsEnabled() != null ? request.getIsEnabled() : entity.getIsEnabled());
        return ApiResponse.of(toResponse(entity));
    }

    @Transactional
    public void deleteDuty(Long teamId, Long dutyId) {
        DutyRotationEntity entity = findDutyOrThrow(dutyId);
        entity.softDelete();
    }

    public ApiResponse<List<DutyTodayResponse>> getTodayDuties(Long teamId) {
        List<DutyRotationEntity> duties = dutyRotationRepository
                .findByTeamIdAndDeletedAtIsNullAndIsEnabledTrueOrderByCreatedAtAsc(teamId);
        List<DutyTodayResponse> responses = duties.stream().map(duty -> {
            List<Long> members = fromJson(duty.getMemberOrder());
            Long assignee = calculateTodayAssignee(duty, members);
            return new DutyTodayResponse(duty.getId(), duty.getDutyName(), duty.getIcon(), assignee, duty.getRotationType().name());
        }).toList();
        return ApiResponse.of(responses);
    }

    private Long calculateTodayAssignee(DutyRotationEntity entity, List<Long> members) {
        if (members == null || members.isEmpty()) { return null; }
        long daysDiff = ChronoUnit.DAYS.between(entity.getStartDate(), LocalDate.now());
        if (daysDiff < 0) { return members.get(0); }
        int rotationDays = RotationType.WEEKLY.equals(entity.getRotationType()) ? 7 : 1;
        int index = (int) ((daysDiff / rotationDays) % members.size());
        return members.get(index);
    }

    private DutyRotationEntity findDutyOrThrow(Long dutyId) {
        return dutyRotationRepository.findByIdAndDeletedAtIsNull(dutyId)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_016));
    }

    private DutyRotationResponse toResponse(DutyRotationEntity entity) {
        List<Long> members = fromJson(entity.getMemberOrder());
        Long todayAssignee = Boolean.TRUE.equals(entity.getIsEnabled()) ? calculateTodayAssignee(entity, members) : null;
        return new DutyRotationResponse(entity.getId(), entity.getTeamId(), entity.getDutyName(),
                entity.getRotationType().name(), members, entity.getStartDate(), entity.getIcon(),
                Boolean.TRUE.equals(entity.getIsEnabled()), todayAssignee, entity.getCreatedAt());
    }

    private String toJson(List<Long> list) {
        try { return objectMapper.writeValueAsString(list); }
        catch (JsonProcessingException e) { throw new IllegalStateException("JSON変換に失敗しました", e); }
    }

    private List<Long> fromJson(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("JSONパースに失敗しました", e); }
    }
}

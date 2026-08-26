package com.mannschaft.app.family.service;

import com.mannschaft.app.common.AccessControlService;
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

import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DutyRotationService {

    private static final int MAX_DUTIES_PER_TEAM = 10;
    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private final DutyRotationRepository dutyRotationRepository;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;

    public ApiResponse<List<DutyRotationResponse>> getDuties(Long teamId, Long actorUserId) {
        // 認可根治 Wave2-2C: 閲覧=checkMembership
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TYPE_TEAM);
        List<DutyRotationEntity> duties = dutyRotationRepository.findByTeamIdAndDeletedAtIsNullOrderByCreatedAtAsc(teamId);
        return ApiResponse.of(duties.stream().map(this::toResponse).toList());
    }

    @Transactional
    public ApiResponse<DutyRotationResponse> createDuty(Long teamId, Long userId, DutyRotationRequest request) {
        // 認可根治 Wave2-2C: 当番作成は ADMIN 用 EP（既存仕様）のため checkAdminOrAbove
        accessControlService.checkAdminOrAbove(userId, teamId, SCOPE_TYPE_TEAM);
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
    public ApiResponse<DutyRotationResponse> updateDuty(Long teamId, Long dutyId, Long actorUserId,
                                                        DutyRotationRequest request) {
        DutyRotationEntity entity = findDutyInTeamOrThrow(teamId, dutyId);
        accessControlService.checkAdminOrAbove(actorUserId, entity.getTeamId(), SCOPE_TYPE_TEAM);
        RotationType rotationType = request.getRotationType() != null
                ? RotationType.valueOf(request.getRotationType().toUpperCase()) : entity.getRotationType();
        entity.update(request.getDutyName(), rotationType, toJson(request.getMemberOrder()),
                request.getStartDate(), request.getIcon(),
                request.getIsEnabled() != null ? request.getIsEnabled() : entity.getIsEnabled());
        return ApiResponse.of(toResponse(entity));
    }

    @Transactional
    public void deleteDuty(Long teamId, Long dutyId, Long actorUserId) {
        DutyRotationEntity entity = findDutyInTeamOrThrow(teamId, dutyId);
        accessControlService.checkAdminOrAbove(actorUserId, entity.getTeamId(), SCOPE_TYPE_TEAM);
        entity.softDelete();
    }

    public ApiResponse<List<DutyTodayResponse>> getTodayDuties(Long teamId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TYPE_TEAM);
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
        long daysDiff = ChronoUnit.DAYS.between(entity.getStartDate(), LocalDate.now(TimezoneContextHolder.get()));
        if (daysDiff < 0) { return members.get(0); }
        int rotationDays = RotationType.WEEKLY.equals(entity.getRotationType()) ? 7 : 1;
        int index = (int) ((daysDiff / rotationDays) % members.size());
        return members.get(index);
    }

    /**
     * 当番を取得し、entity 由来の teamId とパス teamId の一致を検証する。
     * 不一致（他チームの当番 ID 指定 = BOLA）は存在秘匿のため FAMILY_016（404）を返す。
     */
    private DutyRotationEntity findDutyInTeamOrThrow(Long teamId, Long dutyId) {
        DutyRotationEntity entity = dutyRotationRepository.findByIdAndDeletedAtIsNull(dutyId)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_016));
        if (!entity.getTeamId().equals(teamId)) {
            throw new BusinessException(FamilyErrorCode.FAMILY_016);
        }
        return entity;
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

package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.DutyRotationRequest;
import com.mannschaft.app.family.dto.DutyRotationResponse;
import com.mannschaft.app.family.dto.DutyTodayResponse;
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

/**
 * 当番ローテーションサービス。当番のCRUD・今日の担当者算出を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DutyRotationService {

    private static final int MAX_DUTIES_PER_TEAM = 10;

    private final DutyRotationRepository dutyRotationRepository;
    private final ObjectMapper objectMapper;

    /**
     * チームの当番ローテーション一覧を取得する（今日の担当者付き）。
     *
     * @param teamId チームID
     * @return 当番一覧
     */
    public ApiResponse<List<DutyRotationResponse>> getDuties(Long teamId) {
        List<DutyRotationEntity> duties = dutyRotationRepository
                .findByTeamIdAndDeletedAtIsNullOrderByCreatedAtAsc(teamId);
        return ApiResponse.of(duties.stream().map(this::toResponse).toList());
    }

    /**
     * 当番ローテーションを作成する。
     *
     * @param teamId  チームID
     * @param userId  ユーザーID
     * @param request リクエスト
     * @return 作成された当番
     */
    @Transactional
    public ApiResponse<DutyRotationResponse> createDuty(Long teamId, Long userId, DutyRotationRequest request) {
        long count = dutyRotationRepository.countByTeamIdAndDeletedAtIsNull(teamId);
        if (count >= MAX_DUTIES_PER_TEAM) {
            throw new BusinessException(FamilyErrorCode.FAMILY_017);
        }

        RotationType rotationType = request.getRotationType() != null
                ? RotationType.valueOf(request.getRotationType().toUpperCase())
                : RotationType.DAILY;

        DutyRotationEntity entity = DutyRotationEntity.builder()
                .teamId(teamId)
                .dutyName(request.getDutyName())
                .rotationType(rotationType)
                .memberOrder(toJson(request.getMemberOrder()))
                .startDate(request.getStartDate())
                .icon(request.getIcon())
                .isEnabled(request.getIsEnabled() != null ? request.getIsEnabled() : true)
                .createdBy(userId)
                .build();

        return ApiResponse.of(toResponse(dutyRotationRepository.save(entity)));
    }

    /**
     * 当番ローテーションを更新する。
     *
     * @param teamId  チームID
     * @param dutyId  当番ID
     * @param request リクエスト
     * @return 更新された当番
     */
    @Transactional
    public ApiResponse<DutyRotationResponse> updateDuty(Long teamId, Long dutyId, DutyRotationRequest request) {
        DutyRotationEntity entity = findDutyOrThrow(dutyId);

        RotationType rotationType = request.getRotationType() != null
                ? RotationType.valueOf(request.getRotationType().toUpperCase())
                : entity.getRotationType();

        entity.update(
                request.getDutyName(),
                rotationType,
                toJson(request.getMemberOrder()),
                request.getStartDate(),
                request.getIcon(),
                request.getIsEnabled() != null ? request.getIsEnabled() : entity.getIsEnabled()
        );

        return ApiResponse.of(toResponse(entity));
    }

    /**
     * 当番ローテーションを削除する（論理削除）。
     *
     * @param teamId チームID
     * @param dutyId 当番ID
     */
    @Transactional
    public void deleteDuty(Long teamId, Long dutyId) {
        DutyRotationEntity entity = findDutyOrThrow(dutyId);
        entity.softDelete();
    }

    /**
     * 今日の当番一覧を取得する（ダッシュボードウィジェット用）。
     *
     * @param teamId チームID
     * @return 今日の当番一覧
     */
    public ApiResponse<List<DutyTodayResponse>> getTodayDuties(Long teamId) {
        List<DutyRotationEntity> duties = dutyRotationRepository
                .findByTeamIdAndDeletedAtIsNullAndIsEnabledTrueOrderByCreatedAtAsc(teamId);

        List<DutyTodayResponse> responses = duties.stream()
                .map(duty -> {
                    List<Long> members = fromJson(duty.getMemberOrder());
                    Long assignee = calculateTodayAssignee(duty, members);
                    return new DutyTodayResponse(
                            duty.getId(), duty.getDutyName(), duty.getIcon(),
                            assignee, duty.getRotationType().name()
                    );
                })
                .toList();

        return ApiResponse.of(responses);
    }

    /**
     * 今日の担当者を算出する。
     *
     * @param entity  当番エンティティ
     * @param members メンバーID配列
     * @return 今日の担当者ID。メンバーが空の場合はnull
     */
    private Long calculateTodayAssignee(DutyRotationEntity entity, List<Long> members) {
        if (members == null || members.isEmpty()) {
            return null;
        }

        long daysDiff = ChronoUnit.DAYS.between(entity.getStartDate(), LocalDate.now());
        if (daysDiff < 0) {
            return members.get(0);
        }

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
        Long todayAssignee = Boolean.TRUE.equals(entity.getIsEnabled())
                ? calculateTodayAssignee(entity, members) : null;

        return new DutyRotationResponse(
                entity.getId(), entity.getTeamId(), entity.getDutyName(),
                entity.getRotationType().name(), members, entity.getStartDate(),
                entity.getIcon(), Boolean.TRUE.equals(entity.getIsEnabled()),
                todayAssignee, entity.getCreatedAt()
        );
    }

    private String toJson(List<Long> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON変換に失敗しました", e);
        }
    }

    private List<Long> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSONパースに失敗しました", e);
        }
    }
}

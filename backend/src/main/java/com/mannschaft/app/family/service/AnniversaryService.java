package com.mannschaft.app.family.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.FamilyErrorCode;
import com.mannschaft.app.family.dto.AnniversaryRequest;
import com.mannschaft.app.family.dto.AnniversaryResponse;
import com.mannschaft.app.family.entity.TeamAnniversaryEntity;
import com.mannschaft.app.family.repository.TeamAnniversaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 記念日リマインダーサービス。記念日のCRUD・通知対象検索を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnniversaryService {

    private static final int MAX_ANNIVERSARIES_PER_TEAM = 50;
    private static final int UPCOMING_DAYS = 30;
    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final TeamAnniversaryRepository teamAnniversaryRepository;
    private final AccessControlService accessControlService;

    public ApiResponse<List<AnniversaryResponse>> getAnniversaries(Long teamId, Long actorUserId) {
        // 認可根治 Wave2-2C: 記念日はチーム内共有データ。非メンバーの閲覧を 403 で拒否する
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TYPE_TEAM);
        List<TeamAnniversaryEntity> anniversaries = teamAnniversaryRepository
                .findByTeamIdAndDeletedAtIsNullOrderByDateAsc(teamId);
        return ApiResponse.of(anniversaries.stream().map(this::toResponse).toList());
    }

    @Transactional
    public ApiResponse<AnniversaryResponse> createAnniversary(Long teamId, Long userId, AnniversaryRequest request) {
        // 認可根治 Wave2-2C: 家族ユーティリティのため作成は全メンバー可（既存仕様）。非メンバーは 403
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE_TEAM);
        long count = teamAnniversaryRepository.countByTeamIdAndDeletedAtIsNull(teamId);
        if (count >= MAX_ANNIVERSARIES_PER_TEAM) {
            throw new BusinessException(FamilyErrorCode.FAMILY_019);
        }
        TeamAnniversaryEntity entity = TeamAnniversaryEntity.builder()
                .teamId(teamId).name(request.getName()).date(request.getDate())
                .repeatAnnually(request.getRepeatAnnually() != null ? request.getRepeatAnnually() : true)
                .notifyDaysBefore(request.getNotifyDaysBefore() != null ? request.getNotifyDaysBefore() : 1)
                .createdBy(userId).build();
        return ApiResponse.of(toResponse(teamAnniversaryRepository.save(entity)));
    }

    @Transactional
    public ApiResponse<AnniversaryResponse> updateAnniversary(Long teamId, Long id, Long actorUserId,
                                                              AnniversaryRequest request) {
        TeamAnniversaryEntity entity = findInTeamOrThrow(teamId, id);
        accessControlService.checkMembership(actorUserId, entity.getTeamId(), SCOPE_TYPE_TEAM);
        entity.update(request.getName(), request.getDate(),
                request.getRepeatAnnually() != null ? request.getRepeatAnnually() : entity.getRepeatAnnually(),
                request.getNotifyDaysBefore() != null ? request.getNotifyDaysBefore() : entity.getNotifyDaysBefore());
        return ApiResponse.of(toResponse(entity));
    }

    @Transactional
    public void deleteAnniversary(Long teamId, Long id, Long actorUserId) {
        TeamAnniversaryEntity entity = findInTeamOrThrow(teamId, id);
        accessControlService.checkMembership(actorUserId, entity.getTeamId(), SCOPE_TYPE_TEAM);
        entity.softDelete();
    }

    public ApiResponse<List<AnniversaryResponse>> getUpcoming(Long teamId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TYPE_TEAM);
        LocalDate today = LocalDate.now();
        LocalDate to = today.plusDays(UPCOMING_DAYS);
        List<TeamAnniversaryEntity> upcoming = teamAnniversaryRepository.findUpcoming(teamId, today, to);
        return ApiResponse.of(upcoming.stream().map(this::toResponse).toList());
    }

    /**
     * 記念日を取得し、entity 由来の teamId とパス teamId の一致を検証する。
     * 不一致（他チームの記念日 ID 指定 = BOLA）は存在秘匿のため FAMILY_018（404）を返す。
     */
    private TeamAnniversaryEntity findInTeamOrThrow(Long teamId, Long id) {
        TeamAnniversaryEntity entity = teamAnniversaryRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_018));
        if (!entity.getTeamId().equals(teamId)) {
            throw new BusinessException(FamilyErrorCode.FAMILY_018);
        }
        return entity;
    }

    private AnniversaryResponse toResponse(TeamAnniversaryEntity entity) {
        return new AnniversaryResponse(entity.getId(), entity.getTeamId(), entity.getName(),
                entity.getDate(), Boolean.TRUE.equals(entity.getRepeatAnnually()),
                entity.getNotifyDaysBefore(), entity.getCreatedBy(), entity.getCreatedAt());
    }
}

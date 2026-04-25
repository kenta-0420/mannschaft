package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.WorkConstraintRequest;
import com.mannschaft.app.shift.dto.WorkConstraintResponse;
import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import com.mannschaft.app.shift.repository.MemberWorkConstraintRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト勤務制約サービス。チームデフォルトおよびメンバー個別の勤務制約 CRUD を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftWorkConstraintService {

    private final MemberWorkConstraintRepository constraintRepository;

    /**
     * チームの勤務制約一覧を取得する（デフォルト + メンバー個別全件）。
     *
     * @param teamId チームID
     * @return 勤務制約一覧
     */
    public List<WorkConstraintResponse> getConstraints(Long teamId) {
        List<MemberWorkConstraintEntity> entities = constraintRepository.findAllByTeamId(teamId);
        return entities.stream().map(this::toResponse).toList();
    }

    /**
     * チームデフォルト勤務制約を作成または更新する。
     *
     * @param teamId  チームID
     * @param request 制約リクエスト
     * @return 勤務制約レスポンス
     */
    @Transactional
    public WorkConstraintResponse upsertDefault(Long teamId, WorkConstraintRequest request) {
        MemberWorkConstraintEntity entity = constraintRepository
                .findByTeamIdAndUserIdIsNull(teamId)
                .orElseGet(() -> MemberWorkConstraintEntity.builder()
                        .teamId(teamId)
                        .userId(null)
                        .build());

        entity.update(
                request.maxMonthlyHours(),
                request.maxMonthlyDays(),
                request.maxConsecutiveDays(),
                request.maxNightShiftsPerMonth(),
                request.minRestHoursBetweenShifts(),
                request.note());

        entity = constraintRepository.save(entity);
        log.info("チームデフォルト勤務制約更新: teamId={}", teamId);
        return toResponse(entity);
    }

    /**
     * メンバー個別の勤務制約を作成または更新する。
     *
     * @param teamId  チームID
     * @param userId  ユーザーID
     * @param request 制約リクエスト
     * @return 勤務制約レスポンス
     */
    @Transactional
    public WorkConstraintResponse upsertMember(Long teamId, Long userId, WorkConstraintRequest request) {
        MemberWorkConstraintEntity entity = constraintRepository
                .findByTeamIdAndUserId(teamId, userId)
                .orElseGet(() -> MemberWorkConstraintEntity.builder()
                        .teamId(teamId)
                        .userId(userId)
                        .build());

        entity.update(
                request.maxMonthlyHours(),
                request.maxMonthlyDays(),
                request.maxConsecutiveDays(),
                request.maxNightShiftsPerMonth(),
                request.minRestHoursBetweenShifts(),
                request.note());

        entity = constraintRepository.save(entity);
        log.info("メンバー個別勤務制約更新: teamId={}, userId={}", teamId, userId);
        return toResponse(entity);
    }

    /**
     * メンバー個別の勤務制約を削除する。
     *
     * @param teamId チームID
     * @param userId ユーザーID
     */
    @Transactional
    public void deleteMember(Long teamId, Long userId) {
        MemberWorkConstraintEntity entity = constraintRepository
                .findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.WORK_CONSTRAINT_NOT_FOUND));

        constraintRepository.delete(entity);
        log.info("メンバー個別勤務制約削除: teamId={}, userId={}", teamId, userId);
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     */
    private WorkConstraintResponse toResponse(MemberWorkConstraintEntity entity) {
        return new WorkConstraintResponse(
                entity.getId(),
                entity.getTeamId(),
                entity.getUserId(),
                entity.getMaxMonthlyHours(),
                entity.getMaxMonthlyDays(),
                entity.getMaxConsecutiveDays(),
                entity.getMaxNightShiftsPerMonth(),
                entity.getMinRestHoursBetweenShifts(),
                entity.getNote());
    }
}

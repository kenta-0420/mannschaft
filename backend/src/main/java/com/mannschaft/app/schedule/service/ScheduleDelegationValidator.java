package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.schedule.ScheduleDelegationStatus;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * スケジュール代理出席のバリデーション（F03.10 §5.6）。
 *
 * <p>代理指定時の前提条件を検証する。スコープ所属判定は {@link MembershipRepository}（F00.5 基盤）の
 * アクティブメンバーシップで判定し、連鎖代理禁止・重複・親スケジュール・ステータスを横断的にチェックする。
 * クロスドメイン参照（membership）は読み取りのみで、整合性はアプリ層で保証する（CLAUDE.md 原則1）。</p>
 */
@Component
@RequiredArgsConstructor
public class ScheduleDelegationValidator {

    /** アクティブ（重複・連鎖判定対象）とみなすステータス群。 */
    private static final List<ScheduleDelegationStatus> ACTIVE_STATUSES =
            List.of(ScheduleDelegationStatus.PENDING, ScheduleDelegationStatus.ACCEPTED);

    private final MembershipRepository membershipRepository;
    private final ScheduleDelegationRepository delegationRepository;

    /**
     * 代理指定時のバリデーションを実行する（§5.6 #1〜#8）。
     *
     * @param schedule    対象スケジュール
     * @param delegatorId 委任者 user_id
     * @param delegateId  代理人 user_id
     */
    public void validateForCreate(ScheduleEntity schedule, Long delegatorId, Long delegateId) {
        // #1: allow_proxy_attendance = TRUE
        if (!Boolean.TRUE.equals(schedule.getAllowProxyAttendance())) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_ALLOWED);
        }
        // #7: スケジュールが CANCELLED/COMPLETED でない
        if (schedule.getStatus() == ScheduleStatus.CANCELLED
                || schedule.getStatus() == ScheduleStatus.COMPLETED) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_INVALID_SCHEDULE_STATUS);
        }
        // #8: 親（繰り返し）スケジュールへの直接指定は不可
        //   親 = parent_schedule_id IS NULL かつ recurrence_rule IS NOT NULL
        if (schedule.getParentScheduleId() == null && schedule.isRecurring()) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_PARENT_SCHEDULE);
        }
        // #4: 自己代理不可
        if (delegatorId.equals(delegateId)) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_SELF_DELEGATION);
        }

        // スコープ解決（XOR: organization or team）
        ScopeType scopeType = schedule.isOrganizationScope() ? ScopeType.ORGANIZATION : ScopeType.TEAM;
        Long scopeId = schedule.isOrganizationScope() ? schedule.getOrganizationId() : schedule.getTeamId();

        // #2: 委任者はスコープのメンバー（403）
        if (!membershipRepository.existsActiveByUserAndScope(delegatorId, scopeType, scopeId)) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_DELEGATOR_NOT_MEMBER);
        }
        // #3: 代理人はスコープのメンバー（422）
        if (!membershipRepository.existsActiveByUserAndScope(delegateId, scopeType, scopeId)) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_DELEGATE_NOT_MEMBER);
        }

        // #5: 委任者のアクティブ代理が既に存在しない（409）
        delegationRepository
                .findFirstByScheduleIdAndDelegatorIdAndStatusIn(schedule.getId(), delegatorId, ACTIVE_STATUSES)
                .ifPresent(existing -> {
                    throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_ALREADY_EXISTS);
                });

        // #6: 連鎖代理禁止 — 代理人が他者の代理として PENDING/ACCEPTED でない（422）
        if (delegationRepository.existsByDelegateIdAndStatusIn(delegateId, ACTIVE_STATUSES)) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_CHAINED);
        }
    }
}

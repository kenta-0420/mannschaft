package com.mannschaft.app.returnstayplan.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.returnstayplan.ReturnStayPlanErrorCode;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanRepository;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanTeamVisibilityRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 帰省・滞在予定の所有者境界。不存在と他人の予定を同じ404へ写像する。 */
@Component
@RequiredArgsConstructor
public class ReturnStayPlanAccessGuard {
    private final ReturnStayPlanRepository repository;
    private final ReturnStayPlanTeamVisibilityRepository visibilities;

    public ReturnStayPlanEntity findByIdAndOwnerUserId(UUID planId, Long ownerUserId) {
        return repository.findByIdAndOwnerUserId(planId, ownerUserId)
                .orElseThrow(() -> new BusinessException(ReturnStayPlanErrorCode.NOT_FOUND));
    }

    /**
     * TEAM 予定閲覧の公開入口ゲート。viewer が slug のチームに ACTIVE 在籍している場合のみ
     * その team_id を返し、非在籍・不在チームは {@code TEAM_ACCESS_DENIED} で弾く。
     * サービス層 {@code listVisiblePlansForMembers} も同じ認可を持つ多層防御だが、
     * 認可を「公開エンドポイントの入口」で明示する（AuthzControllerGuardArchTest 準拠）。
     */
    public Long requireAuthorizedTeamId(String teamSlug, Long viewerUserId) {
        return visibilities.findAuthorizedTeamId(teamSlug, viewerUserId)
                .orElseThrow(() -> new BusinessException(ReturnStayPlanErrorCode.TEAM_ACCESS_DENIED));
    }
}

package com.mannschaft.app.returnstayplan.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.returnstayplan.ReturnStayPlanErrorCode;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 帰省・滞在予定の所有者境界。不存在と他人の予定を同じ404へ写像する。 */
@Component
@RequiredArgsConstructor
public class ReturnStayPlanAccessGuard {
    private final ReturnStayPlanRepository repository;

    public ReturnStayPlanEntity findByIdAndOwnerUserId(UUID planId, Long ownerUserId) {
        return repository.findByIdAndOwnerUserId(planId, ownerUserId)
                .orElseThrow(() -> new BusinessException(ReturnStayPlanErrorCode.NOT_FOUND));
    }
}

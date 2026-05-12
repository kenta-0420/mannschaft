package com.mannschaft.app.repairplan.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.repairplan.entity.ExternalAgentDelegation;

import java.util.List;
import java.util.UUID;

/**
 * 外部エージェント委任リポジトリ。
 */
public interface ExternalAgentDelegationRepository
        extends AbstractTenantAwareRepository<ExternalAgentDelegation, UUID> {

    /** スコープ × 種別単位の有効な委任取得。 */
    List<ExternalAgentDelegation> findByScopeTypeAndScopeIdAndDelegationTypeAndRevokedAtIsNullAndDeletedAtIsNull(
            String scopeType, Long scopeId, String delegationType);

    /** スコープ単位の全有効委任取得。 */
    List<ExternalAgentDelegation> findByScopeTypeAndScopeIdAndRevokedAtIsNullAndDeletedAtIsNull(
            String scopeType, Long scopeId);

    /** 管理会社担当者単位の有効委任取得。 */
    List<ExternalAgentDelegation> findByAgentUserIdAndRevokedAtIsNullAndDeletedAtIsNull(Long agentUserId);
}

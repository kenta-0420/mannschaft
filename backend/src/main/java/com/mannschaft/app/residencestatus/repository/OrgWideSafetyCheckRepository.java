package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.residencestatus.entity.OrgWideSafetyCheck;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.16 管理組合横展開安否確認ラッパ リポジトリ。
 */
public interface OrgWideSafetyCheckRepository
        extends AbstractTenantAwareRepository<OrgWideSafetyCheck, UUID> {

    /** F03.6 safety_check_id からの逆引き（同期クローズ用）。 */
    Optional<OrgWideSafetyCheck> findBySafetyCheckIdAndDeletedAtIsNull(Long safetyCheckId);

    /** 組織の未クローズな組合横展開安否確認一覧。 */
    List<OrgWideSafetyCheck> findByOrganizationIdAndClosedAtIsNullAndDeletedAtIsNull(Long organizationId);
}

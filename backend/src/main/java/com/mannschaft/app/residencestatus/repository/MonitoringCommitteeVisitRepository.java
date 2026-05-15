package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.residencestatus.entity.MonitoringCommitteeVisit;

import java.util.List;
import java.util.UUID;

/**
 * F09.16 見守り委員訪問記録 リポジトリ。
 */
public interface MonitoringCommitteeVisitRepository
        extends AbstractTenantAwareRepository<MonitoringCommitteeVisit, UUID> {

    /** 委員会の訪問記録（直近順）。 */
    List<MonitoringCommitteeVisit> findByCommitteeIdAndDeletedAtIsNullOrderByVisitedAtDesc(Long committeeId);

    /** 訪問対象ユーザーの訪問履歴（直近順）。 */
    List<MonitoringCommitteeVisit> findBySubjectUserIdAndDeletedAtIsNullOrderByVisitedAtDesc(Long subjectUserId);

    /** 居住者の訪問履歴（直近順）。 */
    List<MonitoringCommitteeVisit> findByResidentRegistryIdAndDeletedAtIsNullOrderByVisitedAtDesc(
            Long residentRegistryId);

    /** 訪問者（WATCHER）の自身の訪問履歴（24h 以内更新可否判定にも使用）。 */
    List<MonitoringCommitteeVisit> findByVisitorUserIdAndDeletedAtIsNullOrderByVisitedAtDesc(Long visitorUserId);

    /**
     * 訪問者（WATCHER）の組織スコープ付き訪問履歴（直近順）。
     * S4-A: getVisitsByWatcher() で使用する。
     */
    List<MonitoringCommitteeVisit> findByVisitorUserIdAndOrganizationIdAndDeletedAtIsNullOrderByVisitedAtDesc(
            Long visitorUserId, Long organizationId);
}

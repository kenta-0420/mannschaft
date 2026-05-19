package com.mannschaft.app.publicview.repository;

import com.mannschaft.app.publicview.entity.OrganizationNameDisclosureChangeLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 組織名前開示モード変更履歴リポジトリ。
 *
 * <p>F19.1 Phase 2: 組織の {@code supporter_name_disclosure} 変更履歴を管理する。
 * UI での「過去 1 年の切替履歴表示」や監査目的で使用する。</p>
 */
public interface OrganizationNameDisclosureChangeLogRepository extends JpaRepository<OrganizationNameDisclosureChangeLogEntity, UUID> {

    /**
     * 指定組織の変更履歴を変更日時の降順で取得する。
     *
     * @param organizationId 組織 ID
     * @return 変更履歴リスト（降順）
     */
    List<OrganizationNameDisclosureChangeLogEntity> findByOrganizationIdOrderByChangedAtDesc(Long organizationId);
}

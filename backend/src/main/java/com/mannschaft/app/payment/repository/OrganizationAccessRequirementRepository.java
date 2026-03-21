package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.OrganizationAccessRequirementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 組織全体ロック用支払い要件リポジトリ。
 */
public interface OrganizationAccessRequirementRepository extends JpaRepository<OrganizationAccessRequirementEntity, Long> {

    /**
     * 組織 ID で支払い要件一覧を取得する。
     */
    List<OrganizationAccessRequirementEntity> findByOrganizationId(Long organizationId);

    /**
     * 組織 ID で全件削除する（一括設定の置換用）。
     */
    void deleteByOrganizationId(Long organizationId);

    /**
     * 支払い項目 ID で全件削除する（支払い項目論理削除時のクリーンアップ用）。
     */
    void deleteByPaymentItemId(Long paymentItemId);
}

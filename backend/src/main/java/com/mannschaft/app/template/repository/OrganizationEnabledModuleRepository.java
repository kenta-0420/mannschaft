package com.mannschaft.app.template.repository;

import com.mannschaft.app.template.entity.OrganizationEnabledModuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 組織有効モジュールリポジトリ。
 * organization_enabled_modules はテナント×モジュール紐付けテーブルのため
 * JpaRepository を継承する（AbstractTenantAwareRepository は使用しない）。
 */
public interface OrganizationEnabledModuleRepository extends JpaRepository<OrganizationEnabledModuleEntity, Long> {

    /**
     * 組織の有効モジュール一覧を取得する。
     *
     * @param organizationId 組織ID
     * @return 有効モジュールエンティティリスト
     */
    List<OrganizationEnabledModuleEntity> findByOrganizationId(Long organizationId);

    /**
     * 組織とモジュールIDで有効化状態を取得する。
     *
     * @param organizationId 組織ID
     * @param moduleId       モジュールID
     * @return 有効化状態エンティティ（存在しない場合は空）
     */
    Optional<OrganizationEnabledModuleEntity> findByOrganizationIdAndModuleId(Long organizationId, Long moduleId);

    /**
     * 有効なモジュール数を取得する（Analytics 等の汎用集計用）。
     *
     * @param organizationId 組織ID
     * @return 有効なモジュール数
     */
    long countByOrganizationIdAndIsEnabledTrue(Long organizationId);

    /**
     * 無料プラン上限チェック用の有効モジュール数を取得する。
     * グランドファザリング行（is_grandfathered=1）は既得機能として上限カウントから除外する。
     *
     * @param organizationId 組織ID
     * @return グランドファザリングを除いた有効モジュール数
     */
    long countByOrganizationIdAndIsEnabledTrueAndIsGrandfatheredFalse(Long organizationId);
}

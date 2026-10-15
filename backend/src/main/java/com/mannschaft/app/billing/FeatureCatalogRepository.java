package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F20.1: 機能カタログリポジトリ（{@code feature_catalog}・マスタ例外・非テナント）。
 *
 * <p>{@code fee_policies} 前例（自然キー・organization_id 無し・素の {@code JpaRepository}）に倣う
 * （設計書 01 §0）。このフェーズでは Repo 骨格のみ（CRUD Service はシスアド機能として別部隊）。</p>
 */
public interface FeatureCatalogRepository extends JpaRepository<FeatureCatalogEntity, String> {

    /** カタログ表示用（enabled のみ・sort_order 昇順）。 */
    List<FeatureCatalogEntity> findByEnabledTrueOrderBySortOrderAsc();

    /** category（INTERNAL/REVENUE）で絞り込む。 */
    List<FeatureCatalogEntity> findByCategory(FeatureCategory category);
}

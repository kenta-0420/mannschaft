package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F20.1: 人数バンド別単価リポジトリ（{@code plan_price_bands}・マスタ例外・非テナント）。
 *
 * <p>このフェーズでは Repo 骨格のみ（バンド解決 Service・CRUD Service は別部隊）。</p>
 */
public interface PlanPriceBandRepository extends JpaRepository<PlanPriceBandEntity, PlanPriceBandId> {

    /** 指定プラン×スコープ種別のバンド一覧を band_no 昇順で取得する（単価解決に使用）。 */
    List<PlanPriceBandEntity> findByPlanKeyAndScopeKindOrderByBandNoAsc(
            String planKey, PlanPriceBandScopeKind scopeKind);
}

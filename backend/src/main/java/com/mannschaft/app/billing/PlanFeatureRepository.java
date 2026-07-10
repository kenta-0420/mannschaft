package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * F20.1: プラン→機能の展開表リポジトリ（{@code plan_features}・マスタ例外・非テナント）。
 */
public interface PlanFeatureRepository extends JpaRepository<PlanFeatureEntity, PlanFeatureId> {

    /** 指定プランに紐づく機能キー一覧を取得する（isEntitled の FREE 掲載判定・プラン提示に使用）。 */
    List<PlanFeatureEntity> findByPlanKey(String planKey);

    /** 指定機能キーがどのプランに含まれるかを取得する（シスアド CRUD の整合検証用）。 */
    List<PlanFeatureEntity> findByFeatureKey(String featureKey);

    /**
     * 指定プランに指定 feature_key が掲載されているか（{@code isEntitled} の FREE 掲載判定・設計書 02 §1.1）。
     */
    boolean existsByPlanKeyAndFeatureKey(String planKey, String featureKey);

    /**
     * 指定 feature_key を掲載する<b>購入可能な</b>プラン（enabled=true かつ FREE 以外）が存在するか
     * （{@code EntitlementGuard} の 402/403 判定・設計書 02 §1.2）。
     *
     * <p>{@code plans} を JOIN して enabled を確認する。FREE プランは購入導線に載せない（掲載機能は無料判定で
     * 別途 true になるため、ここでは非 FREE の有料プランだけを購入可能と見なす）。</p>
     */
    @Query("SELECT COUNT(pf) > 0 FROM PlanFeatureEntity pf, PlanEntity p "
            + "WHERE pf.planKey = p.planKey AND pf.featureKey = :featureKey "
            + "AND p.enabled = true AND pf.planKey <> 'FREE'")
    boolean existsPurchasablePlanContaining(@Param("featureKey") String featureKey);
}

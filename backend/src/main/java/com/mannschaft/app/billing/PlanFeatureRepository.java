package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F20.1: プラン→機能の展開表リポジトリ（{@code plan_features}・マスタ例外・非テナント）。
 *
 * <p>このフェーズでは Repo 骨格のみ（プラン提示 Service・CRUD Service は別部隊）。</p>
 */
public interface PlanFeatureRepository extends JpaRepository<PlanFeatureEntity, PlanFeatureId> {

    /** 指定プランに紐づく機能キー一覧を取得する（isEntitled の FREE 掲載判定・プラン提示に使用）。 */
    List<PlanFeatureEntity> findByPlanKey(String planKey);

    /** 指定機能キーがどのプランに含まれるかを取得する（シスアド CRUD の整合検証用）。 */
    List<PlanFeatureEntity> findByFeatureKey(String featureKey);
}

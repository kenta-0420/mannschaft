package com.mannschaft.app.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * F20.1: プランリポジトリ（{@code plans}・マスタ例外・非テナント）。
 *
 * <p>このフェーズでは Repo 骨格のみ（プラン提示 Service・CRUD Service は別部隊）。</p>
 */
public interface PlanRepository extends JpaRepository<PlanEntity, String> {

    /** プラン一覧表示用（enabled のみ・sort_order 昇順）。 */
    List<PlanEntity> findByEnabledTrueOrderBySortOrderAsc();
}

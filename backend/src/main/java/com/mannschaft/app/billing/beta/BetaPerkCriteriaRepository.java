package com.mannschaft.app.billing.beta;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F20.3 {@code beta_perk_criteria} リポジトリ。マスタ例外（非テナント・複合自然キー）ゆえ
 * {@link JpaRepository} を直接継承する（{@code fee_policies} 前例・設計書 01 §2）。
 *
 * <p>主キーは {@link BetaPerkCriteriaId}（{@code beta_phase}, {@code grant_kind}）。付与判定
 * （{@code BetaPerkEligibilityService}・02 §2）は {@code findById(new BetaPerkCriteriaId(phase, kind))}
 * で条件行を引く。</p>
 */
public interface BetaPerkCriteriaRepository
        extends JpaRepository<BetaPerkCriteriaEntity, BetaPerkCriteriaId> {
}

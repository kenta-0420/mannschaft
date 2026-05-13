package com.mannschaft.app.repairplan.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * v_repair_fund_balance ビューリポジトリ（読み取り専用）。
 *
 * <p>シミュレーション実行時の初期残高取得に使用する。
 * スコープ種別（TEAM / ORGANIZATION）× スコープ ID の組で 1 件を取得する。</p>
 */
public interface RepairFundBalanceRepository extends JpaRepository<RepairFundBalanceView, Long> {

    /**
     * スコープ種別とスコープ ID で積立金残高を取得する。
     * 該当レコードが存在しない場合は Optional.empty() を返す（残高 0 扱いとする）。
     */
    Optional<RepairFundBalanceView> findByScopeTypeAndScopeId(String scopeType, Long scopeId);
}

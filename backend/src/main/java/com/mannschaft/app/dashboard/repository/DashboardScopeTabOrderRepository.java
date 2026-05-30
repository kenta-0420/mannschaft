package com.mannschaft.app.dashboard.repository;

import com.mannschaft.app.dashboard.entity.DashboardScopeTabOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1: ダッシュボードのチーム/組織タグ表示順リポジトリ。
 *
 * <p>本テーブルは user_id 単位の個人設定であり {@code organization_id} を持たないため、
 * {@code AbstractTenantAwareRepository}（原則7）は不適用とする
 * （01_db_design.md §3 判断記録）。代わりに全クエリで {@code user_id} を必須条件とし、
 * IDOR を防ぐ。</p>
 */
public interface DashboardScopeTabOrderRepository
        extends JpaRepository<DashboardScopeTabOrderEntity, UUID> {

    /**
     * 指定ユーザー × スコープ種別の保存済み表示順を sort_order 昇順で全件取得する。
     */
    List<DashboardScopeTabOrderEntity> findByUserIdAndScopeTypeOrderBySortOrderAsc(
            Long userId, String scopeType);

    /**
     * UPSERT のための既存行検索（unique key: user_id, scope_type, scope_id）。
     */
    Optional<DashboardScopeTabOrderEntity> findByUserIdAndScopeTypeAndScopeId(
            Long userId, String scopeType, Long scopeId);

    /**
     * 指定ユーザーの全行を物理削除する（GDPR 退会時の弱匿名化区分。03_security_ux.md §1.6）。
     *
     * <p>本フェーズではメソッド定義のみ。退会フローの Listener / AccountPurgeService からの
     * 呼び出し配線は別フェーズで行う（症状隠蔽を避けるため、未配線である旨を明記する）。</p>
     */
    void deleteByUserId(Long userId);
}

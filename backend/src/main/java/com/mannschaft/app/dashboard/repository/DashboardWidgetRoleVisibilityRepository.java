package com.mannschaft.app.dashboard.repository;

import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.entity.DashboardWidgetRoleVisibilityEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * F02.2.1: ダッシュボードウィジェットのロール別可視性設定リポジトリ。
 *
 * スコープ（チーム／組織）×ウィジェットごとの最低必要ロール（min_role）レコードを操作する。
 * Service 層からは:
 * <ul>
 *   <li>{@link #findByScopeTypeAndScopeId} でスコープの全設定を一括取得（GET エンドポイント）</li>
 *   <li>{@link #findByScopeTypeAndScopeIdAndWidgetKey} で個別レコードを取得（PUT 差分更新）</li>
 *   <li>{@link #deleteByScopeTypeAndScopeIdAndWidgetKey} でデフォルトに戻す際の削除（PUT で min_role がデフォルトと一致した場合）</li>
 * </ul>
 *
 * <p>設計書: docs/features/F02.2.1_dashboard_widget_role_visibility.md §3, §5</p>
 */
public interface DashboardWidgetRoleVisibilityRepository
        extends JpaRepository<DashboardWidgetRoleVisibilityEntity, Long> {

    /**
     * 指定スコープに紐付くロール別可視性設定の一覧を取得する。
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   スコープID
     * @return 該当する設定一覧（レコードがないウィジェットはアプリ層デフォルト値が適用される）
     */
    List<DashboardWidgetRoleVisibilityEntity> findByScopeTypeAndScopeId(
            ScopeType scopeType, Long scopeId);

    /**
     * 指定スコープ×ウィジェットキーの設定を1件取得する。
     */
    Optional<DashboardWidgetRoleVisibilityEntity> findByScopeTypeAndScopeIdAndWidgetKey(
            ScopeType scopeType, Long scopeId, String widgetKey);

    /**
     * 指定スコープ×ウィジェットキーの設定を削除する（デフォルトに戻す操作）。
     */
    @Modifying
    @Query("DELETE FROM DashboardWidgetRoleVisibilityEntity e "
            + "WHERE e.scopeType = :scopeType AND e.scopeId = :scopeId AND e.widgetKey = :widgetKey")
    void deleteByScopeTypeAndScopeIdAndWidgetKey(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("widgetKey") String widgetKey);
}

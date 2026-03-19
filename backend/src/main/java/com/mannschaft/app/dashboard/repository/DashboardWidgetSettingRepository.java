package com.mannschaft.app.dashboard.repository;

import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.entity.DashboardWidgetSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ダッシュボードウィジェット設定のリポジトリ。
 */
public interface DashboardWidgetSettingRepository extends JpaRepository<DashboardWidgetSettingEntity, Long> {

    /**
     * 指定ユーザー×スコープのウィジェット設定一覧を並び順で取得する。
     */
    List<DashboardWidgetSettingEntity> findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(
            Long userId, ScopeType scopeType, Long scopeId);

    /**
     * 指定ユーザー×スコープ×ウィジェットキーで1件取得する。
     */
    Optional<DashboardWidgetSettingEntity> findByUserIdAndScopeTypeAndScopeIdAndWidgetKey(
            Long userId, ScopeType scopeType, Long scopeId, String widgetKey);

    /**
     * 指定ユーザー×スコープの全設定を削除する（リセット機能用）。
     */
    @Modifying
    @Query("DELETE FROM DashboardWidgetSettingEntity e WHERE e.userId = :userId AND e.scopeType = :scopeType AND e.scopeId = :scopeId")
    void deleteByUserIdAndScopeTypeAndScopeId(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);
}

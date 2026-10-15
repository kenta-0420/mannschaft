package com.mannschaft.app.performance.repository;

import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * パフォーマンス指標リポジトリ。
 */
public interface PerformanceMetricRepository extends JpaRepository<PerformanceMetricEntity, Long> {

    /**
     * チームの指標一覧を表示順で取得する。
     */
    List<PerformanceMetricEntity> findByTeamIdOrderBySortOrderAsc(Long teamId);

    /**
     * チームの有効な指標一覧を取得する。
     */
    List<PerformanceMetricEntity> findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(Long teamId);

    /**
     * チームのIDと指標IDで取得する。
     */
    Optional<PerformanceMetricEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * チームの有効な指標数をカウントする。
     */
    long countByTeamIdAndIsActiveTrue(Long teamId);

    /**
     * チームの指標名一覧を取得する（テンプレート重複チェック用）。
     */
    @Query("SELECT m.name FROM PerformanceMetricEntity m WHERE m.teamId = :teamId AND m.isActive = true")
    List<String> findActiveNamesByTeamId(@Param("teamId") Long teamId);

    /**
     * 活動記録フィールドIDで紐付く指標を取得する。
     */
    Optional<PerformanceMetricEntity> findByLinkedActivityFieldId(Long linkedActivityFieldId);

    /**
     * チームのメンバーに公開されている指標一覧を取得する。
     */
    List<PerformanceMetricEntity> findByTeamIdAndIsVisibleToMembersTrueOrderBySortOrderAsc(Long teamId);
}

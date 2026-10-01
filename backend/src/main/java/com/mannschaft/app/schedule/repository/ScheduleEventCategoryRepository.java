package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * スケジュール行事カテゴリリポジトリ。
 */
public interface ScheduleEventCategoryRepository extends JpaRepository<ScheduleEventCategoryEntity, Long> {

    /**
     * チームIDでカテゴリ一覧を取得する（sortOrder昇順）。
     */
    List<ScheduleEventCategoryEntity> findByTeamIdOrderBySortOrder(Long teamId);

    /**
     * 組織IDでカテゴリ一覧を取得する（sortOrder昇順）。
     */
    List<ScheduleEventCategoryEntity> findByOrganizationIdOrderBySortOrder(Long orgId);

    /**
     * 複数の組織IDのカテゴリを一括取得する（sortOrder 昇順→id 昇順。組織ごとの並べ替えは呼び出し側で行う）。
     * {@code orgIds} は非空であること。
     */
    List<ScheduleEventCategoryEntity> findByOrganizationIdInOrderBySortOrderAscIdAsc(
            java.util.Collection<Long> orgIds);

    /**
     * チーム内で同名カテゴリの存在を確認する。
     */
    boolean existsByTeamIdAndName(Long teamId, String name);

    /**
     * 組織内で同名カテゴリの存在を確認する。
     */
    boolean existsByOrganizationIdAndName(Long orgId, String name);
}

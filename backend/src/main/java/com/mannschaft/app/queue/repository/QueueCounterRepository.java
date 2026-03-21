package com.mannschaft.app.queue.repository;

import com.mannschaft.app.queue.entity.QueueCounterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 順番待ちカウンターリポジトリ。
 */
public interface QueueCounterRepository extends JpaRepository<QueueCounterEntity, Long> {

    /**
     * カテゴリIDでカウンター一覧を表示順で取得する。
     */
    List<QueueCounterEntity> findByCategoryIdOrderByDisplayOrderAsc(Long categoryId);

    /**
     * IDとカテゴリIDでカウンターを取得する。
     */
    Optional<QueueCounterEntity> findByIdAndCategoryId(Long id, Long categoryId);

    /**
     * カテゴリIDでアクティブなカウンター数を取得する。
     */
    long countByCategoryIdAndIsActiveTrue(Long categoryId);
}

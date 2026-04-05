package com.mannschaft.app.signage.repository;

import com.mannschaft.app.signage.entity.SignageScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * デジタルサイネージ スケジュールリポジトリ。
 */
public interface SignageScheduleRepository extends JpaRepository<SignageScheduleEntity, Long> {

    /**
     * 画面IDに紐づくスケジュール一覧を優先度降順で取得する。
     */
    List<SignageScheduleEntity> findByScreenIdOrderByPriorityDesc(Long screenId);
}

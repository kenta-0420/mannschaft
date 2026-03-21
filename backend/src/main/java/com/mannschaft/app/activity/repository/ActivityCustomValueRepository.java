package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.entity.ActivityCustomValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 活動レベルカスタムフィールド値リポジトリ。
 */
public interface ActivityCustomValueRepository extends JpaRepository<ActivityCustomValueEntity, Long> {

    List<ActivityCustomValueEntity> findByActivityResultId(Long activityResultId);

    void deleteByActivityResultId(Long activityResultId);
}

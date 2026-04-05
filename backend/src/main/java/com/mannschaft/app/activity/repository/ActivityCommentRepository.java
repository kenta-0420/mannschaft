package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 活動記録コメントリポジトリ。
 */
public interface ActivityCommentRepository extends JpaRepository<ActivityCommentEntity, Long> {

    List<ActivityCommentEntity> findByActivityResultIdOrderByCreatedAtAsc(Long activityResultId);
}

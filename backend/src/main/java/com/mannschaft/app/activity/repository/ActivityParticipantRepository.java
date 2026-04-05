package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 活動参加者リポジトリ。
 */
public interface ActivityParticipantRepository extends JpaRepository<ActivityParticipantEntity, Long> {

    List<ActivityParticipantEntity> findByActivityResultIdOrderByCreatedAtAsc(Long activityResultId);

    Optional<ActivityParticipantEntity> findByActivityResultIdAndUserId(Long activityResultId, Long userId);

    void deleteByActivityResultIdAndUserIdIn(Long activityResultId, List<Long> userIds);

    long countByActivityResultId(Long activityResultId);

    long countByUserId(Long userId);
}

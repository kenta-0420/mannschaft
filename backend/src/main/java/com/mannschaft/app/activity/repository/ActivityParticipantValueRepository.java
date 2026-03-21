package com.mannschaft.app.activity.repository;

import com.mannschaft.app.activity.entity.ActivityParticipantValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 参加者レベルカスタムフィールド値リポジトリ。
 */
public interface ActivityParticipantValueRepository extends JpaRepository<ActivityParticipantValueEntity, Long> {

    List<ActivityParticipantValueEntity> findByParticipantId(Long participantId);

    void deleteByParticipantId(Long participantId);
}

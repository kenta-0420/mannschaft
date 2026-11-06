package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 臨時休業リポジトリ。
 */
public interface EmergencyClosureRepository extends JpaRepository<EmergencyClosureEntity, Long> {

    /**
     * チームの臨時休業履歴を新しい順に取得する。
     */
    List<EmergencyClosureEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    /**
     * 冪等キーで送信済みの臨時休業を取得する（CMP-261104-0334）。キーはチーム×送信者で区別する。
     */
    Optional<EmergencyClosureEntity> findByTeamIdAndCreatedByAndIdempotencyKey(
            Long teamId, Long createdBy, String idempotencyKey);
}

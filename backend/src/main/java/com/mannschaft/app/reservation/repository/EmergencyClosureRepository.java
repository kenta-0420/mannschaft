package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 臨時休業リポジトリ。
 */
public interface EmergencyClosureRepository extends JpaRepository<EmergencyClosureEntity, Long> {

    /**
     * チームの臨時休業履歴を新しい順に取得する。
     */
    List<EmergencyClosureEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);
}

package com.mannschaft.app.family;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 当番ローテーションリポジトリ。
 */
public interface DutyRotationRepository extends JpaRepository<DutyRotationEntity, Long> {

    /**
     * チームの有効な当番ローテーション一覧を取得する。
     */
    List<DutyRotationEntity> findByTeamIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long teamId);

    /**
     * チームの有効な当番ローテーション（有効フラグON）を取得する。
     */
    List<DutyRotationEntity> findByTeamIdAndDeletedAtIsNullAndIsEnabledTrueOrderByCreatedAtAsc(Long teamId);

    /**
     * チームの論理削除されていないローテーション数を取得する。
     */
    long countByTeamIdAndDeletedAtIsNull(Long teamId);

    /**
     * ID + 論理削除除外で取得する。
     */
    Optional<DutyRotationEntity> findByIdAndDeletedAtIsNull(Long id);
}

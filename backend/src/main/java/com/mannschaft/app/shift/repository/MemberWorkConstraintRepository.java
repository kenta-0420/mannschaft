package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * メンバー勤務制約リポジトリ。
 */
public interface MemberWorkConstraintRepository extends JpaRepository<MemberWorkConstraintEntity, Long> {

    /**
     * チームのデフォルト制約を取得する（userId = NULL）。
     */
    Optional<MemberWorkConstraintEntity> findByTeamIdAndUserIdIsNull(Long teamId);

    /**
     * メンバー個別の制約を取得する。
     */
    Optional<MemberWorkConstraintEntity> findByTeamIdAndUserId(Long teamId, Long userId);

    /**
     * チームの全制約一覧を取得する（デフォルト + 個別）。
     */
    List<MemberWorkConstraintEntity> findAllByTeamId(Long teamId);
}

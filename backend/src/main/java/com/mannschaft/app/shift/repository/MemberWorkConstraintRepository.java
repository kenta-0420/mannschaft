package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * メンバー勤務制約リポジトリ（F03.5 v2）。
 */
public interface MemberWorkConstraintRepository extends JpaRepository<MemberWorkConstraintEntity, Long> {

    /**
     * 指定ユーザー・チームの個別勤務制約を取得する。
     */
    Optional<MemberWorkConstraintEntity> findByUserIdAndTeamId(Long userId, Long teamId);

    /**
     * チームのデフォルト制約を取得する（userId = NULL）— 互換用。
     */
    default Optional<MemberWorkConstraintEntity> findByTeamIdAndUserIdIsNull(Long teamId) {
        return findTeamDefault(teamId);
    }

    /**
     * メンバー個別の制約を取得する — 互換用。
     */
    default Optional<MemberWorkConstraintEntity> findByTeamIdAndUserId(Long teamId, Long userId) {
        return findByUserIdAndTeamId(userId, teamId);
    }

    /**
     * 指定チームの全勤務制約（個別 + チームデフォルト含む）を取得する。
     */
    List<MemberWorkConstraintEntity> findByTeamId(Long teamId);

    /**
     * チームの全制約一覧を取得する — 互換用。
     */
    default List<MemberWorkConstraintEntity> findAllByTeamId(Long teamId) {
        return findByTeamId(teamId);
    }

    /**
     * チームデフォルト（{@code user_id IS NULL}）を取得する。
     *
     * <p>JPQL で明示的に IS NULL を記述することで Hibernate の挙動差を回避する。</p>
     */
    @Query("SELECT c FROM MemberWorkConstraintEntity c "
            + "WHERE c.teamId = :teamId AND c.userId IS NULL")
    Optional<MemberWorkConstraintEntity> findTeamDefault(@Param("teamId") Long teamId);
}

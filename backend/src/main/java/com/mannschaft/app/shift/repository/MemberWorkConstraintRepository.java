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
     * 指定チームの全勤務制約（個別 + チームデフォルト含む）を取得する。
     */
    List<MemberWorkConstraintEntity> findByTeamId(Long teamId);

    /**
     * チームデフォルト（{@code user_id IS NULL}）を取得する。
     *
     * <p>JPA の derived query では {@code findByUserIdNullAndTeamId} のように NULL 検索を
     * 宣言できるが、実装ごとの挙動差（Hibernate で {@code IS NULL} に置換されるかどうか）を
     * 避けるため JPQL で明示的に記述する。</p>
     */
    @Query("SELECT c FROM MemberWorkConstraintEntity c "
            + "WHERE c.teamId = :teamId AND c.userId IS NULL")
    Optional<MemberWorkConstraintEntity> findTeamDefault(@Param("teamId") Long teamId);
}

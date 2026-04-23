package com.mannschaft.app.shift.repository;

import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * メンバー勤務制約リポジトリ（F03.5 v2）。
 *
 * <p>追加メソッドは後続フェーズ（足軽弐）で必要に応じて拡張する前提で、
 * ここでは最小限のルックアップのみ提供する。</p>
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
}

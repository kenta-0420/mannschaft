package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.UserBadgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ユーザーバッジリポジトリ。
 */
public interface UserBadgeRepository extends JpaRepository<UserBadgeEntity, Long> {

    /**
     * ユーザーIDで取得済みバッジ一覧を取得する。
     */
    List<UserBadgeEntity> findByUserId(Long userId);

    /**
     * バッジとユーザーと期間ラベルで既存バッジを確認する（重複防止）。
     */
    boolean existsByBadgeIdAndUserIdAndPeriodLabel(Long badgeId, Long userId, String periodLabel);
}

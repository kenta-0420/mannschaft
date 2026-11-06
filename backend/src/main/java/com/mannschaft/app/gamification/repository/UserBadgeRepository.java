package com.mannschaft.app.gamification.repository;

import com.mannschaft.app.gamification.entity.UserBadgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * ユーザーバッジリポジトリ。
 */
public interface UserBadgeRepository extends JpaRepository<UserBadgeEntity, Long> {

    /**
     * ユーザーIDで取得済みバッジ一覧を取得する。
     */
    List<UserBadgeEntity> findByUserId(Long userId);

    /** 本人の取得履歴をID順の有界ページで読む。旧取得APIの無制限読取とは分離する。 */
    List<UserBadgeEntity> findByUserIdAndIdGreaterThanOrderByIdAsc(
            Long userId, Long afterId, Pageable pageable);

    /**
     * バッジとユーザーと期間ラベルで既存バッジを確認する（繰返し可バッジの重複防止）。
     */
    boolean existsByBadgeIdAndUserIdAndPeriodLabel(Long badgeId, Long userId, String periodLabel);

    /**
     * バッジとユーザーで既存バッジを確認する（非繰返しバッジの重複防止）。
     */
    boolean existsByBadgeIdAndUserId(Long badgeId, Long userId);
}

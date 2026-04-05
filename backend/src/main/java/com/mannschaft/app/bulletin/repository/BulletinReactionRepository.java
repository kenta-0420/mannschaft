package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.entity.BulletinReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 掲示板リアクションリポジトリ。
 */
public interface BulletinReactionRepository extends JpaRepository<BulletinReactionEntity, Long> {

    /**
     * ターゲットのリアクション一覧を取得する。
     */
    List<BulletinReactionEntity> findByTargetTypeAndTargetId(TargetType targetType, Long targetId);

    /**
     * リアクションの重複チェック。
     */
    boolean existsByTargetTypeAndTargetIdAndUserIdAndEmoji(
            TargetType targetType, Long targetId, Long userId, String emoji);

    /**
     * リアクションを取得する。
     */
    Optional<BulletinReactionEntity> findByTargetTypeAndTargetIdAndUserIdAndEmoji(
            TargetType targetType, Long targetId, Long userId, String emoji);

    /**
     * ターゲットの絵文字別リアクション数を取得する。
     */
    @Query("SELECT r.emoji, COUNT(r) FROM BulletinReactionEntity r WHERE r.targetType = :targetType AND r.targetId = :targetId GROUP BY r.emoji")
    List<Object[]> countByTargetGroupedByEmoji(@Param("targetType") TargetType targetType, @Param("targetId") Long targetId);
}

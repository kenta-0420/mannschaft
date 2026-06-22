package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.entity.BulletinReactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * ターゲット集合の絵文字別リアクション数を一括取得する（N+1 回避・一覧 enrichment 用）。
     *
     * <p>{@code [targetId, emoji, count]} の行を返す。スレッド一覧の reactionSummary を
     * 1 クエリで解決するために使用する。</p>
     *
     * @param targetType ターゲット種別
     * @param targetIds  ターゲット ID 集合
     * @return {@code Object[]{Long targetId, String emoji, Long count}} のリスト
     */
    @Query("SELECT r.targetId, r.emoji, COUNT(r) FROM BulletinReactionEntity r "
            + "WHERE r.targetType = :targetType AND r.targetId IN :targetIds "
            + "GROUP BY r.targetId, r.emoji")
    List<Object[]> countByTargetIdsGroupedByEmoji(
            @Param("targetType") TargetType targetType, @Param("targetIds") Collection<Long> targetIds);

    /**
     * ターゲット集合に対する特定ユーザーのリアクション（押下した絵文字）を一括取得する
     * （N+1 回避・一覧 enrichment の myReactions 用）。
     *
     * <p>{@code [targetId, emoji]} の行を返す。</p>
     *
     * @param targetType ターゲット種別
     * @param targetIds  ターゲット ID 集合
     * @param userId     ユーザー ID
     * @return {@code Object[]{Long targetId, String emoji}} のリスト
     */
    @Query("SELECT r.targetId, r.emoji FROM BulletinReactionEntity r "
            + "WHERE r.targetType = :targetType AND r.targetId IN :targetIds AND r.userId = :userId")
    List<Object[]> findUserReactionsByTargetIds(
            @Param("targetType") TargetType targetType,
            @Param("targetIds") Collection<Long> targetIds,
            @Param("userId") Long userId);
}

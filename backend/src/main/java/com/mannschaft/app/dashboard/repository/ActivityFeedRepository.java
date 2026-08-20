package com.mannschaft.app.dashboard.repository;

import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * アクティビティフィードのリポジトリ。
 *
 * <p><strong>整列キーの規約（F03.18 §4.2 裁定）</strong>: 3本のクエリメソッドはすべて
 * {@code ORDER BY a.id DESC} で整列する。カーソル条件が {@code a.id < :cursor} である以上、
 * 整列キーを {@code createdAt} にすると「id は新しいが createdAt は古い」行が生じたときに
 * ページ境界で行の重複・欠落が起こる。番人 {@code ActivityFeedRepositoryContractGuardTest} が
 * この規約を機械検証する。</p>
 *
 * <p><strong>可視性の規約（F03.18 AC-16）</strong>: 本 Repository に {@code visibility} や
 * {@code min_view_role} を用いた独自の閲覧述語を書いてはならない。可視性の正準は
 * {@code AbstractContentVisibilityResolver#filterAccessible} 一本であり、Repository に
 * 述語を増やすと漏洩源が二重化する。ここで行うのは「所属スコープでの絞り込み」までである。</p>
 *
 * <p><strong>スコープの型別ペアリング</strong>: {@code scopeId} は TEAM 系列と ORGANIZATION 系列で
 * 独立した採番であり、平坦な ID リストで {@code scopeId IN (...)} を書くと
 * 「チーム42」の所属者に「組織42」の活動が見えてしまう。そのため所属チーム ID と
 * 所属組織 ID を別パラメータで受け、scopeType と対で突き合わせる。</p>
 */
public interface ActivityFeedRepository extends JpaRepository<ActivityFeedEntity, Long> {

    /**
     * 単一スコープ種別のアクティビティを id 降順で取得する（自分の行動を除外）。
     */
    @Query("SELECT a FROM ActivityFeedEntity a " +
            "WHERE a.scopeType = :scopeType AND a.scopeId IN :scopeIds AND a.actorId <> :excludeActorId " +
            "ORDER BY a.id DESC")
    List<ActivityFeedEntity> findByScopeAndExcludeActor(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeIds") List<Long> scopeIds,
            @Param("excludeActorId") Long excludeActorId,
            Pageable pageable);

    /**
     * カーソルベースページネーション用：指定IDより古いアクティビティを id 降順で取得する。
     *
     * @param teamIds 視聴者の所属チームID（空リスト不可。呼出元がセンチネルを詰める）
     * @param orgIds  視聴者の所属組織ID（空リスト不可。呼出元がセンチネルを詰める）
     */
    @Query("SELECT a FROM ActivityFeedEntity a " +
            "WHERE a.actorId <> :excludeActorId AND a.id < :cursor " +
            "AND ((a.scopeType = com.mannschaft.app.dashboard.ScopeType.TEAM AND a.scopeId IN :teamIds) " +
            "  OR (a.scopeType = com.mannschaft.app.dashboard.ScopeType.ORGANIZATION AND a.scopeId IN :orgIds)) " +
            "ORDER BY a.id DESC")
    List<ActivityFeedEntity> findByScopeAndExcludeActorWithCursor(
            @Param("teamIds") List<Long> teamIds,
            @Param("orgIds") List<Long> orgIds,
            @Param("excludeActorId") Long excludeActorId,
            @Param("cursor") Long cursor,
            Pageable pageable);

    /**
     * 所属チーム・所属組織のアクティビティを id 降順で取得する（最新から）。
     *
     * @param teamIds 視聴者の所属チームID（空リスト不可。呼出元がセンチネルを詰める）
     * @param orgIds  視聴者の所属組織ID（空リスト不可。呼出元がセンチネルを詰める）
     */
    @Query("SELECT a FROM ActivityFeedEntity a " +
            "WHERE a.actorId <> :excludeActorId " +
            "AND ((a.scopeType = com.mannschaft.app.dashboard.ScopeType.TEAM AND a.scopeId IN :teamIds) " +
            "  OR (a.scopeType = com.mannschaft.app.dashboard.ScopeType.ORGANIZATION AND a.scopeId IN :orgIds)) " +
            "ORDER BY a.id DESC")
    List<ActivityFeedEntity> findByScopesAndExcludeActor(
            @Param("teamIds") List<Long> teamIds,
            @Param("orgIds") List<Long> orgIds,
            @Param("excludeActorId") Long excludeActorId,
            Pageable pageable);

    /**
     * 同一操作者・同一対象の直近1行を取得する（F03.18 §5.4 フィード洪水対策）。
     *
     * <p>{@code ActivityFeedEventListener} が 5 分以内の連続編集をこの行へマージするために使う。
     * 整列キーは本 Repository の規約どおり {@code id DESC}（＝最新1行）。</p>
     */
    java.util.Optional<ActivityFeedEntity> findTopByActorIdAndTargetIdAndTargetTypeOrderByIdDesc(
            Long actorId, Long targetId, com.mannschaft.app.dashboard.TargetType targetType);

    /**
     * 30日超の古いレコードを物理削除する（日次バッチ用）。
     */
    @Modifying
    @Query("DELETE FROM ActivityFeedEntity a WHERE a.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") LocalDateTime threshold);
}

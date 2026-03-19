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
 */
public interface ActivityFeedRepository extends JpaRepository<ActivityFeedEntity, Long> {

    /**
     * 指定スコープ群のアクティビティを作成日時降順で取得する（自分の行動を除外）。
     */
    @Query("SELECT a FROM ActivityFeedEntity a " +
            "WHERE a.scopeType = :scopeType AND a.scopeId IN :scopeIds AND a.actorId <> :excludeActorId " +
            "ORDER BY a.createdAt DESC")
    List<ActivityFeedEntity> findByScopeAndExcludeActor(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeIds") List<Long> scopeIds,
            @Param("excludeActorId") Long excludeActorId,
            Pageable pageable);

    /**
     * カーソルベースページネーション用：指定IDより古いアクティビティを取得する。
     */
    @Query("SELECT a FROM ActivityFeedEntity a " +
            "WHERE a.scopeType IN :scopeTypes AND a.scopeId IN :scopeIds " +
            "AND a.actorId <> :excludeActorId AND a.id < :cursor " +
            "ORDER BY a.createdAt DESC")
    List<ActivityFeedEntity> findByScopeAndExcludeActorWithCursor(
            @Param("scopeTypes") List<ScopeType> scopeTypes,
            @Param("scopeIds") List<Long> scopeIds,
            @Param("excludeActorId") Long excludeActorId,
            @Param("cursor") Long cursor,
            Pageable pageable);

    /**
     * 指定スコープ群のアクティビティを作成日時降順で取得する（複数スコープタイプ対応）。
     */
    @Query("SELECT a FROM ActivityFeedEntity a " +
            "WHERE a.scopeType IN :scopeTypes AND a.scopeId IN :scopeIds AND a.actorId <> :excludeActorId " +
            "ORDER BY a.createdAt DESC")
    List<ActivityFeedEntity> findByScopesAndExcludeActor(
            @Param("scopeTypes") List<ScopeType> scopeTypes,
            @Param("scopeIds") List<Long> scopeIds,
            @Param("excludeActorId") Long excludeActorId,
            Pageable pageable);

    /**
     * 30日超の古いレコードを物理削除する（日次バッチ用）。
     */
    @Modifying
    @Query("DELETE FROM ActivityFeedEntity a WHERE a.createdAt < :threshold")
    int deleteByCreatedAtBefore(@Param("threshold") LocalDateTime threshold);
}

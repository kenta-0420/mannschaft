package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.entity.TeamFriendEntity;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * フレンドチーム関係リポジトリ。
 *
 * <p>
 * {@code team_friends} テーブルへのアクセス経路。ペアの ID 順序は常に
 * {@code teamAId &lt; teamBId} で正規化された状態で扱うこと。
 * </p>
 */
public interface TeamFriendRepository extends JpaRepository<TeamFriendEntity, Long> {

    /**
     * チーム ID のペア（正規化済み）でフレンド関係を取得する。
     *
     * @param teamAId 小さい方のチーム ID
     * @param teamBId 大きい方のチーム ID
     * @return フレンド関係エンティティ（存在しなければ空）
     */
    Optional<TeamFriendEntity> findByTeamAIdAndTeamBId(Long teamAId, Long teamBId);

    /**
     * 指定されたチームが関与するフレンド関係一覧を、成立日時の降順で取得する。
     *
     * <p>
     * 一方向のみの検索をシンプルに行うため、{@code teamAId} と {@code teamBId} のどちらに
     * 出現してもマッチするよう OR 条件で検索する。実際の呼び出しでは同じチーム ID を
     * 両引数に渡す想定。
     * </p>
     *
     * @param teamAId  チーム ID（team_a_id 側の検索値）
     * @param teamBId  チーム ID（team_b_id 側の検索値）
     * @param pageable ページング
     * @return フレンド関係一覧
     */
    List<TeamFriendEntity> findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(
            Long teamAId, Long teamBId, Pageable pageable);

    /**
     * 指定ペアのフレンド関係を削除する。
     *
     * @param teamAId 小さい方のチーム ID
     * @param teamBId 大きい方のチーム ID
     */
    void deleteByTeamAIdAndTeamBId(Long teamAId, Long teamBId);

    /**
     * 競合対策用に、指定ペアのフレンド関係を {@code SELECT ... FOR UPDATE NOWAIT} で
     * 取得する。相互フォロー成立・解除処理など、同時更新が発生しうるクリティカルセクション
     * から呼び出すこと。他トランザクションがロック中の場合は
     * {@code PessimisticLockException} / {@code CannotAcquireLockException} が即座にスローされる。
     *
     * @param teamAId 小さい方のチーム ID
     * @param teamBId 大きい方のチーム ID
     * @return ロック取得済みのフレンド関係エンティティ（存在しなければ空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("""
            SELECT tf FROM TeamFriendEntity tf
            WHERE tf.teamAId = :teamAId
              AND tf.teamBId = :teamBId
            """)
    Optional<TeamFriendEntity> findByTeamAIdAndTeamBIdForUpdateNoWait(
            @Param("teamAId") Long teamAId,
            @Param("teamBId") Long teamBId);
}

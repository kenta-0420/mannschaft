package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.entity.FollowEntity;
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
 * フォローリポジトリ。
 */
public interface FollowRepository extends JpaRepository<FollowEntity, Long> {

    /**
     * フォロー関係を取得する。
     */
    Optional<FollowEntity> findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
            FollowerType followerType, Long followerId, FollowerType followedType, Long followedId);

    /**
     * フォロー関係の存在チェックを行う。
     * 相互フォロー確認（FRIENDS_ONLY アクセス制御）にも使用する。
     */
    boolean existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
            FollowerType followerType, Long followerId, FollowerType followedType, Long followedId);

    /**
     * フォロー一覧（自分がフォローしている対象）を取得する。
     */
    List<FollowEntity> findByFollowerTypeAndFollowerIdOrderByCreatedAtDesc(
            FollowerType followerType, Long followerId, Pageable pageable);

    /**
     * フォロワー一覧（自分をフォローしている対象）を取得する。
     */
    List<FollowEntity> findByFollowedTypeAndFollowedIdOrderByCreatedAtDesc(
            FollowerType followedType, Long followedId, Pageable pageable);

    /**
     * ユーザーがフォローしている対象を FollowedType・FollowedId で絞り込んで取得する。
     * Phase 2 getMyFeed: ユーザーがフォローしているチーム/組織の ID を取得するために使用する。
     *
     * @param followerType フォロワー種別 (USER)
     * @param followerId   ユーザーID
     * @param followedType フォロー対象種別 (TEAM / ORGANIZATION)
     * @return フォロー対象の ID リスト
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT f.followedId FROM FollowEntity f
            WHERE f.followerType = :followerType
              AND f.followerId = :followerId
              AND f.followedType = :followedType
            """)
    java.util.List<Long> findFollowedIdsByFollowerAndType(
            @org.springframework.data.repository.query.Param("followerType") FollowerType followerType,
            @org.springframework.data.repository.query.Param("followerId") Long followerId,
            @org.springframework.data.repository.query.Param("followedType") FollowerType followedType);

    /**
     * followerType・followerId・followedType で絞り込んだフォロー一覧を取得する。
     * チームフォロー一覧など followedType による絞り込みに使用する。
     */
    List<FollowEntity> findByFollowerTypeAndFollowerIdAndFollowedTypeOrderByCreatedAtDesc(
            FollowerType followerType, Long followerId, FollowerType followedType, Pageable pageable);

    /**
     * フォロー数を取得する。
     */
    long countByFollowerTypeAndFollowerId(FollowerType followerType, Long followerId);

    /**
     * フォロワー数を取得する。
     */
    long countByFollowedTypeAndFollowedId(FollowerType followedType, Long followedId);

    /**
     * 対称フォロー関係（A→B に対する B→A）を {@code SELECT ... FOR UPDATE NOWAIT} で取得する。
     *
     * <p>
     * F01.5 相互フォロー検知クリティカルセクションで使用する。同時並行で A→B と B→A が
     * 到達した場合に、対称側のレコードへ排他ロックを掛けて両スレッドが同時に {@code team_friends}
     * を INSERT しようとする二重成立レースを防止する。
     * </p>
     *
     * <p>
     * MySQL の {@code NOWAIT} オプションは JPA 層では
     * {@code jakarta.persistence.lock.timeout = -2} ヒントで表現する
     * （{@link TeamFriendRepository} と同方針）。他トランザクションが既にロックを
     * 保持していた場合は即座に {@code PessimisticLockException} /
     * {@code CannotAcquireLockException} がスローされ、Service 層は 202 Accepted で
     * 再試行を指示する。
     * </p>
     *
     * @param followerType フォロワー種別
     * @param followerId   フォロワー ID
     * @param followedType フォロー対象種別
     * @param followedId   フォロー対象 ID
     * @return 対称フォロー関係（存在しなければ空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")
    })
    @Query("""
            SELECT f FROM FollowEntity f
            WHERE f.followerType = :followerType
              AND f.followerId = :followerId
              AND f.followedType = :followedType
              AND f.followedId = :followedId
            """)
    Optional<FollowEntity> findByFollowerAndFollowedForUpdateNoWait(
            @Param("followerType") FollowerType followerType,
            @Param("followerId") Long followerId,
            @Param("followedType") FollowerType followedType,
            @Param("followedId") Long followedId);
}

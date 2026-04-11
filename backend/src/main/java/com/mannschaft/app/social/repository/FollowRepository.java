package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.entity.FollowEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * フォロー数を取得する。
     */
    long countByFollowerTypeAndFollowerId(FollowerType followerType, Long followerId);

    /**
     * フォロワー数を取得する。
     */
    long countByFollowedTypeAndFollowedId(FollowerType followedType, Long followedId);
}

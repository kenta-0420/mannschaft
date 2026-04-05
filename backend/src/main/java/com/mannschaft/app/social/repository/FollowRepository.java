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
     * フォロー数を取得する。
     */
    long countByFollowerTypeAndFollowerId(FollowerType followerType, Long followerId);

    /**
     * フォロワー数を取得する。
     */
    long countByFollowedTypeAndFollowedId(FollowerType followedType, Long followedId);
}

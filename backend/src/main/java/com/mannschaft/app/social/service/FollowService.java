package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.FollowListVisibility;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.SocialMapper;
import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * フォローサービス。フォロー・アンフォロー・フォロー一覧取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FollowService {

    private static final int DEFAULT_FOLLOW_SIZE = 20;

    private final FollowRepository followRepository;
    private final SocialMapper socialMapper;
    private final UserRepository userRepository;

    /**
     * フォローする。
     *
     * @param followedType フォロー対象種別
     * @param followedId   フォロー対象ID
     * @param userId       フォローするユーザーID
     * @return 作成されたフォロー
     */
    @Transactional
    public FollowResponse follow(String followedType, Long followedId, Long userId) {
        FollowerType targetType = FollowerType.valueOf(followedType);

        // 自分自身のフォロー防止
        if (targetType == FollowerType.USER && followedId.equals(userId)) {
            throw new BusinessException(SocialErrorCode.CANNOT_FOLLOW_SELF);
        }

        if (followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                FollowerType.USER, userId, targetType, followedId)) {
            throw new BusinessException(SocialErrorCode.FOLLOW_ALREADY_EXISTS);
        }

        FollowEntity follow = FollowEntity.builder()
                .followerType(FollowerType.USER)
                .followerId(userId)
                .followedType(targetType)
                .followedId(followedId)
                .build();
        follow = followRepository.save(follow);

        log.info("フォロー: followedType={}, followedId={}, userId={}", followedType, followedId, userId);
        return socialMapper.toFollowResponse(follow);
    }

    /**
     * アンフォローする。
     *
     * @param followedType フォロー対象種別
     * @param followedId   フォロー対象ID
     * @param userId       アンフォローするユーザーID
     */
    @Transactional
    public void unfollow(String followedType, Long followedId, Long userId) {
        FollowerType targetType = FollowerType.valueOf(followedType);

        FollowEntity follow = followRepository
                .findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                        FollowerType.USER, userId, targetType, followedId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FOLLOW_NOT_FOUND));

        followRepository.delete(follow);

        log.info("アンフォロー: followedType={}, followedId={}, userId={}", followedType, followedId, userId);
    }

    /**
     * フォロー一覧（自分がフォローしている対象）を取得する。
     *
     * @param userId ユーザーID
     * @param size   取得件数
     * @return フォロー一覧
     */
    public List<FollowResponse> getFollowing(Long userId, int size) {
        int followSize = size > 0 ? size : DEFAULT_FOLLOW_SIZE;
        return socialMapper.toFollowResponseList(
                followRepository.findByFollowerTypeAndFollowerIdOrderByCreatedAtDesc(
                        FollowerType.USER, userId, PageRequest.of(0, followSize)));
    }

    /**
     * フォロワー一覧（自分をフォローしている対象）を取得する。
     *
     * @param userId ユーザーID
     * @param size   取得件数
     * @return フォロワー一覧
     */
    public List<FollowResponse> getFollowers(Long userId, int size) {
        int followSize = size > 0 ? size : DEFAULT_FOLLOW_SIZE;
        return socialMapper.toFollowResponseList(
                followRepository.findByFollowedTypeAndFollowedIdOrderByCreatedAtDesc(
                        FollowerType.USER, userId, PageRequest.of(0, followSize)));
    }

    /**
     * フォロー状態を確認する。
     *
     * @param followedType フォロー対象種別
     * @param followedId   フォロー対象ID
     * @param userId       ユーザーID
     * @return フォロー中の場合 true
     */
    public boolean isFollowing(String followedType, Long followedId, Long userId) {
        return followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                FollowerType.USER, userId, FollowerType.valueOf(followedType), followedId);
    }

    // =========================================================
    // F04.4 / F01.7 Phase 2: フォロー可視化
    // =========================================================

    /**
     * フォロー一覧の閲覧権限を確認するヘルパー。
     * 本人は常に閲覧可。PUBLIC は誰でも閲覧可。
     * FRIENDS_ONLY は相互フォロー関係（双方向）の場合のみ可。
     * PRIVATE は本人のみ。
     *
     * @param targetUserId 閲覧対象ユーザーID
     * @param requesterId  閲覧者のユーザーID
     * @throws BusinessException FOLLOW_LIST_NOT_PUBLIC — 閲覧権限なし
     */
    private void checkFollowListAccess(Long targetUserId, Long requesterId) {
        if (targetUserId.equals(requesterId)) {
            return; // 本人は常に閲覧可
        }
        UserEntity target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FOLLOW_USER_NOT_FOUND));
        FollowListVisibility visibility = target.getFollowListVisibility() != null
                ? target.getFollowListVisibility() : FollowListVisibility.PUBLIC;

        if (visibility == FollowListVisibility.PUBLIC) {
            return;
        }
        if (visibility == FollowListVisibility.FRIENDS_ONLY) {
            // 相互フォロー（A→B かつ B→A）を確認
            boolean mutual = followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                            FollowerType.USER, requesterId, FollowerType.USER, targetUserId)
                    && followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                            FollowerType.USER, targetUserId, FollowerType.USER, requesterId);
            if (mutual) {
                return;
            }
        }
        // PRIVATE または FRIENDS_ONLY で相互フォローでない場合
        throw new BusinessException(SocialErrorCode.FOLLOW_LIST_NOT_PUBLIC);
    }

    /**
     * 他ユーザーのフォロー中一覧を取得する（公開設定チェック付き）。
     *
     * @param targetUserId 対象ユーザーID
     * @param requesterId  閲覧者のユーザーID
     * @param size         取得件数
     * @return フォロー中一覧
     */
    public List<FollowResponse> getUserFollowing(Long targetUserId, Long requesterId, int size) {
        checkFollowListAccess(targetUserId, requesterId);
        int followSize = size > 0 ? size : DEFAULT_FOLLOW_SIZE;
        return socialMapper.toFollowResponseList(
                followRepository.findByFollowerTypeAndFollowerIdOrderByCreatedAtDesc(
                        FollowerType.USER, targetUserId, PageRequest.of(0, followSize)));
    }

    /**
     * 他ユーザーのフォロワー一覧を取得する（公開設定チェック付き）。
     *
     * @param targetUserId 対象ユーザーID
     * @param requesterId  閲覧者のユーザーID
     * @param size         取得件数
     * @return フォロワー一覧
     */
    public List<FollowResponse> getUserFollowers(Long targetUserId, Long requesterId, int size) {
        checkFollowListAccess(targetUserId, requesterId);
        int followSize = size > 0 ? size : DEFAULT_FOLLOW_SIZE;
        return socialMapper.toFollowResponseList(
                followRepository.findByFollowedTypeAndFollowedIdOrderByCreatedAtDesc(
                        FollowerType.USER, targetUserId, PageRequest.of(0, followSize)));
    }

    /**
     * ユーザーがフォローしているチーム一覧を取得する。
     *
     * @param userId ユーザーID
     * @param size   取得件数
     * @return フォロー中のチーム一覧
     */
    public List<FollowResponse> getFollowedTeams(Long userId, int size) {
        int followSize = size > 0 ? size : DEFAULT_FOLLOW_SIZE;
        return socialMapper.toFollowResponseList(
                followRepository.findByFollowerTypeAndFollowerIdAndFollowedTypeOrderByCreatedAtDesc(
                        FollowerType.USER, userId, FollowerType.TEAM, PageRequest.of(0, followSize)));
    }

    /**
     * チームのフォロワー一覧を取得する。
     *
     * @param teamId チームID
     * @param size   取得件数
     * @return チームのフォロワー一覧
     */
    public List<FollowResponse> getTeamFollowers(Long teamId, int size) {
        int followSize = size > 0 ? size : DEFAULT_FOLLOW_SIZE;
        return socialMapper.toFollowResponseList(
                followRepository.findByFollowedTypeAndFollowedIdOrderByCreatedAtDesc(
                        FollowerType.TEAM, teamId, PageRequest.of(0, followSize)));
    }

    /**
     * フォロー一覧の公開設定を取得する。
     *
     * @param userId ユーザーID
     * @return 公開設定
     */
    public FollowListVisibility getFollowListVisibility(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FOLLOW_USER_NOT_FOUND));
        return user.getFollowListVisibility() != null
                ? user.getFollowListVisibility() : FollowListVisibility.PUBLIC;
    }

    /**
     * フォロー一覧の公開設定を更新する。
     *
     * @param userId     ユーザーID
     * @param visibility 新しい公開設定
     */
    @Transactional
    public void updateFollowListVisibility(Long userId, FollowListVisibility visibility) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FOLLOW_USER_NOT_FOUND));
        user.updateFollowListVisibility(visibility);
        userRepository.save(user);
    }
}

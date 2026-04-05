package com.mannschaft.app.social.service;

import com.mannschaft.app.common.BusinessException;
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
}

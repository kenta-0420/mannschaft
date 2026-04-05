package com.mannschaft.app.social.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.CreateProfileRequest;
import com.mannschaft.app.social.dto.ProfileResponse;
import com.mannschaft.app.social.dto.UpdateProfileRequest;
import com.mannschaft.app.social.entity.UserSocialProfileEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.UserSocialProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ソーシャルプロフィールサービス。プロフィールのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SocialProfileService {

    private final UserSocialProfileRepository profileRepository;
    private final FollowRepository followRepository;

    /**
     * ソーシャルプロフィールを作成する。
     *
     * @param req    作成リクエスト
     * @param userId ユーザーID
     * @return 作成されたプロフィール
     */
    @Transactional
    public ProfileResponse createProfile(CreateProfileRequest req, Long userId) {
        if (profileRepository.existsByUserId(userId)) {
            throw new BusinessException(SocialErrorCode.PROFILE_ALREADY_EXISTS);
        }
        if (profileRepository.existsByHandle(req.getHandle())) {
            throw new BusinessException(SocialErrorCode.HANDLE_ALREADY_TAKEN);
        }

        UserSocialProfileEntity profile = UserSocialProfileEntity.builder()
                .userId(userId)
                .handle(req.getHandle())
                .displayName(req.getDisplayName())
                .avatarUrl(req.getAvatarUrl())
                .bio(req.getBio())
                .build();
        profile = profileRepository.save(profile);

        log.info("ソーシャルプロフィール作成: id={}, handle={}, userId={}", profile.getId(), req.getHandle(), userId);
        return toProfileResponse(profile);
    }

    /**
     * ソーシャルプロフィールを更新する。
     *
     * @param req    更新リクエスト
     * @param userId ユーザーID
     * @return 更新されたプロフィール
     */
    @Transactional
    public ProfileResponse updateProfile(UpdateProfileRequest req, Long userId) {
        UserSocialProfileEntity profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.PROFILE_NOT_FOUND));

        if (req.getHandle() != null && !req.getHandle().equals(profile.getHandle())) {
            if (profileRepository.existsByHandle(req.getHandle())) {
                throw new BusinessException(SocialErrorCode.HANDLE_ALREADY_TAKEN);
            }
            profile.changeHandle(req.getHandle());
        }

        profile.updateProfile(req.getDisplayName(), req.getBio(), req.getAvatarUrl());
        profile = profileRepository.save(profile);

        log.info("ソーシャルプロフィール更新: id={}, userId={}", profile.getId(), userId);
        return toProfileResponse(profile);
    }

    /**
     * 自分のソーシャルプロフィールを取得する。
     *
     * @param userId ユーザーID
     * @return プロフィール
     */
    public ProfileResponse getMyProfile(Long userId) {
        UserSocialProfileEntity profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.PROFILE_NOT_FOUND));
        return toProfileResponse(profile);
    }

    /**
     * ハンドルでソーシャルプロフィールを取得する。
     *
     * @param handle ハンドル
     * @return プロフィール
     */
    public ProfileResponse getProfileByHandle(String handle) {
        UserSocialProfileEntity profile = profileRepository.findByHandle(handle)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.PROFILE_NOT_FOUND));
        if (!profile.getIsActive()) {
            throw new BusinessException(SocialErrorCode.PROFILE_INACTIVE);
        }
        return toProfileResponse(profile);
    }

    /**
     * ユーザーIDでソーシャルプロフィールを取得する。
     *
     * @param userId ユーザーID
     * @return プロフィール
     */
    public ProfileResponse getProfileByUserId(Long userId) {
        UserSocialProfileEntity profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.PROFILE_NOT_FOUND));
        return toProfileResponse(profile);
    }

    /**
     * ソーシャルプロフィールを無効化する。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void deactivateProfile(Long userId) {
        UserSocialProfileEntity profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.PROFILE_NOT_FOUND));
        profile.deactivate();
        profileRepository.save(profile);

        log.info("ソーシャルプロフィール無効化: userId={}", userId);
    }

    // --- プライベートメソッド ---

    /**
     * エンティティをレスポンスDTOに変換する。フォロー数を集計する。
     */
    private ProfileResponse toProfileResponse(UserSocialProfileEntity entity) {
        long followingCount = followRepository.countByFollowerTypeAndFollowerId(
                FollowerType.USER, entity.getUserId());
        long followerCount = followRepository.countByFollowedTypeAndFollowedId(
                FollowerType.USER, entity.getUserId());

        return new ProfileResponse(
                entity.getId(),
                entity.getUserId(),
                entity.getHandle(),
                entity.getDisplayName(),
                entity.getAvatarUrl(),
                entity.getBio(),
                entity.getIsActive(),
                followingCount,
                followerCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}

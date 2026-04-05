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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SocialProfileService} の単体テスト。
 * プロフィールCRUD・無効化を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SocialProfileService 単体テスト")
class SocialProfileServiceTest {

    @Mock
    private UserSocialProfileRepository profileRepository;

    @Mock
    private FollowRepository followRepository;

    @InjectMocks
    private SocialProfileService socialProfileService;

    private static final Long USER_ID = 100L;

    private UserSocialProfileEntity createProfile() {
        return UserSocialProfileEntity.builder()
                .userId(USER_ID)
                .handle("testuser")
                .displayName("テストユーザー")
                .bio("自己紹介")
                .avatarUrl("https://example.com/avatar.jpg")
                .build();
    }

    // ========================================
    // createProfile
    // ========================================
    @Nested
    @DisplayName("createProfile")
    class CreateProfile {

        @Test
        @DisplayName("正常系: プロフィールを作成できる")
        void プロフィールを作成できる() {
            // given
            CreateProfileRequest req = new CreateProfileRequest("testuser", "テスト", null, "bio");
            UserSocialProfileEntity saved = createProfile();

            given(profileRepository.existsByUserId(USER_ID)).willReturn(false);
            given(profileRepository.existsByHandle("testuser")).willReturn(false);
            given(profileRepository.save(any(UserSocialProfileEntity.class))).willReturn(saved);
            given(followRepository.countByFollowerTypeAndFollowerId(FollowerType.USER, USER_ID)).willReturn(0L);
            given(followRepository.countByFollowedTypeAndFollowedId(FollowerType.USER, USER_ID)).willReturn(0L);

            // when
            ProfileResponse result = socialProfileService.createProfile(req, USER_ID);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getHandle()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("異常系: プロフィールが既に存在する場合はエラー")
        void プロフィールが既に存在する場合はエラー() {
            // given
            CreateProfileRequest req = new CreateProfileRequest("testuser", "テスト", null, null);
            given(profileRepository.existsByUserId(USER_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> socialProfileService.createProfile(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.PROFILE_ALREADY_EXISTS));
        }

        @Test
        @DisplayName("異常系: ハンドルが既に使用されている場合はエラー")
        void ハンドルが既に使用されている場合はエラー() {
            // given
            CreateProfileRequest req = new CreateProfileRequest("taken", "テスト", null, null);
            given(profileRepository.existsByUserId(USER_ID)).willReturn(false);
            given(profileRepository.existsByHandle("taken")).willReturn(true);

            // when & then
            assertThatThrownBy(() -> socialProfileService.createProfile(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.HANDLE_ALREADY_TAKEN));
        }
    }

    // ========================================
    // updateProfile
    // ========================================
    @Nested
    @DisplayName("updateProfile")
    class UpdateProfile {

        @Test
        @DisplayName("正常系: プロフィールを更新できる")
        void プロフィールを更新できる() {
            // given
            UserSocialProfileEntity profile = createProfile();
            UpdateProfileRequest req = new UpdateProfileRequest(null, "更新名", null, "更新bio");

            given(profileRepository.findByUserId(USER_ID)).willReturn(Optional.of(profile));
            given(profileRepository.save(any(UserSocialProfileEntity.class))).willReturn(profile);
            given(followRepository.countByFollowerTypeAndFollowerId(FollowerType.USER, USER_ID)).willReturn(5L);
            given(followRepository.countByFollowedTypeAndFollowedId(FollowerType.USER, USER_ID)).willReturn(3L);

            // when
            ProfileResponse result = socialProfileService.updateProfile(req, USER_ID);

            // then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: ハンドルを変更できる")
        void ハンドルを変更できる() {
            // given
            UserSocialProfileEntity profile = createProfile();
            UpdateProfileRequest req = new UpdateProfileRequest("newhandle", null, null, null);

            given(profileRepository.findByUserId(USER_ID)).willReturn(Optional.of(profile));
            given(profileRepository.existsByHandle("newhandle")).willReturn(false);
            given(profileRepository.save(any(UserSocialProfileEntity.class))).willReturn(profile);
            given(followRepository.countByFollowerTypeAndFollowerId(FollowerType.USER, USER_ID)).willReturn(0L);
            given(followRepository.countByFollowedTypeAndFollowedId(FollowerType.USER, USER_ID)).willReturn(0L);

            // when
            ProfileResponse result = socialProfileService.updateProfile(req, USER_ID);

            // then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: ハンドル変更時に重複があるとエラー")
        void ハンドル変更時に重複があるとエラー() {
            // given
            UserSocialProfileEntity profile = createProfile();
            UpdateProfileRequest req = new UpdateProfileRequest("taken", null, null, null);

            given(profileRepository.findByUserId(USER_ID)).willReturn(Optional.of(profile));
            given(profileRepository.existsByHandle("taken")).willReturn(true);

            // when & then
            assertThatThrownBy(() -> socialProfileService.updateProfile(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.HANDLE_ALREADY_TAKEN));
        }

        @Test
        @DisplayName("異常系: プロフィールが見つからない場合はエラー")
        void プロフィールが見つからない場合はエラー() {
            // given
            UpdateProfileRequest req = new UpdateProfileRequest(null, "名前", null, null);
            given(profileRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> socialProfileService.updateProfile(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.PROFILE_NOT_FOUND));
        }
    }

    // ========================================
    // getProfileByHandle
    // ========================================
    @Nested
    @DisplayName("getProfileByHandle")
    class GetProfileByHandle {

        @Test
        @DisplayName("正常系: ハンドルでプロフィールを取得できる")
        void ハンドルでプロフィールを取得できる() {
            // given
            UserSocialProfileEntity profile = createProfile();

            given(profileRepository.findByHandle("testuser")).willReturn(Optional.of(profile));
            given(followRepository.countByFollowerTypeAndFollowerId(FollowerType.USER, USER_ID)).willReturn(0L);
            given(followRepository.countByFollowedTypeAndFollowedId(FollowerType.USER, USER_ID)).willReturn(0L);

            // when
            ProfileResponse result = socialProfileService.getProfileByHandle("testuser");

            // then
            assertThat(result).isNotNull();
            assertThat(result.getHandle()).isEqualTo("testuser");
        }

        @Test
        @DisplayName("異常系: 無効なプロフィールの場合はエラー")
        void 無効なプロフィールの場合はエラー() {
            // given
            UserSocialProfileEntity profile = createProfile();
            profile.deactivate();

            given(profileRepository.findByHandle("testuser")).willReturn(Optional.of(profile));

            // when & then
            assertThatThrownBy(() -> socialProfileService.getProfileByHandle("testuser"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.PROFILE_INACTIVE));
        }
    }

    // ========================================
    // deactivateProfile
    // ========================================
    @Nested
    @DisplayName("deactivateProfile")
    class DeactivateProfile {

        @Test
        @DisplayName("正常系: プロフィールを無効化できる")
        void プロフィールを無効化できる() {
            // given
            UserSocialProfileEntity profile = createProfile();

            given(profileRepository.findByUserId(USER_ID)).willReturn(Optional.of(profile));
            given(profileRepository.save(any(UserSocialProfileEntity.class))).willReturn(profile);

            // when
            socialProfileService.deactivateProfile(USER_ID);

            // then
            verify(profileRepository).save(any(UserSocialProfileEntity.class));
        }
    }
}

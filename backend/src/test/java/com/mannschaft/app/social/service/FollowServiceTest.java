package com.mannschaft.app.social.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.SocialMapper;
import com.mannschaft.app.social.dto.FollowResponse;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link FollowService} の単体テスト。
 * フォロー・アンフォロー・一覧取得・状態確認を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FollowService 単体テスト")
class FollowServiceTest {

    @Mock
    private FollowRepository followRepository;

    @Mock
    private SocialMapper socialMapper;

    @InjectMocks
    private FollowService followService;

    private static final Long USER_ID = 100L;
    private static final Long FOLLOWED_ID = 200L;

    // ========================================
    // follow
    // ========================================
    @Nested
    @DisplayName("follow")
    class Follow {

        @Test
        @DisplayName("正常系: ユーザーをフォローできる")
        void ユーザーをフォローできる() {
            // given
            FollowEntity saved = FollowEntity.builder()
                    .followerType(FollowerType.USER).followerId(USER_ID)
                    .followedType(FollowerType.USER).followedId(FOLLOWED_ID).build();
            FollowResponse expected = new FollowResponse(1L, "USER", USER_ID, "USER", FOLLOWED_ID, LocalDateTime.now());

            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.USER, USER_ID, FollowerType.USER, FOLLOWED_ID)).willReturn(false);
            given(followRepository.save(any(FollowEntity.class))).willReturn(saved);
            given(socialMapper.toFollowResponse(any(FollowEntity.class))).willReturn(expected);

            // when
            FollowResponse result = followService.follow("USER", FOLLOWED_ID, USER_ID);

            // then
            assertThat(result).isEqualTo(expected);
            verify(followRepository).save(any(FollowEntity.class));
        }

        @Test
        @DisplayName("異常系: 自分自身をフォローするとエラー")
        void 自分自身をフォローするとエラー() {
            // when & then
            assertThatThrownBy(() -> followService.follow("USER", USER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.CANNOT_FOLLOW_SELF));
        }

        @Test
        @DisplayName("異常系: 既にフォロー済みの場合はエラー")
        void 既にフォロー済みの場合はエラー() {
            // given
            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.USER, USER_ID, FollowerType.USER, FOLLOWED_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> followService.follow("USER", FOLLOWED_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FOLLOW_ALREADY_EXISTS));
        }
    }

    // ========================================
    // unfollow
    // ========================================
    @Nested
    @DisplayName("unfollow")
    class Unfollow {

        @Test
        @DisplayName("正常系: アンフォローできる")
        void アンフォローできる() {
            // given
            FollowEntity follow = FollowEntity.builder()
                    .followerType(FollowerType.USER).followerId(USER_ID)
                    .followedType(FollowerType.USER).followedId(FOLLOWED_ID).build();

            given(followRepository.findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.USER, USER_ID, FollowerType.USER, FOLLOWED_ID))
                    .willReturn(Optional.of(follow));

            // when
            followService.unfollow("USER", FOLLOWED_ID, USER_ID);

            // then
            verify(followRepository).delete(follow);
        }

        @Test
        @DisplayName("異常系: フォローが見つからない場合はエラー")
        void フォローが見つからない場合はエラー() {
            // given
            given(followRepository.findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.USER, USER_ID, FollowerType.USER, FOLLOWED_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> followService.unfollow("USER", FOLLOWED_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FOLLOW_NOT_FOUND));
        }
    }

    // ========================================
    // getFollowing / getFollowers
    // ========================================
    @Nested
    @DisplayName("getFollowing")
    class GetFollowing {

        @Test
        @DisplayName("正常系: フォロー一覧を取得できる")
        void フォロー一覧を取得できる() {
            // given
            List<FollowEntity> follows = List.of(FollowEntity.builder()
                    .followerType(FollowerType.USER).followerId(USER_ID)
                    .followedType(FollowerType.USER).followedId(FOLLOWED_ID).build());
            List<FollowResponse> expected = List.of(
                    new FollowResponse(1L, "USER", USER_ID, "USER", FOLLOWED_ID, LocalDateTime.now()));

            given(followRepository.findByFollowerTypeAndFollowerIdOrderByCreatedAtDesc(
                    eq(FollowerType.USER), eq(USER_ID), any(PageRequest.class))).willReturn(follows);
            given(socialMapper.toFollowResponseList(follows)).willReturn(expected);

            // when
            List<FollowResponse> result = followService.getFollowing(USER_ID, 10);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // isFollowing
    // ========================================
    @Nested
    @DisplayName("isFollowing")
    class IsFollowing {

        @Test
        @DisplayName("正常系: フォロー状態を確認できる")
        void フォロー状態を確認できる() {
            // given
            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.USER, USER_ID, FollowerType.USER, FOLLOWED_ID)).willReturn(true);

            // when
            boolean result = followService.isFollowing("USER", FOLLOWED_ID, USER_ID);

            // then
            assertThat(result).isTrue();
        }
    }
}

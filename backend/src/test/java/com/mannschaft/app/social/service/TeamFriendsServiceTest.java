package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.social.dto.PastForwardHandling;
import com.mannschaft.app.social.dto.TeamFriendView;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.entity.FriendContentForwardEntity;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.FriendContentForwardRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link TeamFriendsService} の単体テスト。
 * チーム間フォロー・相互フォロー検知・フォロー解除・フレンド一覧取得・公開設定変更を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamFriendsService 単体テスト")
class TeamFriendsServiceTest {

    @Mock
    private FollowRepository followRepository;

    @Mock
    private TeamFriendRepository teamFriendRepository;

    @Mock
    private FriendContentForwardRepository friendContentForwardRepository;

    @Mock
    private TimelinePostRepository timelinePostRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TeamFriendsService teamFriendsService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;

    // ========================================
    // follow
    // ========================================
    @Nested
    @DisplayName("follow")
    class Follow {

        @Test
        @DisplayName("正常系: フォロー登録・片方向フォローレスポンスが正常に返る")
        void フォロー登録_片方向() {
            // given
            TeamEntity targetTeam = TeamEntity.builder().name("相手チーム").build();
            FollowEntity savedFollow = buildFollow(TEAM_ID, TARGET_TEAM_ID, 100L);

            given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(targetTeam));
            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID)).willReturn(false);
            given(followRepository.save(any(FollowEntity.class))).willReturn(savedFollow);
            given(followRepository.findByFollowerAndFollowedForUpdateNoWait(
                    FollowerType.TEAM, TARGET_TEAM_ID, FollowerType.TEAM, TEAM_ID))
                    .willReturn(Optional.empty());
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            FollowTeamResponse result = teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID);

            // then
            assertThat(result.getFollowerTeamId()).isEqualTo(TEAM_ID);
            assertThat(result.getFollowedTeamId()).isEqualTo(TARGET_TEAM_ID);
            assertThat(result.isMutual()).isFalse();
            assertThat(result.getTeamFriendId()).isNull();
            verify(followRepository).save(any(FollowEntity.class));
        }

        @Test
        @DisplayName("異常系: 自己フォロー → BusinessException(FRIEND_CANNOT_SELF_FOLLOW)")
        void 自己フォロー_例外() {
            // when & then
            assertThatThrownBy(() -> teamFriendsService.follow(TEAM_ID, TEAM_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW));
        }

        @Test
        @DisplayName("異常系: 権限不足（MANAGE_FRIEND_TEAMS なし）→ BusinessException")
        void 権限不足_例外() {
            // given
            doThrow(new BusinessException(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION))
                    .when(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");

            // when & then
            assertThatThrownBy(() -> teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_INSUFFICIENT_PERMISSION));
        }

        @Test
        @DisplayName("異常系: 既にフォロー済み → BusinessException(FRIEND_ALREADY_FOLLOWING)")
        void 既にフォロー済み_例外() {
            // given
            given(teamRepository.findById(TARGET_TEAM_ID))
                    .willReturn(Optional.of(TeamEntity.builder().name("相手チーム").build()));
            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_ALREADY_FOLLOWING));
        }

        @Test
        @DisplayName("正常系: 相互フォロー成立 → team_friends に INSERT される")
        void 相互フォロー成立_team_friendsが作成される() {
            // given
            TeamEntity targetTeam = TeamEntity.builder().name("相手チーム").build();
            FollowEntity savedFollow = buildFollow(TEAM_ID, TARGET_TEAM_ID, 100L);
            FollowEntity reverseFollow = buildFollow(TARGET_TEAM_ID, TEAM_ID, 200L);
            TeamFriendEntity savedFriend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);

            given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(targetTeam));
            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID)).willReturn(false);
            given(followRepository.save(any(FollowEntity.class))).willReturn(savedFollow);
            given(followRepository.findByFollowerAndFollowedForUpdateNoWait(
                    FollowerType.TEAM, TARGET_TEAM_ID, FollowerType.TEAM, TEAM_ID))
                    .willReturn(Optional.of(reverseFollow));
            given(teamFriendRepository.save(any(TeamFriendEntity.class))).willReturn(savedFriend);
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            FollowTeamResponse result = teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID);

            // then
            assertThat(result.isMutual()).isTrue();
            assertThat(result.getTeamFriendId()).isEqualTo(1L);
            verify(teamFriendRepository).save(any(TeamFriendEntity.class));
        }
    }

    // ========================================
    // unfollow
    // ========================================
    @Nested
    @DisplayName("unfollow")
    class Unfollow {

        @Test
        @DisplayName("正常系(KEEP): フォロー解除・team_friends 削除・転送はそのまま")
        void フォロー解除_KEEP() {
            // given
            FollowEntity follow = buildFollow(TEAM_ID, TARGET_TEAM_ID, 100L);
            TeamFriendEntity friend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);

            given(followRepository.findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID))
                    .willReturn(Optional.of(follow));
            given(teamFriendRepository.findByTeamAIdAndTeamBId(TEAM_ID, TARGET_TEAM_ID))
                    .willReturn(Optional.of(friend));
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            teamFriendsService.unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.KEEP, USER_ID);

            // then
            verify(teamFriendRepository).deleteByTeamAIdAndTeamBId(TEAM_ID, TARGET_TEAM_ID);
            verify(followRepository).delete(follow);
            // KEEP モードなので転送処理は呼ばれない
            verify(friendContentForwardRepository, never())
                    .findByForwardingTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(anyLong(), any());
        }

        @Test
        @DisplayName("正常系(SOFT_DELETE): 転送が論理削除される")
        void フォロー解除_SOFT_DELETE() {
            // given
            FollowEntity follow = buildFollow(TEAM_ID, TARGET_TEAM_ID, 100L);
            TeamFriendEntity friend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);
            FriendContentForwardEntity forward = buildForward(TEAM_ID, TARGET_TEAM_ID, 50L);
            TimelinePostEntity post = TimelinePostEntity.builder()
                    .scopeType(com.mannschaft.app.timeline.PostScopeType.FRIEND_FORWARD)
                    .scopeId(TEAM_ID)
                    .userId(USER_ID)
                    .postedAsType(com.mannschaft.app.timeline.PostedAsType.TEAM)
                    .postedAsId(TEAM_ID)
                    .content("テスト投稿")
                    .status(com.mannschaft.app.timeline.PostStatus.PUBLISHED)
                    .shareWithFriends(false)
                    .build();

            given(followRepository.findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID))
                    .willReturn(Optional.of(follow));
            given(teamFriendRepository.findByTeamAIdAndTeamBId(TEAM_ID, TARGET_TEAM_ID))
                    .willReturn(Optional.of(friend));
            given(friendContentForwardRepository
                    .findByForwardingTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(
                            eq(TEAM_ID), any(Pageable.class)))
                    .willReturn(List.of(forward));
            given(friendContentForwardRepository
                    .findByForwardingTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(
                            eq(TARGET_TEAM_ID), any(Pageable.class)))
                    .willReturn(List.of());
            given(timelinePostRepository.findById(forward.getForwardedPostId()))
                    .willReturn(Optional.of(post));
            given(timelinePostRepository.save(any())).willReturn(post);
            given(friendContentForwardRepository.save(any())).willReturn(forward);
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            teamFriendsService.unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.SOFT_DELETE, USER_ID);

            // then
            verify(timelinePostRepository, atLeastOnce()).save(any(TimelinePostEntity.class));
            verify(friendContentForwardRepository, atLeastOnce()).save(any(FriendContentForwardEntity.class));
            verify(followRepository).delete(follow);
        }

        @Test
        @DisplayName("正常系(ARCHIVE): 転送が ARCHIVE（HIDDEN）される")
        void フォロー解除_ARCHIVE() {
            // given
            FollowEntity follow = buildFollow(TEAM_ID, TARGET_TEAM_ID, 100L);
            TeamFriendEntity friend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);
            FriendContentForwardEntity forward = buildForward(TEAM_ID, TARGET_TEAM_ID, 50L);
            TimelinePostEntity post = TimelinePostEntity.builder()
                    .scopeType(com.mannschaft.app.timeline.PostScopeType.FRIEND_FORWARD)
                    .scopeId(TEAM_ID)
                    .userId(USER_ID)
                    .postedAsType(com.mannschaft.app.timeline.PostedAsType.TEAM)
                    .postedAsId(TEAM_ID)
                    .content("テスト投稿")
                    .status(com.mannschaft.app.timeline.PostStatus.PUBLISHED)
                    .shareWithFriends(false)
                    .build();

            given(followRepository.findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID))
                    .willReturn(Optional.of(follow));
            given(teamFriendRepository.findByTeamAIdAndTeamBId(TEAM_ID, TARGET_TEAM_ID))
                    .willReturn(Optional.of(friend));
            given(friendContentForwardRepository
                    .findByForwardingTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(
                            eq(TEAM_ID), any(Pageable.class)))
                    .willReturn(List.of(forward));
            given(friendContentForwardRepository
                    .findByForwardingTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(
                            eq(TARGET_TEAM_ID), any(Pageable.class)))
                    .willReturn(List.of());
            given(timelinePostRepository.findById(forward.getForwardedPostId()))
                    .willReturn(Optional.of(post));
            given(timelinePostRepository.save(any())).willReturn(post);
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            teamFriendsService.unfollow(TEAM_ID, TARGET_TEAM_ID, PastForwardHandling.ARCHIVE, USER_ID);

            // then
            verify(timelinePostRepository, atLeastOnce()).save(any(TimelinePostEntity.class));
            verify(followRepository).delete(follow);
        }
    }

    // ========================================
    // setVisibility
    // ========================================
    @Nested
    @DisplayName("setVisibility")
    class SetVisibility {

        @Test
        @DisplayName("正常系: isPublic が更新される")
        void 公開設定変更_正常() {
            // given
            TeamFriendEntity friend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);
            Long teamFriendId = 1L;

            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamFriendRepository.findById(teamFriendId)).willReturn(Optional.of(friend));
            given(teamFriendRepository.save(any(TeamFriendEntity.class))).willReturn(friend);
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            teamFriendsService.setVisibility(TEAM_ID, teamFriendId, true, USER_ID);

            // then
            verify(teamFriendRepository).save(any(TeamFriendEntity.class));
            assertThat(friend.getIsPublic()).isTrue();
        }

        @Test
        @DisplayName("異常系: ADMIN 権限なし → BusinessException(FRIEND_VISIBILITY_ADMIN_ONLY)")
        void 権限不足_公開設定変更_例外() {
            // given
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            // when & then
            assertThatThrownBy(() ->
                    teamFriendsService.setVisibility(TEAM_ID, 1L, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SocialErrorCode.FRIEND_VISIBILITY_ADMIN_ONLY));
        }
    }

    // ========================================
    // listFriends
    // ========================================
    @Nested
    @DisplayName("listFriends")
    class ListFriends {

        @Test
        @DisplayName("正常系: ADMIN(publicOnly=false) には全件返る")
        void ADMIN_には全件返る() {
            // given
            TeamFriendEntity publicFriend = buildTeamFriendWithPublic(TEAM_ID, TARGET_TEAM_ID, 1L, true);
            TeamFriendEntity privateFriend = buildTeamFriendWithPublic(TEAM_ID, 30L, 2L, false);
            TeamEntity friendTeamA = TeamEntity.builder().name("公開チーム").build();
            TeamEntity friendTeamB = TeamEntity.builder().name("非公開チーム").build();
            Pageable pageable = PageRequest.of(0, 20);

            given(teamFriendRepository.findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(
                    TEAM_ID, TEAM_ID, pageable))
                    .willReturn(List.of(publicFriend, privateFriend));
            given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(friendTeamA));
            given(teamRepository.findById(30L)).willReturn(Optional.of(friendTeamB));

            // when
            var result = teamFriendsService.listFriends(TEAM_ID, USER_ID, pageable, false);

            // then
            assertThat(result.getContent()).hasSize(2);
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: SUPPORTER(publicOnly=true) には isPublic=true のみ返る")
        void SUPPORTER_には公開のみ返る() {
            // given
            TeamFriendEntity publicFriend = buildTeamFriendWithPublic(TEAM_ID, TARGET_TEAM_ID, 1L, true);
            TeamFriendEntity privateFriend = buildTeamFriendWithPublic(TEAM_ID, 30L, 2L, false);
            TeamEntity friendTeam = TeamEntity.builder().name("公開チーム").build();
            Pageable pageable = PageRequest.of(0, 20);

            given(teamFriendRepository.findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(
                    TEAM_ID, TEAM_ID, pageable))
                    .willReturn(List.of(publicFriend, privateFriend));
            given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(friendTeam));

            // when
            var result = teamFriendsService.listFriends(TEAM_ID, USER_ID, pageable, true);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getFriendTeamId()).isEqualTo(TARGET_TEAM_ID);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private FollowEntity buildFollow(Long followerId, Long followedId, Long id) {
        FollowEntity entity = FollowEntity.builder()
                .followerType(FollowerType.TEAM)
                .followerId(followerId)
                .followedType(FollowerType.TEAM)
                .followedId(followedId)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private TeamFriendEntity buildTeamFriend(Long teamAId, Long teamBId, Long id) {
        long aId = Math.min(teamAId, teamBId);
        long bId = Math.max(teamAId, teamBId);
        TeamFriendEntity entity = TeamFriendEntity.builder()
                .teamAId(aId)
                .teamBId(bId)
                .aFollowId(100L)
                .bFollowId(200L)
                .establishedAt(LocalDateTime.now())
                .isPublic(false)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private TeamFriendEntity buildTeamFriendWithPublic(Long teamAId, Long teamBId, Long id, boolean isPublic) {
        long aId = Math.min(teamAId, teamBId);
        long bId = Math.max(teamAId, teamBId);
        TeamFriendEntity entity = TeamFriendEntity.builder()
                .teamAId(aId)
                .teamBId(bId)
                .aFollowId(100L)
                .bFollowId(200L)
                .establishedAt(LocalDateTime.now())
                .isPublic(isPublic)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private FriendContentForwardEntity buildForward(Long forwardingTeamId, Long sourceTeamId, Long id) {
        FriendContentForwardEntity entity = FriendContentForwardEntity.builder()
                .sourcePostId(300L)
                .sourceTeamId(sourceTeamId)
                .forwardingTeamId(forwardingTeamId)
                .forwardedPostId(400L)
                .target("MEMBER")
                .isRevoked(false)
                .forwardedBy(USER_ID)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}

package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.social.dto.PastForwardHandling;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * {@link TeamFriendsService} の単体テスト（リファクタリング第4弾 Phase 4-B 反映）。
 *
 * <p>
 * ファサードに残った follow / unfollow / 相互フォロー検知の振る舞いを検証する。
 * listFriends は {@link TeamFriendQueryServiceTest}、setVisibility は
 * {@link TeamFriendVisibilityServiceTest} を参照。
 * </p>
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

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private TeamFriendQueryService teamFriendQueryService;

    @Mock
    private TeamFriendVisibilityService teamFriendVisibilityService;

    @Mock
    private MessageSource mockMessageSource;

    @InjectMocks
    private TeamFriendsService teamFriendsService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;

    /**
     * Issue #2715 ロットC-3: フレンドチーム通知の locale 別本文組み立てを検証するため、
     * 実物の {@link MessageSource} で {@code messageSource} フィールドを上書きする
     * （モックで引数をそのまま返す形では鍵欠落・フォーマット崩れを検出できないため）。
     */
    @BeforeEach
    void setUpRealMessageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(teamFriendsService, "messageSource", ms);
    }

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
        @DisplayName("正常系: 相互フォロー成立 → team_friends に INSERT され FRIEND_ESTABLISHED 通知が両チームに送信される")
        void 相互フォロー成立_team_friendsが作成される() {
            // given
            TeamEntity selfTeam = TeamEntity.builder().name("自チーム").build();
            TeamEntity targetTeam = TeamEntity.builder().name("相手チーム").build();
            FollowEntity savedFollow = buildFollow(TEAM_ID, TARGET_TEAM_ID, 100L);
            FollowEntity reverseFollow = buildFollow(TARGET_TEAM_ID, TEAM_ID, 200L);
            TeamFriendEntity savedFriend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);

            given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(targetTeam));
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(selfTeam));
            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID)).willReturn(false);
            given(followRepository.save(any(FollowEntity.class))).willReturn(savedFollow);
            given(followRepository.findByFollowerAndFollowedForUpdateNoWait(
                    FollowerType.TEAM, TARGET_TEAM_ID, FollowerType.TEAM, TEAM_ID))
                    .willReturn(Optional.of(reverseFollow));
            given(teamFriendRepository.save(any(TeamFriendEntity.class))).willReturn(savedFriend);
            given(userRoleRepository.findUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                    .willReturn(List.of(USER_ID));
            given(userRoleRepository.findUserIdsByTeamIdAndRoleName(TARGET_TEAM_ID, "ADMIN"))
                    .willReturn(List.of(2L));
            doNothing().when(auditLogService).record(anyString(), anyLong(), isNull(),
                    anyLong(), isNull(), isNull(), isNull(), isNull(), anyString());

            // when
            FollowTeamResponse result = teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID);

            // then
            assertThat(result.isMutual()).isTrue();
            assertThat(result.getTeamFriendId()).isEqualTo(1L);
            verify(teamFriendRepository).save(any(TeamFriendEntity.class));
            // 両チームの ADMIN へ FRIEND_ESTABLISHED 通知が 2 回送信される（自チーム + 相手チーム）
            ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> builderCaptor =
                    ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
            verify(notificationHelper, times(2)).notifyAllLocalized(
                    anyList(),
                    eq("FRIEND_ESTABLISHED"),
                    eq("TEAM_FRIEND"),
                    eq(1L),
                    eq(NotificationScopeType.FRIEND_TEAM),
                    anyLong(),
                    anyString(),
                    eq(USER_ID),
                    builderCaptor.capture()
            );
            // 受信者 locale=en のとき件名・本文が英語で組み立てられ、プレースホルダが残らないことを検証する
            NotificationHelper.LocalizedMessage enMessage =
                    builderCaptor.getAllValues().get(0).build(2L, Locale.ENGLISH);
            assertThat(enMessage.title()).doesNotContain("{0}").contains("相手チーム").isEqualTo(
                    "You are now friend teams with 相手チーム");
            assertThat(enMessage.body()).doesNotContain("{0}");
        }
    }

    // ========================================
    // unfollow
    // ========================================
    @Nested
    @DisplayName("unfollow")
    class Unfollow {

        @Test
        @DisplayName("正常系(KEEP): フォロー解除・team_friends 削除・転送はそのまま・FRIEND_DISSOLVED 通知が両チームに送信される")
        void フォロー解除_KEEP() {
            // given
            FollowEntity follow = buildFollow(TEAM_ID, TARGET_TEAM_ID, 100L);
            TeamFriendEntity friend = buildTeamFriend(TEAM_ID, TARGET_TEAM_ID, 1L);
            TeamEntity selfTeam = TeamEntity.builder().name("自チーム").build();
            TeamEntity targetTeam = TeamEntity.builder().name("相手チーム").build();

            given(followRepository.findByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID))
                    .willReturn(Optional.of(follow));
            given(teamFriendRepository.findByTeamAIdAndTeamBId(TEAM_ID, TARGET_TEAM_ID))
                    .willReturn(Optional.of(friend));
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(selfTeam));
            given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(targetTeam));
            given(userRoleRepository.findUserIdsByTeamIdAndRoleName(TEAM_ID, "ADMIN"))
                    .willReturn(List.of(USER_ID));
            given(userRoleRepository.findUserIdsByTeamIdAndRoleName(TARGET_TEAM_ID, "ADMIN"))
                    .willReturn(List.of(2L));
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
            // 両チームの ADMIN へ FRIEND_DISSOLVED 通知が 2 回送信される（自チーム + 相手チーム）
            ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> builderCaptor =
                    ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
            verify(notificationHelper, times(2)).notifyAllLocalized(
                    anyList(),
                    eq("FRIEND_DISSOLVED"),
                    eq("TEAM_FRIEND"),
                    eq(friend.getId()),
                    eq(NotificationScopeType.FRIEND_TEAM),
                    anyLong(),
                    anyString(),
                    eq(USER_ID),
                    builderCaptor.capture()
            );
            NotificationHelper.LocalizedMessage enMessage =
                    builderCaptor.getAllValues().get(0).build(2L, Locale.ENGLISH);
            assertThat(enMessage.title()).doesNotContain("{0}").isEqualTo(
                    "The friend team relationship with 相手チーム has been dissolved");
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
    // 委譲メソッド（QueryService / VisibilityService）
    // ========================================
    @Nested
    @DisplayName("委譲メソッド")
    class Delegation {

        @Test
        @DisplayName("listFriendsResponse は TeamFriendQueryService に委譲される")
        void listFriendsResponse_委譲() {
            // given
            Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);

            // when
            teamFriendsService.listFriendsResponse(TEAM_ID, USER_ID, pageable, false);

            // then
            verify(teamFriendQueryService).listFriendsResponse(TEAM_ID, USER_ID, pageable, false);
        }

        @Test
        @DisplayName("listFriends は TeamFriendQueryService に委譲される")
        void listFriends_委譲() {
            // given
            Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);

            // when
            teamFriendsService.listFriends(TEAM_ID, USER_ID, pageable, true);

            // then
            verify(teamFriendQueryService).listFriends(TEAM_ID, USER_ID, pageable, true);
        }

        @Test
        @DisplayName("setVisibility は TeamFriendVisibilityService に委譲される")
        void setVisibility_委譲() {
            // when
            teamFriendsService.setVisibility(TEAM_ID, 1L, true, USER_ID);

            // then
            verify(teamFriendVisibilityService).setVisibility(TEAM_ID, 1L, true, USER_ID);
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

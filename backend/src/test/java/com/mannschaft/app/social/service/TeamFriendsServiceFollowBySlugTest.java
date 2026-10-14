package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.social.entity.FollowEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.FriendContentForwardRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261012-0315 フレンドチームのフォロー先を slug で指定する — Service 層の試練（red）。
 *
 * <h2>実装側が満たすべき契約</h2>
 * <ul>
 *   <li>新入口 {@code TeamFriendsService#followBySlug(Long teamId, String targetTeamSlug, Long userId)}
 *       を設ける（戻り値 {@link FollowTeamResponse}）。入口メソッド自身に
 *       {@code @Transactional(isolation = REPEATABLE_READ)} と
 *       {@code @CacheEvict(value = "teamFriendList", allEntries = true)} を付ける
 *       （{@code this.follow(...)} の自己呼び出しではプロキシを通らないため。
 *       {@code TeamFriendListCacheEvictionCoverageTest#followBySlug_が失効させる} が番人）。</li>
 *   <li>slug 経路の検証順: ① 権限（{@code accessControlService.checkPermission(userId, teamId, "TEAM",
 *       "MANAGE_FRIEND_TEAMS")} → 403 COMMON_002）を <b>slug 解決より先</b>に行う（権限の無い者に
 *       slug の実在を 404/400 の差で教えない）② slug 解決 ③ 自チーム → 400 SOCIAL_101
 *       ④ フォロー先の可否 → 404 SOCIAL_104 ⑤ 既フォロー → 409 SOCIAL_102。</li>
 *   <li>slug 解決は {@code TeamRepository#findBySlugAndDeletedAtIsNullAndLifecycleStatus(slug, ACTIVE)}
 *       か {@code findBySlugAndDeletedAtIsNull(slug)} のどちらを使ってもよい（本テストは両方を
 *       lenient にスタブする）。ただし<b>アーカイブ済み（{@code archivedAt != null}）・ACTIVE 以外
 *       （PROVISIONED）・論理削除済み</b>は 404 SOCIAL_104 にすること。アーカイブは ACTIVE のまま
 *       {@code archivedAt} を立てるだけなので、ACTIVE 限定クエリだけでは弾けない点に注意。</li>
 *   <li>数値経路 {@code follow(Long, Long, Long)} も同じく、{@code findById} で取れた相手が
 *       アーカイブ済み・PROVISIONED なら 404 SOCIAL_104 にする（AC-7）。</li>
 *   <li>不正 slug の書式検査（空文字・空白・長さ・文字種）は DTO 側（400 COMMON_001）の責務で、
 *       Service は呼ばれない前提（{@code TeamFriendsControllerFollowSlugTest} が番人）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261012-0315 TeamFriendsService slug 指定フォロー（試練）")
class TeamFriendsServiceFollowBySlugTest {

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
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private TeamFriendQueryService teamFriendQueryService;
    @Mock
    private TeamFriendVisibilityService teamFriendVisibilityService;

    @InjectMocks
    private TeamFriendsService teamFriendsService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final String TEAM_SLUG = "my-team";
    private static final Long TARGET_TEAM_ID = 20L;
    private static final String TARGET_SLUG = "rival-fc";

    // ========================================
    // AC-1 / AC-3 / AC-2
    // ========================================
    @Nested
    @DisplayName("slug 経路の基本")
    class Basic {

        @Test
        @DisplayName("AC-1: slug 指定で他チームへのフォローを作成できる（解決した数値 ID で follows を作る）")
        void ac1_slug指定でフォローを作成できる() {
            TeamEntity target = team(TARGET_TEAM_ID, TARGET_SLUG);
            stubSlug(TARGET_SLUG, target);
            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID)).willReturn(false);
            given(followRepository.save(any(FollowEntity.class))).willReturn(follow(TEAM_ID, TARGET_TEAM_ID, 100L));
            given(followRepository.findByFollowerAndFollowedForUpdateNoWait(
                    FollowerType.TEAM, TARGET_TEAM_ID, FollowerType.TEAM, TEAM_ID)).willReturn(Optional.empty());

            FollowTeamResponse result = teamFriendsService.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID);

            assertThat(result.getFollowerTeamId()).isEqualTo(TEAM_ID);
            assertThat(result.getFollowedTeamId()).isEqualTo(TARGET_TEAM_ID);
            assertThat(result.isMutual()).isFalse();
            ArgumentCaptor<FollowEntity> captor = ArgumentCaptor.forClass(FollowEntity.class);
            verify(followRepository).save(captor.capture());
            assertThat(captor.getValue().getFollowerId()).isEqualTo(TEAM_ID);
            assertThat(captor.getValue().getFollowedId())
                    .as("slug から解決した相手チームの内部 ID で follows を作ること")
                    .isEqualTo(TARGET_TEAM_ID);
            verify(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
        }

        @Test
        @DisplayName("AC-2: 不在の slug は 404 SOCIAL_104 で、フォロー行を作らない")
        void ac2_不在のslugは404() {
            stubSlugAbsent("no-such-team");

            assertThatThrownBy(() -> teamFriendsService.followBySlug(TEAM_ID, "no-such-team", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND);
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-3: 自チームの slug は 400 SOCIAL_101 で、フォロー行を作らない")
        void ac3_自チームのslugは400() {
            stubSlug(TEAM_SLUG, team(TEAM_ID, TEAM_SLUG));

            assertThatThrownBy(() -> teamFriendsService.followBySlug(TEAM_ID, TEAM_SLUG, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW);
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("既フォローの slug は 409 SOCIAL_102（数値経路と同じ判定）")
        void 既フォローのslugは409() {
            stubSlug(TARGET_SLUG, team(TARGET_TEAM_ID, TARGET_SLUG));
            given(followRepository.existsByFollowerTypeAndFollowerIdAndFollowedTypeAndFollowedId(
                    FollowerType.TEAM, TEAM_ID, FollowerType.TEAM, TARGET_TEAM_ID)).willReturn(true);

            assertThatThrownBy(() -> teamFriendsService.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SocialErrorCode.FRIEND_ALREADY_FOLLOWING);
            verify(followRepository, never()).save(any());
        }
    }

    // ========================================
    // AC-6
    // ========================================
    @Nested
    @DisplayName("AC-6: 権限不足は slug 経路でも 403 COMMON_002")
    class Permission {

        @Test
        @DisplayName("AC-6: MANAGE_FRIEND_TEAMS を持たない者の slug 指定は 403 COMMON_002 で、フォロー行を作らない")
        void ac6_権限なしは403() {
            lenient().when(teamRepository.findBySlugAndDeletedAtIsNullAndLifecycleStatus(
                    TARGET_SLUG, TeamEntity.LifecycleStatus.ACTIVE)).thenReturn(Optional.of(team(TARGET_TEAM_ID, TARGET_SLUG)));
            lenient().when(teamRepository.findBySlugAndDeletedAtIsNull(TARGET_SLUG))
                    .thenReturn(Optional.of(team(TARGET_TEAM_ID, TARGET_SLUG)));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");

            assertThatThrownBy(() -> teamFriendsService.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-6: 権限の無い者には不在 slug でも 404 ではなく 403 COMMON_002（slug の実在を漏らさない）")
        void ac6_権限なしは不在slugでも403() {
            stubSlugAbsent("no-such-team");
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");

            assertThatThrownBy(() -> teamFriendsService.followBySlug(TEAM_ID, "no-such-team", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(followRepository, never()).save(any());
        }
    }

    // ========================================
    // AC-7
    // ========================================
    @Nested
    @DisplayName("AC-7: アーカイブ済み・非ACTIVE・論理削除済みのフォロー先は 404 SOCIAL_104")
    class UnavailableTarget {

        @Test
        @DisplayName("AC-7(slug): アーカイブ済みチーム（ACTIVE のまま archivedAt あり）は 404 SOCIAL_104")
        void ac7_slug_アーカイブ済みは404() {
            TeamEntity archived = team(TARGET_TEAM_ID, TARGET_SLUG);
            ReflectionTestUtils.setField(archived, "archivedAt", LocalDateTime.of(2026, 10, 1, 0, 0));
            stubSlug(TARGET_SLUG, archived);

            assertNotFound(() -> teamFriendsService.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID));
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-7(slug): PROVISIONED（非ACTIVE）チームは 404 SOCIAL_104")
        void ac7_slug_非ACTIVEは404() {
            TeamEntity provisioned = team(TARGET_TEAM_ID, TARGET_SLUG);
            ReflectionTestUtils.setField(provisioned, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED);
            // ACTIVE 限定クエリでは見えず、非限定クエリでは見える（実 DB と同じ振る舞い）
            lenient().when(teamRepository.findBySlugAndDeletedAtIsNullAndLifecycleStatus(
                    TARGET_SLUG, TeamEntity.LifecycleStatus.ACTIVE)).thenReturn(Optional.empty());
            lenient().when(teamRepository.findBySlugAndDeletedAtIsNull(TARGET_SLUG)).thenReturn(Optional.of(provisioned));

            assertNotFound(() -> teamFriendsService.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID));
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-7(slug): 論理削除済みチームは 404 SOCIAL_104")
        void ac7_slug_論理削除済みは404() {
            stubSlugAbsent(TARGET_SLUG);

            assertNotFound(() -> teamFriendsService.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID));
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-7(数値): アーカイブ済みチームの数値 ID 指定は 404 SOCIAL_104")
        void ac7_数値_アーカイブ済みは404() {
            TeamEntity archived = team(TARGET_TEAM_ID, TARGET_SLUG);
            ReflectionTestUtils.setField(archived, "archivedAt", LocalDateTime.of(2026, 10, 1, 0, 0));
            lenient().when(teamRepository.findById(TARGET_TEAM_ID)).thenReturn(Optional.of(archived));
            lenient().when(followRepository.save(any(FollowEntity.class)))
                    .thenReturn(follow(TEAM_ID, TARGET_TEAM_ID, 100L));
            lenient().when(followRepository.findByFollowerAndFollowedForUpdateNoWait(
                    FollowerType.TEAM, TARGET_TEAM_ID, FollowerType.TEAM, TEAM_ID)).thenReturn(Optional.empty());

            assertNotFound(() -> teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID));
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-7(数値): PROVISIONED（非ACTIVE）チームの数値 ID 指定は 404 SOCIAL_104")
        void ac7_数値_非ACTIVEは404() {
            TeamEntity provisioned = team(TARGET_TEAM_ID, TARGET_SLUG);
            ReflectionTestUtils.setField(provisioned, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED);
            lenient().when(teamRepository.findById(TARGET_TEAM_ID)).thenReturn(Optional.of(provisioned));
            lenient().when(followRepository.save(any(FollowEntity.class)))
                    .thenReturn(follow(TEAM_ID, TARGET_TEAM_ID, 100L));
            lenient().when(followRepository.findByFollowerAndFollowedForUpdateNoWait(
                    FollowerType.TEAM, TARGET_TEAM_ID, FollowerType.TEAM, TEAM_ID)).thenReturn(Optional.empty());

            assertNotFound(() -> teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID));
            verify(followRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-7(数値): 論理削除済みチーム（@SQLRestriction で findById が空）は 404 SOCIAL_104")
        void ac7_数値_論理削除済みは404() {
            given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.empty());

            assertNotFound(() -> teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID));
            verify(followRepository, never()).save(any());
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND);
    }

    /** どちらの slug 解決クエリを使っても同じチームが返るようにスタブする（ACTIVE 行）。 */
    private void stubSlug(String slug, TeamEntity team) {
        lenient().when(teamRepository.findBySlugAndDeletedAtIsNullAndLifecycleStatus(
                slug, TeamEntity.LifecycleStatus.ACTIVE)).thenReturn(Optional.of(team));
        lenient().when(teamRepository.findBySlugAndDeletedAtIsNull(slug)).thenReturn(Optional.of(team));
        lenient().when(teamRepository.findById(team.getId())).thenReturn(Optional.of(team));
    }

    /** どちらの slug 解決クエリでも見つからない（不在・論理削除済み）。 */
    private void stubSlugAbsent(String slug) {
        lenient().when(teamRepository.findBySlugAndDeletedAtIsNullAndLifecycleStatus(
                slug, TeamEntity.LifecycleStatus.ACTIVE)).thenReturn(Optional.empty());
        lenient().when(teamRepository.findBySlugAndDeletedAtIsNull(slug)).thenReturn(Optional.empty());
    }

    private static TeamEntity team(Long id, String slug) {
        TeamEntity entity = TeamEntity.builder().name("チーム-" + slug).slug(slug).build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private static FollowEntity follow(Long followerId, Long followedId, Long id) {
        FollowEntity entity = FollowEntity.builder()
                .followerType(FollowerType.TEAM)
                .followerId(followerId)
                .followedType(FollowerType.TEAM)
                .followedId(followedId)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}

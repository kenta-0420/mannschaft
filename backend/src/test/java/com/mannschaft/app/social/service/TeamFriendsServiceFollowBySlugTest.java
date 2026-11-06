package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.FriendContentForwardRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261012-0315 AC-7: 数値 ID 経路（{@code follow}）のフォロー先可否判定。
 *
 * <p>不在・論理削除済み・アーカイブ済み・PROVISIONED（ACTIVE 以外）のフォロー先は、いずれも
 * 404 SOCIAL_104 にして follows 行を作らない。判定は team ドメインの公開 Service
 * （{@code TeamService#isFollowableTeam}）に委ね、social 側は {@code TeamEntity} を扱わない。
 * 4 状態それぞれの判定そのものは {@code TeamServiceFollowableTeamTest}、実 DB での結果は
 * {@code TeamFriendFollowSlugIT} が検証する。slug 経路は {@code TeamFriendFollowBySlugServiceTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261012-0315 TeamFriendsService.follow のフォロー先可否（AC-7）")
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
    private TeamService teamService;
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
    private static final Long TARGET_TEAM_ID = 20L;

    @Test
    @DisplayName("AC-7(数値): フォロー不可のチーム（アーカイブ済み・非ACTIVE・論理削除済み・不在）は 404 SOCIAL_104 で行を作らない")
    void ac7_数値_フォロー不可は404() {
        given(teamService.isFollowableTeam(TARGET_TEAM_ID)).willReturn(false);

        assertThatThrownBy(() -> teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND);
        verify(followRepository, never()).save(any());
    }
}

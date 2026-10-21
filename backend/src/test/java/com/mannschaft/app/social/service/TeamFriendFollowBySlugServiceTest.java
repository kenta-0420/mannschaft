package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261012-0315 slug 指定フォローの入口（{@link TeamFriendFollowBySlugService}）の単体テスト。
 *
 * <h2>契約</h2>
 * <ul>
 *   <li>検証順: ① 権限（MANAGE_FRIEND_TEAMS・403 COMMON_002）を slug 解決より先に行う
 *       （権限の無い者に slug の実在を 404/403 の差で教えない）② team ドメインで slug 解決とフォロー可否
 *       （不在・論理削除・アーカイブ・非 ACTIVE は 404 SOCIAL_104）③ 解決した ID で
 *       {@code TeamFriendsService#follow} に委譲（自チーム 400・既フォロー 409・後続処理は共通）。</li>
 *   <li>本クラスは {@code @Transactional} を持たない（トランザクション・キャッシュ失効は follow が担う）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261012-0315 TeamFriendFollowBySlugService（slug 指定フォローの入口）")
class TeamFriendFollowBySlugServiceTest {

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private TeamService teamService;
    @Mock
    private TeamFriendsService teamFriendsService;

    @InjectMocks
    private TeamFriendFollowBySlugService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;
    private static final String TARGET_SLUG = "rival-fc";

    @Test
    @DisplayName("AC-1: 権限確認の後に slug を解決し、解決した内部 ID で follow に委譲する")
    void ac1_slug指定でfollowに委譲する() {
        FollowTeamResponse expected = FollowTeamResponse.builder().followId(100L).build();
        given(teamService.resolveFollowableTeamId(TARGET_SLUG)).willReturn(Optional.of(TARGET_TEAM_ID));
        given(teamFriendsService.follow(TEAM_ID, TARGET_TEAM_ID, USER_ID)).willReturn(expected);

        FollowTeamResponse result = service.followBySlug(TEAM_ID, TARGET_SLUG, USER_ID);

        assertThat(result).isSameAs(expected);
        var order = inOrder(accessControlService, teamService, teamFriendsService);
        order.verify(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
        order.verify(teamService).resolveFollowableTeamId(TARGET_SLUG);
        order.verify(teamFriendsService).follow(TEAM_ID, TARGET_TEAM_ID, USER_ID);
    }

    @Test
    @DisplayName("AC-2 / AC-7: 解決できない slug（不在・論理削除・アーカイブ・非ACTIVE）は 404 SOCIAL_104 で follow を呼ばない")
    void ac2_ac7_解決できないslugは404() {
        given(teamService.resolveFollowableTeamId("no-such-team")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.followBySlug(TEAM_ID, "no-such-team", USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND);
        verify(teamFriendsService, never()).follow(any(), any(), any());
    }

    @Test
    @DisplayName("AC-6: 権限の無い者には不在 slug でも 404 ではなく 403 COMMON_002（slug の実在を漏らさない）")
    void ac6_権限なしは不在slugでも403で解決もしない() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");

        assertThatThrownBy(() -> service.followBySlug(TEAM_ID, "no-such-team", USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
        verifyNoInteractions(teamService, teamFriendsService);
    }

    @Test
    @DisplayName("AC-3: 自チームの slug は follow が 400 SOCIAL_101 を投げ、そのまま伝わる")
    void ac3_自チームslugはfollowの400が伝わる() {
        given(teamService.resolveFollowableTeamId("my-team")).willReturn(Optional.of(TEAM_ID));
        given(teamFriendsService.follow(TEAM_ID, TEAM_ID, USER_ID))
                .willThrow(new BusinessException(SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW));

        assertThatThrownBy(() -> service.followBySlug(TEAM_ID, "my-team", USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SocialErrorCode.FRIEND_CANNOT_SELF_FOLLOW);
    }
}

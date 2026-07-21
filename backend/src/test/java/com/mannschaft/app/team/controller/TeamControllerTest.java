package com.mannschaft.app.team.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityErrorCode;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.role.service.BlockService;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.role.service.PermissionGroupService;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.social.service.FollowService;
import com.mannschaft.app.supporter.dto.FollowStatusResponse;
import com.mannschaft.app.supporter.service.SupporterService;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamController} の単体テスト。
 *
 * <p>主眼: チーム取得・メンバー一覧の <strong>可視性認可（F00 委譲）</strong>を検証する。
 * 非メンバー（可視性ラダー未満）は {@link ContentVisibilityChecker#assertCanView} が
 * 例外を投げ、Service 本体（取得処理）が呼ばれずに 403/404 へ伝播することを確認する。
 * これは「visibility=MEMBERS_AND_ABOVE のチームに非メンバーが 200 アクセスしてメンバー情報が
 * 漏洩する」実機 E2E バグの回帰テストである。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamController 単体テスト")
class TeamControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final String TEAM_SLUG = "test-team";

    @Mock private TeamService teamService;
    @Mock private RoleService roleService;
    @Mock private AccessControlService accessControlService;
    @Mock private InviteService inviteService;
    @Mock private PermissionGroupService permissionGroupService;
    @Mock private BlockService blockService;
    @Mock private SupporterService supporterService;
    @Mock private FollowService followService;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private TeamController controller;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private TeamResponse teamResponse() {
        return TeamResponse.builder()
                .id(TEAM_SLUG)
                .slug(TEAM_SLUG)
                .basicInfo(new TeamResponse.TeamBasicInfoDto("テストチーム", null, null, null))
                .visibility(new TeamResponse.TeamVisibilityDto("MEMBERS_AND_ABOVE", true))
                .timestamps(new TeamResponse.TeamTimestampsDto(null, LocalDateTime.now()))
                .build();
    }

    // ========================================
    // getTeam: 可視性認可
    // ========================================

    @Test
    @DisplayName("getTeam: 可視性チェック通過時は 200 OK（PUBLIC 等の許可ケース）")
    void getTeam_200_whenVisibilityAllows() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(teamService.getTeam(TEAM_SLUG)).willReturn(ApiResponse.of(teamResponse()));
        assertThat(controller.getTeam(TEAM_SLUG).getStatusCode()).isEqualTo(HttpStatus.OK);
        // F00 正準: TEAM 可視性を ContentVisibilityChecker に委譲して判定している
        verify(contentVisibilityChecker).assertCanView(ReferenceType.TEAM, TEAM_ID, USER_ID);
    }

    @Test
    @DisplayName("getTeam: 非メンバー（可視性ラダー未満）は例外伝播し、Service 取得本体を呼ばない")
    void getTeam_denied_whenNonMember() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        willThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_001))
                .given(contentVisibilityChecker)
                .assertCanView(ReferenceType.TEAM, TEAM_ID, USER_ID);
        assertThatThrownBy(() -> controller.getTeam(TEAM_SLUG))
                .isInstanceOf(BusinessException.class);
        verify(teamService, Mockito.never()).getTeam(TEAM_SLUG);
    }

    // ========================================
    // getMembers: 可視性認可（メンバー情報列挙の遮断）
    // ========================================

    @Test
    @DisplayName("getMembers: 可視性チェック通過時は 200 OK")
    void getMembers_200_whenVisibilityAllows() {
        Pageable pageable = PageRequest.of(0, 10);
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(teamService.getMembers(TEAM_ID, pageable)).willReturn(
                PagedResponse.of(
                        List.of(new MemberResponse(USER_ID, "テスト", null, "ADMIN", LocalDateTime.now())),
                        new PagedResponse.PageMeta(1L, 0, 10, 1)));
        assertThat(controller.getMembers(TEAM_SLUG, pageable).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(contentVisibilityChecker).assertCanView(ReferenceType.TEAM, TEAM_ID, USER_ID);
    }

    @Test
    @DisplayName("getMembers: 非メンバーはメンバー一覧を取得できない（userId/displayName/role 漏洩の遮断）")
    void getMembers_denied_whenNonMember() {
        Pageable pageable = PageRequest.of(0, 10);
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        willThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_001))
                .given(contentVisibilityChecker)
                .assertCanView(ReferenceType.TEAM, TEAM_ID, USER_ID);
        assertThatThrownBy(() -> controller.getMembers(TEAM_SLUG, pageable))
                .isInstanceOf(BusinessException.class);
        verify(teamService, Mockito.never()).getMembers(TEAM_ID, pageable);
    }

    @Test
    @DisplayName("getTeam: 不在チームは NOT_FOUND がそのまま伝播（IDOR/エニュメレーション対策）")
    void getTeam_notFound_propagates() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        willThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_004))
                .given(contentVisibilityChecker)
                .assertCanView(ReferenceType.TEAM, TEAM_ID, USER_ID);
        assertThatThrownBy(() -> controller.getTeam(TEAM_SLUG))
                .isInstanceOf(BusinessException.class);
        verify(teamService, Mockito.never()).getTeam(TEAM_SLUG);
    }

    // ========================================
    // followTeam: サポーター自己登録の可視性・受け入れ可否ゲート（認可根治 Wave6）
    // ========================================

    @Test
    @DisplayName("followTeam: 可視性・受け入れ可否ともに通過すれば 201 Created（正常系）")
    void followTeam_201_whenGatesPass() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        given(supporterService.follow(USER_ID, "TEAM", TEAM_ID))
                .willReturn(ApiResponse.of(FollowStatusResponse.approved()));
        assertThat(controller.followTeam(TEAM_SLUG).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(contentVisibilityChecker).assertCanView(ReferenceType.TEAM, TEAM_ID, USER_ID);
        verify(teamService).assertSupporterEnabled(TEAM_ID);
        verify(supporterService).follow(USER_ID, "TEAM", TEAM_ID);
    }

    @Test
    @DisplayName("followTeam: 可視性ラダー未満は例外伝播し、サポーター登録本体を呼ばない")
    void followTeam_denied_whenNotVisible() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        willThrow(new BusinessException(VisibilityErrorCode.VISIBILITY_001))
                .given(contentVisibilityChecker)
                .assertCanView(ReferenceType.TEAM, TEAM_ID, USER_ID);
        assertThatThrownBy(() -> controller.followTeam(TEAM_SLUG))
                .isInstanceOf(BusinessException.class);
        verify(teamService, Mockito.never()).assertSupporterEnabled(TEAM_ID);
        verify(supporterService, Mockito.never()).follow(USER_ID, "TEAM", TEAM_ID);
    }

    @Test
    @DisplayName("followTeam: サポーター受け入れ無効は例外伝播し、サポーター登録本体を呼ばない")
    void followTeam_denied_whenSupporterDisabled() {
        given(teamService.resolveTeamId(TEAM_SLUG)).willReturn(TEAM_ID);
        willThrow(new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_SUPPORTER_DISABLED))
                .given(teamService).assertSupporterEnabled(TEAM_ID);
        assertThatThrownBy(() -> controller.followTeam(TEAM_SLUG))
                .isInstanceOf(BusinessException.class);
        verify(supporterService, Mockito.never()).follow(USER_ID, "TEAM", TEAM_ID);
    }
}

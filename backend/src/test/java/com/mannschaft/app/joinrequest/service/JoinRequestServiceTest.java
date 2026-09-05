package com.mannschaft.app.joinrequest.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.joinrequest.JoinRequestErrorCode;
import com.mannschaft.app.joinrequest.dto.JoinRequestCreateRequest;
import com.mannschaft.app.joinrequest.dto.JoinRequestResponse;
import com.mannschaft.app.joinrequest.dto.JoinRequestReviewRequest;
import com.mannschaft.app.joinrequest.entity.JoinRequestEntity;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;
import com.mannschaft.app.joinrequest.event.JoinRequestCreatedEvent;
import com.mannschaft.app.joinrequest.event.JoinRequestReviewedEvent;
import com.mannschaft.app.joinrequest.repository.JoinRequestRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.service.MembershipGrantService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link JoinRequestService} の単体テスト（柱③-A・CMP-260901-1538）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JoinRequestService 単体テスト")
class JoinRequestServiceTest {

    @Mock
    private JoinRequestRepository joinRequestRepository;

    @Mock
    private TeamService teamService;

    @Mock
    private OrganizationService organizationService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private MembershipGrantService membershipGrantService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private JoinRequestService service;

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 2L;

    private TeamService.JoinabilitySummary publicActiveTeam() {
        return new TeamService.JoinabilitySummary("チームA", false, true, false);
    }

    private OrganizationService.JoinabilitySummary publicActiveOrg() {
        return new OrganizationService.JoinabilitySummary("組織A", false, true, false);
    }

    private JoinRequestEntity pendingTeamRequest() {
        return JoinRequestEntity.builder()
                .teamId(TEAM_ID)
                .requesterUserId(USER_ID)
                .status(JoinRequestStatus.PENDING)
                .build();
    }

    // ========================================================================
    // createRequest
    // ========================================================================

    @Nested
    @DisplayName("createRequest")
    class CreateRequest {

        @Test
        @DisplayName("正常系: PUBLIC な ACTIVE チームへ申請できる")
        void 正常_チーム申請() {
            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(joinRequestRepository.findByTeamIdAndRequesterUserIdAndStatus(
                    TEAM_ID, USER_ID, JoinRequestStatus.PENDING)).willReturn(Optional.empty());
            given(joinRequestRepository.save(any(JoinRequestEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            JoinRequestResponse response = service.createRequest(
                    "TEAM", TEAM_ID, USER_ID, new JoinRequestCreateRequest("よろしくお願いします"));

            assertThat(response.status()).isEqualTo(JoinRequestStatus.PENDING);
            assertThat(response.scopeType()).isEqualTo("TEAM");
            assertThat(response.scopeId()).isEqualTo(TEAM_ID);
            assertThat(response.requesterUserId()).isEqualTo(USER_ID);
            verify(eventPublisher).publishEvent(any(JoinRequestCreatedEvent.class));
        }

        @Test
        @DisplayName("異常系: 存在しないチームは 404 SCOPE_NOT_FOUND")
        void 異常_チーム不存在() {
            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.createRequest("TEAM", TEAM_ID, USER_ID, null))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(JoinRequestErrorCode.SCOPE_NOT_FOUND);
                        assertThat(e.getHttpStatusOverride()).isEqualTo(HttpStatus.NOT_FOUND);
                    });
        }

        @Test
        @DisplayName("異常系: PRIVATE（非PUBLIC）チームは不存在と同一の 404 SCOPE_NOT_FOUND（存在秘匿）")
        void 異常_非公開チームは不存在と同一コード() {
            given(teamService.findJoinabilitySummary(TEAM_ID))
                    .willReturn(Optional.of(new TeamService.JoinabilitySummary("チームB", false, false, false)));

            assertThatThrownBy(() -> service.createRequest("TEAM", TEAM_ID, USER_ID, null))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(JoinRequestErrorCode.SCOPE_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: PROVISIONED チームは不存在と同一の 404 SCOPE_NOT_FOUND")
        void 異常_プロビジョニング中は同一コード() {
            given(teamService.findJoinabilitySummary(TEAM_ID))
                    .willReturn(Optional.of(new TeamService.JoinabilitySummary("チームC", false, true, true)));

            assertThatThrownBy(() -> service.createRequest("TEAM", TEAM_ID, USER_ID, null))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(JoinRequestErrorCode.SCOPE_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: アーカイブ済みチームは同一の 404 SCOPE_NOT_FOUND")
        void 異常_アーカイブ済みは同一コード() {
            given(teamService.findJoinabilitySummary(TEAM_ID))
                    .willReturn(Optional.of(new TeamService.JoinabilitySummary("チームD", true, true, false)));

            assertThatThrownBy(() -> service.createRequest("TEAM", TEAM_ID, USER_ID, null))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(JoinRequestErrorCode.SCOPE_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 既にメンバーなら 409 ALREADY_MEMBER")
        void 異常_既にメンバー() {
            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertThatThrownBy(() -> service.createRequest("TEAM", TEAM_ID, USER_ID, null))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(JoinRequestErrorCode.ALREADY_MEMBER);
                        assertThat(e.getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }

        @Test
        @DisplayName("冪等性: PENDING 中の重複申請は新規作成せず既存申請を返す")
        void 冪等_PENDING重複は新規作成しない() {
            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            JoinRequestEntity existing = pendingTeamRequest();
            given(joinRequestRepository.findByTeamIdAndRequesterUserIdAndStatus(
                    TEAM_ID, USER_ID, JoinRequestStatus.PENDING)).willReturn(Optional.of(existing));

            JoinRequestResponse response = service.createRequest("TEAM", TEAM_ID, USER_ID, null);

            assertThat(response.requesterUserId()).isEqualTo(USER_ID);
            verify(joinRequestRepository, never()).save(any(JoinRequestEntity.class));
        }

        @Test
        @DisplayName("正常系: 組織スコープでも同様に申請できる")
        void 正常_組織申請() {
            given(organizationService.findJoinabilitySummary(ORG_ID)).willReturn(Optional.of(publicActiveOrg()));
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(joinRequestRepository.findByOrganizationIdAndRequesterUserIdAndStatus(
                    ORG_ID, USER_ID, JoinRequestStatus.PENDING)).willReturn(Optional.empty());
            given(joinRequestRepository.save(any(JoinRequestEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            JoinRequestResponse response = service.createRequest("ORGANIZATION", ORG_ID, USER_ID, null);

            assertThat(response.scopeType()).isEqualTo("ORGANIZATION");
            assertThat(response.scopeId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("異常系: 不正な scopeType は 400 INVALID_SCOPE_TYPE")
        void 異常_不正スコープ種別() {
            assertThatThrownBy(() -> service.createRequest("VILLAGE", 1L, USER_ID, null))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(JoinRequestErrorCode.INVALID_SCOPE_TYPE));
        }
    }

    // ========================================================================
    // approve
    // ========================================================================

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("正常系: 承認すると MEMBER ロールが付与され APPROVED になる")
        void 正常_承認() {
            UUID requestId = UUID.randomUUID();
            JoinRequestEntity req = pendingTeamRequest();
            req.setId(requestId);

            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            given(joinRequestRepository.findById(requestId)).willReturn(Optional.of(req));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(joinRequestRepository.save(any(JoinRequestEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            JoinRequestResponse response = service.approve(
                    "TEAM", TEAM_ID, requestId, ADMIN_ID, new JoinRequestReviewRequest("歓迎します"));

            assertThat(response.status()).isEqualTo(JoinRequestStatus.APPROVED);
            verify(accessControlService).checkAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM");
            verify(membershipGrantService).grantMemberRole("TEAM", TEAM_ID, USER_ID, ADMIN_ID, "JOIN_REQUEST");
            verify(eventPublisher).publishEvent(any(JoinRequestReviewedEvent.class));
        }

        @Test
        @DisplayName("異常系: PENDING でない申請の承認は 409 ALREADY_REVIEWED")
        void 異常_既に処理済み() {
            UUID requestId = UUID.randomUUID();
            JoinRequestEntity req = pendingTeamRequest();
            req.setId(requestId);
            req.setStatus(JoinRequestStatus.APPROVED);

            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            given(joinRequestRepository.findById(requestId)).willReturn(Optional.of(req));

            assertThatThrownBy(() -> service.approve("TEAM", TEAM_ID, requestId, ADMIN_ID, null))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(JoinRequestErrorCode.ALREADY_REVIEWED));
            verify(membershipGrantService, never()).grantMemberRole(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("異常系: 別スコープの申請 ID を指定すると不在と同一の 404 REQUEST_NOT_FOUND（IDOR対策）")
        void 異常_別スコープの申請IDはIDOR対策で404() {
            UUID requestId = UUID.randomUUID();
            JoinRequestEntity req = JoinRequestEntity.builder()
                    .teamId(999L) // 別チーム
                    .requesterUserId(USER_ID)
                    .status(JoinRequestStatus.PENDING)
                    .build();
            req.setId(requestId);

            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            given(joinRequestRepository.findById(requestId)).willReturn(Optional.of(req));

            assertThatThrownBy(() -> service.approve("TEAM", TEAM_ID, requestId, ADMIN_ID, null))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(JoinRequestErrorCode.REQUEST_NOT_FOUND));
        }

        @Test
        @DisplayName("異常系: 権限が無ければ Service 認可で拒否される")
        void 異常_権限なし() {
            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            org.mockito.Mockito.doThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.approve("TEAM", TEAM_ID, UUID.randomUUID(), USER_ID, null))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================================================
    // reject
    // ========================================================================

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("正常系: 却下すると REJECTED になり、メンバーシップは付与されない")
        void 正常_却下() {
            UUID requestId = UUID.randomUUID();
            JoinRequestEntity req = pendingTeamRequest();
            req.setId(requestId);

            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            given(joinRequestRepository.findById(requestId)).willReturn(Optional.of(req));
            given(joinRequestRepository.save(any(JoinRequestEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            JoinRequestResponse response = service.reject(
                    "TEAM", TEAM_ID, requestId, ADMIN_ID, new JoinRequestReviewRequest("定員に達しました"));

            assertThat(response.status()).isEqualTo(JoinRequestStatus.REJECTED);
            assertThat(response.reviewComment()).isEqualTo("定員に達しました");
            verify(membershipGrantService, never()).grantMemberRole(any(), any(), any(), any(), any());
            verify(eventPublisher).publishEvent(any(JoinRequestReviewedEvent.class));
        }
    }

    // ========================================================================
    // listMine
    // ========================================================================

    @Nested
    @DisplayName("listMine")
    class ListMine {

        @Test
        @DisplayName("正常系: 自分の申請のみを新しい順で返す")
        void 正常_自分の申請一覧() {
            given(teamService.findJoinabilitySummary(TEAM_ID)).willReturn(Optional.of(publicActiveTeam()));
            given(joinRequestRepository.findByTeamIdAndRequesterUserIdOrderByCreatedAtDesc(TEAM_ID, USER_ID))
                    .willReturn(List.of(pendingTeamRequest()));

            List<JoinRequestResponse> result = service.listMine("TEAM", TEAM_ID, USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).requesterUserId()).isEqualTo(USER_ID);
        }
    }
}

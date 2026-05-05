package com.mannschaft.app.supporter.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.supporter.SupporterApplicationStatus;
import com.mannschaft.app.supporter.SupporterErrorCode;
import com.mannschaft.app.supporter.dto.BulkApproveRequest;
import com.mannschaft.app.supporter.dto.FollowStatusResponse;
import com.mannschaft.app.supporter.entity.SupporterApplicationEntity;
import com.mannschaft.app.supporter.entity.SupporterSettingsEntity;
import com.mannschaft.app.supporter.repository.SupporterApplicationRepository;
import com.mannschaft.app.supporter.repository.SupporterSettingsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SupporterService} 単体テスト。
 *
 * <p>F00.5 Phase 5: SupporterService の承認フローが
 * MembershipService 経由（memberships テーブル）に移管されたことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SupporterService 単体テスト")
class SupporterServiceTest {

    @Mock
    private SupporterApplicationRepository applicationRepository;

    @Mock
    private SupporterSettingsRepository settingsRepository;

    @Mock
    private MembershipService membershipService;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SupporterService service;

    // ============================================================
    // follow()
    // ============================================================

    @Nested
    @DisplayName("follow() — サポーター申請")
    class FollowTest {

        @Test
        @DisplayName("自動承認ON: membershipService.join() が SUPPORTER で呼ばれる")
        void autoApprove_calls_membershipService_join() {
            given(membershipRepository.existsActiveByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(false);
            given(settingsRepository.findByScopeTypeAndScopeId("TEAM", 20L))
                    .willReturn(Optional.of(settingsEntity(true)));

            // MembershipService.join() は MembershipDto を返すが、戻り値は使わないので null で OK
            given(membershipService.join(any(MembershipCreateRequest.class))).willReturn(null);

            ApiResponse<FollowStatusResponse> resp = service.follow(10L, "TEAM", 20L);

            assertThat(resp.getData().status()).isEqualTo("APPROVED");

            ArgumentCaptor<MembershipCreateRequest> captor =
                    ArgumentCaptor.forClass(MembershipCreateRequest.class);
            verify(membershipService).join(captor.capture());
            MembershipCreateRequest req = captor.getValue();
            assertThat(req.getUserId()).isEqualTo(10L);
            assertThat(req.getScopeType()).isEqualTo(ScopeType.TEAM);
            assertThat(req.getScopeId()).isEqualTo(20L);
            assertThat(req.getRoleKind()).isEqualTo(RoleKind.SUPPORTER);
            assertThat(req.getSource()).isEqualTo("SELF_SUPPORTER_REGISTRATION");
        }

        @Test
        @DisplayName("自動承認OFF: PENDING 申請レコードが作成される（membershipService.join() は呼ばれない）")
        void manualApprove_creates_pending_application() {
            given(membershipRepository.existsActiveByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(false);
            given(settingsRepository.findByScopeTypeAndScopeId("TEAM", 20L))
                    .willReturn(Optional.of(settingsEntity(false)));
            given(applicationRepository.existsByScopeTypeAndScopeIdAndUserIdAndStatus(
                    "TEAM", 20L, 10L, SupporterApplicationStatus.PENDING)).willReturn(false);
            given(applicationRepository.save(any(SupporterApplicationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ApiResponse<FollowStatusResponse> resp = service.follow(10L, "TEAM", 20L);

            assertThat(resp.getData().status()).isEqualTo("PENDING");
            verify(membershipService, never()).join(any());
        }

        @Test
        @DisplayName("既にアクティブなメンバーシップあり → SUPPORTER_002 例外")
        void alreadyMember_throws() {
            given(membershipRepository.existsActiveByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(true);

            assertThatThrownBy(() -> service.follow(10L, "TEAM", 20L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", SupporterErrorCode.SUPPORTER_002);
            verify(membershipService, never()).join(any());
        }

        @Test
        @DisplayName("PENDING 申請が既にある → SUPPORTER_001 例外")
        void alreadyPending_throws() {
            given(membershipRepository.existsActiveByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(false);
            given(settingsRepository.findByScopeTypeAndScopeId("TEAM", 20L))
                    .willReturn(Optional.of(settingsEntity(false)));
            given(applicationRepository.existsByScopeTypeAndScopeIdAndUserIdAndStatus(
                    "TEAM", 20L, 10L, SupporterApplicationStatus.PENDING)).willReturn(true);

            assertThatThrownBy(() -> service.follow(10L, "TEAM", 20L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", SupporterErrorCode.SUPPORTER_001);
        }
    }

    // ============================================================
    // unfollow()
    // ============================================================

    @Nested
    @DisplayName("unfollow() — サポーター解除")
    class UnfollowTest {

        @Test
        @DisplayName("APPROVED: membershipService.leave() が SELF で呼ばれる")
        void approved_calls_leave_with_self() {
            MembershipEntity m = activeMembership(55L, 10L, ScopeType.TEAM, 20L);
            given(membershipRepository.findActiveByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(Optional.of(m));
            given(applicationRepository.findByScopeTypeAndScopeIdAndUserIdAndStatus(
                    "TEAM", 20L, 10L, SupporterApplicationStatus.PENDING))
                    .willReturn(Optional.empty());
            given(membershipService.leave(eq(55L), any(MembershipLeaveRequest.class))).willReturn(null);

            service.unfollow(10L, "TEAM", 20L);

            ArgumentCaptor<MembershipLeaveRequest> captor =
                    ArgumentCaptor.forClass(MembershipLeaveRequest.class);
            verify(membershipService).leave(eq(55L), captor.capture());
            assertThat(captor.getValue().getLeaveReason()).isEqualTo(LeaveReason.SELF);
        }

        @Test
        @DisplayName("PENDING のみ: PENDING 申請レコードが削除される（leave() は呼ばれない）")
        void pendingOnly_deletes_application() {
            given(membershipRepository.findActiveByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(Optional.empty());
            SupporterApplicationEntity app = pendingApp(10L, "TEAM", 20L);
            given(applicationRepository.findByScopeTypeAndScopeIdAndUserIdAndStatus(
                    "TEAM", 20L, 10L, SupporterApplicationStatus.PENDING))
                    .willReturn(Optional.of(app));

            service.unfollow(10L, "TEAM", 20L);

            verify(applicationRepository).delete(app);
            verify(membershipService, never()).leave(any(), any());
        }
    }

    // ============================================================
    // getFollowStatus()
    // ============================================================

    @Nested
    @DisplayName("getFollowStatus() — フォロー状態取得")
    class GetFollowStatusTest {

        @Test
        @DisplayName("SUPPORTER メンバーシップあり → APPROVED")
        void approvedStatus() {
            given(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    10L, ScopeType.TEAM, 20L, RoleKind.SUPPORTER)).willReturn(true);

            ApiResponse<FollowStatusResponse> resp = service.getFollowStatus(10L, "TEAM", 20L);
            assertThat(resp.getData().status()).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("PENDING 申請あり → PENDING")
        void pendingStatus() {
            given(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    10L, ScopeType.TEAM, 20L, RoleKind.SUPPORTER)).willReturn(false);
            given(applicationRepository.existsByScopeTypeAndScopeIdAndUserIdAndStatus(
                    "TEAM", 20L, 10L, SupporterApplicationStatus.PENDING)).willReturn(true);

            ApiResponse<FollowStatusResponse> resp = service.getFollowStatus(10L, "TEAM", 20L);
            assertThat(resp.getData().status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("何もなし → NONE")
        void noneStatus() {
            given(membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                    10L, ScopeType.TEAM, 20L, RoleKind.SUPPORTER)).willReturn(false);
            given(applicationRepository.existsByScopeTypeAndScopeIdAndUserIdAndStatus(
                    "TEAM", 20L, 10L, SupporterApplicationStatus.PENDING)).willReturn(false);

            ApiResponse<FollowStatusResponse> resp = service.getFollowStatus(10L, "TEAM", 20L);
            assertThat(resp.getData().status()).isEqualTo("NONE");
        }
    }

    // ============================================================
    // approve()
    // ============================================================

    @Nested
    @DisplayName("approve() — 個別承認")
    class ApproveTest {

        @Test
        @DisplayName("正常系: ステータス APPROVED + membershipService.join() が呼ばれる")
        void normalApprove() {
            SupporterApplicationEntity app = pendingApp(10L, "TEAM", 20L);
            ReflectionTestUtils.setField(app, "id", 1L);
            given(applicationRepository.findById(1L)).willReturn(Optional.of(app));
            given(membershipService.join(any(MembershipCreateRequest.class))).willReturn(null);

            service.approve(1L, "TEAM", 20L);

            assertThat(app.getStatus()).isEqualTo(SupporterApplicationStatus.APPROVED);

            ArgumentCaptor<MembershipCreateRequest> captor =
                    ArgumentCaptor.forClass(MembershipCreateRequest.class);
            verify(membershipService).join(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(10L);
            assertThat(captor.getValue().getRoleKind()).isEqualTo(RoleKind.SUPPORTER);
            assertThat(captor.getValue().getSource()).isEqualTo("SUPPORTER_APPLICATION");
        }
    }

    // ============================================================
    // bulkApprove()
    // ============================================================

    @Nested
    @DisplayName("bulkApprove() — 一括承認")
    class BulkApproveTest {

        @Test
        @DisplayName("正常系: 全件 membershipService.join() が SUPPORTER で呼ばれる")
        void bulkApprove_callsJoinForEach() {
            SupporterApplicationEntity app1 = pendingApp(10L, "TEAM", 20L);
            SupporterApplicationEntity app2 = pendingApp(11L, "TEAM", 20L);
            ReflectionTestUtils.setField(app1, "id", 1L);
            ReflectionTestUtils.setField(app2, "id", 2L);

            given(applicationRepository.findById(1L)).willReturn(Optional.of(app1));
            given(applicationRepository.findById(2L)).willReturn(Optional.of(app2));
            given(membershipService.join(any(MembershipCreateRequest.class))).willReturn(null);

            BulkApproveRequest req = new BulkApproveRequest();
            ReflectionTestUtils.setField(req, "applicationIds", List.of(1L, 2L));

            service.bulkApprove(req, "TEAM", 20L);

            ArgumentCaptor<MembershipCreateRequest> captor =
                    ArgumentCaptor.forClass(MembershipCreateRequest.class);
            verify(membershipService, org.mockito.Mockito.times(2)).join(captor.capture());
            captor.getAllValues().forEach(r -> {
                assertThat(r.getRoleKind()).isEqualTo(RoleKind.SUPPORTER);
                assertThat(r.getSource()).isEqualTo("SUPPORTER_APPLICATION");
            });
        }
    }

    // ============================================================
    // reject()
    // ============================================================

    @Nested
    @DisplayName("reject() — 却下")
    class RejectTest {

        @Test
        @DisplayName("正常系: ステータス REJECTED（membershipService は呼ばれない）")
        void normalReject() {
            SupporterApplicationEntity app = pendingApp(10L, "TEAM", 20L);
            ReflectionTestUtils.setField(app, "id", 1L);
            given(applicationRepository.findById(1L)).willReturn(Optional.of(app));

            service.reject(1L, "TEAM", 20L);

            assertThat(app.getStatus()).isEqualTo(SupporterApplicationStatus.REJECTED);
            verify(membershipService, never()).join(any());
            verify(membershipService, never()).leave(any(), any());
        }
    }

    // ============================================================
    // ヘルパー
    // ============================================================

    private static SupporterSettingsEntity settingsEntity(boolean autoApprove) {
        return SupporterSettingsEntity.builder()
                .scopeType("TEAM")
                .scopeId(20L)
                .autoApprove(autoApprove)
                .build();
    }

    private static MembershipEntity activeMembership(Long id, Long userId, ScopeType st, Long scopeId) {
        MembershipEntity e = MembershipEntity.builder()
                .userId(userId).scopeType(st).scopeId(scopeId)
                .roleKind(RoleKind.SUPPORTER)
                .joinedAt(LocalDateTime.now().minusDays(5))
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static SupporterApplicationEntity pendingApp(Long userId, String scopeType, Long scopeId) {
        return SupporterApplicationEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .status(SupporterApplicationStatus.PENDING)
                .build();
    }
}

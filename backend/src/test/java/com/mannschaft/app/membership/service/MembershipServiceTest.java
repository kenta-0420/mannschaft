package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.AssignPositionRequest;
import com.mannschaft.app.membership.dto.EndPositionRequest;
import com.mannschaft.app.membership.dto.MemberPositionDto;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipDto;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MemberPositionEntity;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.entity.PositionEntity;
import com.mannschaft.app.membership.repository.MemberPositionRepository;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.repository.PositionRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MembershipService} 単体テスト。
 *
 * <p>設計書 §14.1 のスコープに対応:</p>
 * <ul>
 *   <li>入会・退会・再加入の状態遷移</li>
 *   <li>last admin 保護の発火</li>
 *   <li>役職割当・終了・スコープ越境拒否</li>
 *   <li>バリデーションエラー</li>
 * </ul>
 *
 * <p>Phase 4 完了: 二重書き込み（dualWrite）関連テストを削除済み。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipService 単体テスト")
class MembershipServiceTest {

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private MemberPositionRepository memberPositionRepository;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private MembershipService service;

    @Nested
    @DisplayName("join() — 入会")
    class JoinTest {

        @Test
        @DisplayName("正常系: 新規入会で memberships に INSERT され、ASSIGNED イベント発火")
        void newJoin() {
            MembershipCreateRequest req = req(99L, ScopeType.TEAM, 100L, RoleKind.MEMBER, null);
            given(membershipRepository.findActiveByUserAndScope(99L, ScopeType.TEAM, 100L))
                    .willReturn(Optional.empty());
            given(membershipRepository.findHistoryByUserAndScope(99L, ScopeType.TEAM, 100L))
                    .willReturn(List.of());
            given(membershipRepository.save(any(MembershipEntity.class)))
                    .willAnswer(inv -> {
                        MembershipEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", 555L);
                        return e;
                    });

            MembershipDto dto = service.join(req);

            assertThat(dto.id()).isEqualTo(555L);
            assertThat(dto.userId()).isEqualTo(99L);
            assertThat(dto.scopeType()).isEqualTo(ScopeType.TEAM);
            assertThat(dto.scopeId()).isEqualTo(100L);
            assertThat(dto.roleKind()).isEqualTo(RoleKind.MEMBER);
            assertThat(dto.isRejoin()).isFalse();

            ArgumentCaptor<MembershipChangedEvent> ev = ArgumentCaptor.forClass(MembershipChangedEvent.class);
            verify(eventPublisher).publishEvent(ev.capture());
            assertThat(ev.getValue().changeType()).isEqualTo(MembershipChangedEvent.ChangeType.ASSIGNED);
        }

        @Test
        @DisplayName("冪等性: 既存 active 同 role_kind ならそのまま返却")
        void idempotent() {
            MembershipCreateRequest req = req(99L, ScopeType.TEAM, 100L, RoleKind.MEMBER, null);
            MembershipEntity existing = MembershipEntity.builder()
                    .userId(99L).scopeType(ScopeType.TEAM).scopeId(100L)
                    .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build();
            ReflectionTestUtils.setField(existing, "id", 777L);
            given(membershipRepository.findActiveByUserAndScope(99L, ScopeType.TEAM, 100L))
                    .willReturn(Optional.of(existing));

            MembershipDto dto = service.join(req);

            assertThat(dto.id()).isEqualTo(777L);
            verify(membershipRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("既存 active の role_kind が異なれば 409 ACTIVE_EXISTS")
        void conflictDifferentRoleKind() {
            MembershipCreateRequest req = req(99L, ScopeType.TEAM, 100L, RoleKind.SUPPORTER, null);
            MembershipEntity existing = MembershipEntity.builder()
                    .userId(99L).scopeType(ScopeType.TEAM).scopeId(100L)
                    .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build();
            given(membershipRepository.findActiveByUserAndScope(99L, ScopeType.TEAM, 100L))
                    .willReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.join(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", MembershipBasisErrorCode.MEMBERSHIP_ACTIVE_EXISTS);
        }

        @Test
        @DisplayName("再加入: 履歴行があれば isRejoin=true")
        void rejoin() {
            MembershipCreateRequest req = req(99L, ScopeType.TEAM, 100L, RoleKind.MEMBER, null);
            MembershipEntity past = MembershipEntity.builder()
                    .userId(99L).scopeType(ScopeType.TEAM).scopeId(100L)
                    .roleKind(RoleKind.MEMBER)
                    .joinedAt(LocalDateTime.now().minusYears(1))
                    .leftAt(LocalDateTime.now().minusMonths(11))
                    .leaveReason(LeaveReason.SELF)
                    .build();
            given(membershipRepository.findActiveByUserAndScope(99L, ScopeType.TEAM, 100L))
                    .willReturn(Optional.empty());
            given(membershipRepository.findHistoryByUserAndScope(99L, ScopeType.TEAM, 100L))
                    .willReturn(List.of(past));
            given(membershipRepository.save(any(MembershipEntity.class)))
                    .willAnswer(inv -> {
                        MembershipEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", 888L);
                        return e;
                    });

            MembershipDto dto = service.join(req);
            assertThat(dto.isRejoin()).isTrue();
        }

        @Test
        @DisplayName("validateScope: scopeId NULL で MEMBERSHIP_INVALID_SCOPE")
        void invalidScope() {
            MembershipCreateRequest req = req(99L, ScopeType.TEAM, null, RoleKind.MEMBER, null);
            assertThatThrownBy(() -> service.join(req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", MembershipBasisErrorCode.MEMBERSHIP_INVALID_SCOPE);
        }
    }

    @Nested
    @DisplayName("leave() — 退会")
    class LeaveTest {

        @Test
        @DisplayName("正常系: left_at と leave_reason がセットされる")
        void normalLeave() {
            MembershipEntity entity = activeMembership(11L, 99L, ScopeType.TEAM, 100L, RoleKind.MEMBER);
            given(membershipRepository.findById(11L)).willReturn(Optional.of(entity));
            given(memberPositionRepository.findCurrentByMembership(11L)).willReturn(List.of());
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.empty());

            MembershipLeaveRequest req = new MembershipLeaveRequest();
            req.setLeaveReason(LeaveReason.SELF);

            MembershipDto dto = service.leave(11L, req);

            assertThat(entity.getLeftAt()).isNotNull();
            assertThat(entity.getLeaveReason()).isEqualTo(LeaveReason.SELF);
            assertThat(dto.leaveReason()).isEqualTo(LeaveReason.SELF);
        }

        @Test
        @DisplayName("既に退会済みなら 409 ALREADY_LEFT")
        void alreadyLeft() {
            MembershipEntity entity = activeMembership(11L, 99L, ScopeType.TEAM, 100L, RoleKind.MEMBER);
            entity.setLeftAt(LocalDateTime.now().minusDays(1));
            entity.setLeaveReason(LeaveReason.SELF);
            given(membershipRepository.findById(11L)).willReturn(Optional.of(entity));

            MembershipLeaveRequest req = new MembershipLeaveRequest();
            req.setLeaveReason(LeaveReason.SELF);

            assertThatThrownBy(() -> service.leave(11L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", MembershipBasisErrorCode.MEMBERSHIP_ALREADY_LEFT);
        }

        @Test
        @DisplayName("最後の ADMIN 兼任で 409 LAST_ADMIN_BLOCKED")
        void lastAdminBlocked() {
            MembershipEntity entity = activeMembership(11L, 99L, ScopeType.TEAM, 100L, RoleKind.MEMBER);
            given(membershipRepository.findById(11L)).willReturn(Optional.of(entity));

            RoleEntity adminRole = role(2L, "ADMIN");
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(adminRole));
            given(userRoleRepository.existsByUserIdAndTeamIdAndRoleId(99L, 100L, 2L)).willReturn(true);
            given(userRoleRepository.countByTeamIdAndRoleId(100L, 2L)).willReturn(1L);

            MembershipLeaveRequest req = new MembershipLeaveRequest();
            req.setLeaveReason(LeaveReason.SELF);

            assertThatThrownBy(() -> service.leave(11L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", MembershipBasisErrorCode.MEMBERSHIP_LAST_ADMIN_BLOCKED);
        }

        @Test
        @DisplayName("退会時に紐付く現役 member_positions が自動 ended_at セット")
        void positionsAutoEnded() {
            MembershipEntity entity = activeMembership(11L, 99L, ScopeType.TEAM, 100L, RoleKind.MEMBER);
            MemberPositionEntity mp = MemberPositionEntity.builder()
                    .membershipId(11L).positionId(31L).startedAt(LocalDateTime.now().minusMonths(3)).build();
            given(membershipRepository.findById(11L)).willReturn(Optional.of(entity));
            given(memberPositionRepository.findCurrentByMembership(11L)).willReturn(List.of(mp));
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.empty());

            MembershipLeaveRequest req = new MembershipLeaveRequest();
            req.setLeaveReason(LeaveReason.SELF);
            service.leave(11L, req);

            assertThat(mp.getEndedAt()).isNotNull();
            verify(memberPositionRepository, times(1)).save(mp);
        }
    }

    @Nested
    @DisplayName("assignPosition() — 役職割当")
    class AssignPositionTest {

        @Test
        @DisplayName("正常系: 同スコープ内の position を割り当て")
        void normalAssign() {
            MembershipEntity m = activeMembership(11L, 99L, ScopeType.TEAM, 100L, RoleKind.MEMBER);
            PositionEntity p = PositionEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(100L)
                    .name("TREASURER").displayName("会計係").build();
            ReflectionTestUtils.setField(p, "id", 31L);
            given(membershipRepository.findById(11L)).willReturn(Optional.of(m));
            given(positionRepository.findById(31L)).willReturn(Optional.of(p));
            given(memberPositionRepository.save(any(MemberPositionEntity.class)))
                    .willAnswer(inv -> {
                        MemberPositionEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", 12001L);
                        return e;
                    });

            AssignPositionRequest req = new AssignPositionRequest();
            req.setPositionId(31L);
            req.setAssignedBy(7L);

            MemberPositionDto dto = service.assignPosition(11L, req);
            assertThat(dto.id()).isEqualTo(12001L);
            assertThat(dto.positionId()).isEqualTo(31L);
        }

        @Test
        @DisplayName("スコープ越境で MEMBERSHIP_POSITION_SCOPE_MISMATCH")
        void crossScopeRejected() {
            MembershipEntity m = activeMembership(11L, 99L, ScopeType.TEAM, 100L, RoleKind.MEMBER);
            PositionEntity p = PositionEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(999L)
                    .name("TREASURER").displayName("会計係").build();
            ReflectionTestUtils.setField(p, "id", 31L);
            given(membershipRepository.findById(11L)).willReturn(Optional.of(m));
            given(positionRepository.findById(31L)).willReturn(Optional.of(p));

            AssignPositionRequest req = new AssignPositionRequest();
            req.setPositionId(31L);

            assertThatThrownBy(() -> service.assignPosition(11L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            MembershipBasisErrorCode.MEMBERSHIP_POSITION_SCOPE_MISMATCH);
        }

        @Test
        @DisplayName("退会済 membership には割り当て不可")
        void cannotAssignToLeftMembership() {
            MembershipEntity m = activeMembership(11L, 99L, ScopeType.TEAM, 100L, RoleKind.MEMBER);
            m.setLeftAt(LocalDateTime.now().minusDays(1));
            m.setLeaveReason(LeaveReason.SELF);
            given(membershipRepository.findById(11L)).willReturn(Optional.of(m));

            AssignPositionRequest req = new AssignPositionRequest();
            req.setPositionId(31L);

            assertThatThrownBy(() -> service.assignPosition(11L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            MembershipBasisErrorCode.MEMBERSHIP_ALREADY_LEFT);
        }
    }

    @Nested
    @DisplayName("endPosition() — 役職終了")
    class EndPositionTest {

        @Test
        @DisplayName("正常系: ended_at がセットされる")
        void normalEnd() {
            MemberPositionEntity mp = MemberPositionEntity.builder()
                    .membershipId(11L).positionId(31L)
                    .startedAt(LocalDateTime.now().minusMonths(3)).build();
            ReflectionTestUtils.setField(mp, "id", 12001L);
            given(memberPositionRepository.findById(12001L)).willReturn(Optional.of(mp));

            EndPositionRequest req = new EndPositionRequest();
            req.setEndedAt(LocalDateTime.now());

            MemberPositionDto dto = service.endPosition(12001L, req);
            assertThat(mp.getEndedAt()).isNotNull();
            assertThat(dto.endedAt()).isNotNull();
        }

        @Test
        @DisplayName("ended_at < started_at なら MEMBERSHIP_PERIOD_INVERTED")
        void periodInverted() {
            LocalDateTime started = LocalDateTime.now();
            MemberPositionEntity mp = MemberPositionEntity.builder()
                    .membershipId(11L).positionId(31L).startedAt(started).build();
            ReflectionTestUtils.setField(mp, "id", 12001L);
            given(memberPositionRepository.findById(12001L)).willReturn(Optional.of(mp));

            EndPositionRequest req = new EndPositionRequest();
            req.setEndedAt(started.minusDays(1));

            assertThatThrownBy(() -> service.endPosition(12001L, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            MembershipBasisErrorCode.MEMBERSHIP_PERIOD_INVERTED);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private static MembershipCreateRequest req(Long userId, ScopeType st, Long scopeId,
                                                RoleKind rk, Long invitedBy) {
        MembershipCreateRequest r = new MembershipCreateRequest();
        r.setUserId(userId);
        r.setScopeType(st);
        r.setScopeId(scopeId);
        r.setRoleKind(rk);
        r.setInvitedBy(invitedBy);
        r.setSource("TEST");
        return r;
    }

    private static MembershipEntity activeMembership(Long id, Long userId, ScopeType st,
                                                      Long scopeId, RoleKind rk) {
        MembershipEntity e = MembershipEntity.builder()
                .userId(userId).scopeType(st).scopeId(scopeId).roleKind(rk)
                .joinedAt(LocalDateTime.now().minusMonths(1))
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static RoleEntity role(Long id, String name) {
        RoleEntity r = RoleEntity.builder()
                .name(name)
                .displayName(name)
                .priority(0)
                .isSystem(false)
                .build();
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    // ========================================
    // Phase 3 → Phase 4 イベント発火テスト
    // ========================================

    @Nested
    @DisplayName("EventTest — MembershipChangedEvent 発火確認")
    class EventTest {

        @Test
        @DisplayName("join() で MembershipChangedEvent(ASSIGNED) が発火される")
        void join_fires_membership_changed_event() {
            MembershipCreateRequest req = req(10L, ScopeType.TEAM, 20L, RoleKind.MEMBER, null);
            given(membershipRepository.findActiveByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(Optional.empty());
            given(membershipRepository.findHistoryByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(List.of());
            given(membershipRepository.save(any(MembershipEntity.class)))
                    .willAnswer(inv -> {
                        MembershipEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", 100L);
                        return e;
                    });

            service.join(req);

            ArgumentCaptor<MembershipChangedEvent> captor =
                    ArgumentCaptor.forClass(MembershipChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().changeType())
                    .isEqualTo(MembershipChangedEvent.ChangeType.ASSIGNED);
            assertThat(captor.getValue().userId()).isEqualTo(10L);
            assertThat(captor.getValue().scopeId()).isEqualTo(20L);
        }

        @Test
        @DisplayName("leave() で MembershipChangedEvent(REMOVED) が発火される")
        void leave_fires_membership_changed_event() {
            MembershipEntity entity = activeMembership(200L, 10L, ScopeType.TEAM, 20L, RoleKind.MEMBER);
            given(membershipRepository.findById(200L)).willReturn(Optional.of(entity));
            given(memberPositionRepository.findCurrentByMembership(200L)).willReturn(List.of());
            // last admin 保護: ADMIN ロールが存在しない → 保護不要（早期 return）
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.empty());
            given(membershipRepository.save(any(MembershipEntity.class))).willAnswer(inv -> inv.getArgument(0));
            MembershipLeaveRequest req = new MembershipLeaveRequest();
            req.setLeaveReason(LeaveReason.SELF);

            service.leave(200L, req);

            ArgumentCaptor<MembershipChangedEvent> captor =
                    ArgumentCaptor.forClass(MembershipChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().changeType())
                    .isEqualTo(MembershipChangedEvent.ChangeType.REMOVED);
            assertThat(captor.getValue().userId()).isEqualTo(10L);
            assertThat(captor.getValue().scopeId()).isEqualTo(20L);
        }

        @Test
        @DisplayName("join() で userRoleRepository.save() は呼ばれない（二重書き込み廃止済み）")
        void join_no_dual_write() {
            MembershipCreateRequest req = req(10L, ScopeType.TEAM, 20L, RoleKind.MEMBER, null);
            given(membershipRepository.findActiveByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(Optional.empty());
            given(membershipRepository.findHistoryByUserAndScope(10L, ScopeType.TEAM, 20L))
                    .willReturn(List.of());
            given(membershipRepository.save(any(MembershipEntity.class)))
                    .willAnswer(inv -> {
                        MembershipEntity e = inv.getArgument(0);
                        ReflectionTestUtils.setField(e, "id", 100L);
                        return e;
                    });

            service.join(req);

            verify(userRoleRepository, never()).save(any());
        }
    }
}

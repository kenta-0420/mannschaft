package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.MembershipGrantService.GrantOutcome;
import com.mannschaft.app.team.TeamErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link MembershipGrantService} の単体テスト。
 *
 * <p>招待承諾（{@code InviteAcceptanceFacade}）と参加申請承認（柱③-A・CMP-260901-1538）の
 * 双方が経由する共通のロール付与・入会経路であることを検証する。CMP-260826-1457 で、
 * 全経路の user 行ロック先行、招待のみ ACTIVE 確認・user_roles ∪ memberships の現在読み、
 * 戻り値 {@link GrantOutcome} を追加した。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipGrantService 単体テスト")
class MembershipGrantServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private MembershipService membershipService;

    @Mock
    private UserRowLockService userRowLockService;

    @InjectMocks
    private MembershipGrantService service;

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long USER_ID = 1L;
    private static final Long GRANTED_BY = 2L;
    private static final Long ROLE_ID = 3L;

    private void userIs(UserRowLockService.UserState state) {
        given(userRowLockService.lock(USER_ID)).willReturn(state);
    }

    @Nested
    @DisplayName("grantRole（招待）")
    class GrantRole {

        @Test
        @DisplayName("TEAM スコープで user_roles 割当と membership 入会の両方を行い GRANTED を返す")
        void チーム() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

            GrantOutcome outcome = service.grantRole("TEAM", TEAM_ID, USER_ID, ROLE_ID, GRANTED_BY, "INVITE_TOKEN");

            assertThat(outcome).isEqualTo(GrantOutcome.GRANTED);
            ArgumentCaptor<UserRoleEntity> roleCaptor = ArgumentCaptor.forClass(UserRoleEntity.class);
            verify(userRoleRepository).save(roleCaptor.capture());
            assertThat(roleCaptor.getValue().getTeamId()).isEqualTo(TEAM_ID);
            assertThat(roleCaptor.getValue().getRoleId()).isEqualTo(ROLE_ID);
            assertThat(roleCaptor.getValue().getUserId()).isEqualTo(USER_ID);

            ArgumentCaptor<MembershipCreateRequest> membershipCaptor =
                    ArgumentCaptor.forClass(MembershipCreateRequest.class);
            verify(membershipService).join(membershipCaptor.capture());
            MembershipCreateRequest req = membershipCaptor.getValue();
            assertThat(req.getUserId()).isEqualTo(USER_ID);
            assertThat(req.getScopeType()).isEqualTo(ScopeType.TEAM);
            assertThat(req.getScopeId()).isEqualTo(TEAM_ID);
            assertThat(req.getRoleKind()).isEqualTo(RoleKind.MEMBER);
            assertThat(req.getInvitedBy()).isEqualTo(GRANTED_BY);
            assertThat(req.getSource()).isEqualTo("INVITE_TOKEN");
        }

        @Test
        @DisplayName("ORGANIZATION スコープでも同様に動作する")
        void 組織() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.grantRole("ORGANIZATION", ORG_ID, USER_ID, ROLE_ID, GRANTED_BY, "INVITE_TOKEN");

            ArgumentCaptor<UserRoleEntity> roleCaptor = ArgumentCaptor.forClass(UserRoleEntity.class);
            verify(userRoleRepository).save(roleCaptor.capture());
            assertThat(roleCaptor.getValue().getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(roleCaptor.getValue().getTeamId()).isNull();
        }

        @Test
        @DisplayName("ロック順: user 行 → user_roles → memberships の順に現在読みし、その後に付与する")
        void ロック順() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.grantRole("TEAM", TEAM_ID, USER_ID, ROLE_ID, GRANTED_BY, "INVITE_TOKEN");

            InOrder order = inOrder(userRowLockService, userRoleRepository, membershipService);
            order.verify(userRowLockService).lock(USER_ID);
            order.verify(userRoleRepository).findByUserIdAndTeamIdForUpdate(USER_ID, TEAM_ID);
            order.verify(membershipService).isActiveMemberForUpdate(USER_ID, ScopeType.TEAM, TEAM_ID);
            order.verify(userRoleRepository).save(any(UserRoleEntity.class));
            order.verify(membershipService).join(any(MembershipCreateRequest.class));
        }

        @Test
        @DisplayName("user が ACTIVE でなければ ROLE_002（何も書かない）")
        void 非ACTIVEはROLE_002() {
            userIs(UserRowLockService.UserState.INELIGIBLE_EXISTING);

            assertThatThrownBy(() -> service.grantRole("TEAM", TEAM_ID, USER_ID, ROLE_ID, GRANTED_BY, "INVITE_TOKEN"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(RoleErrorCode.ROLE_002));
            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
            verify(membershipService, never()).join(any(MembershipCreateRequest.class));
        }

        @Test
        @DisplayName("memberships に在籍していれば ALREADY_JOINED（二重付与防止・P1-2）")
        void membershipsに在籍ならALREADY_JOINED() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(membershipService.isActiveMemberForUpdate(USER_ID, ScopeType.TEAM, TEAM_ID)).willReturn(true);

            GrantOutcome outcome = service.grantRole("TEAM", TEAM_ID, USER_ID, ROLE_ID, GRANTED_BY, "INVITE_TOKEN");

            assertThat(outcome).isEqualTo(GrantOutcome.ALREADY_JOINED);
            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
            verify(membershipService, never()).join(any(MembershipCreateRequest.class));
        }

        @Test
        @DisplayName("user_roles だけが残っていても ALREADY_JOINED（和集合の現在読み）")
        void userRolesだけ残っていてもALREADY_JOINED() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(userRoleRepository.findByUserIdAndOrganizationIdForUpdate(USER_ID, ORG_ID))
                    .willReturn(Optional.of(UserRoleEntity.builder().userId(USER_ID).roleId(ROLE_ID).build()));

            GrantOutcome outcome = service.grantRole("ORGANIZATION", ORG_ID, USER_ID, ROLE_ID, GRANTED_BY,
                    "INVITE_TOKEN");

            assertThat(outcome).isEqualTo(GrantOutcome.ALREADY_JOINED);
            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("付与中の一意制約違反は 409 TEAM_003 の業務エラーへ変換する（呼び出し側が確保を戻せる）")
        void 一意制約違反は業務エラー() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(userRoleRepository.save(any(UserRoleEntity.class)))
                    .willThrow(new DataIntegrityViolationException("duplicate"));

            assertThatThrownBy(() -> service.grantRole("TEAM", TEAM_ID, USER_ID, ROLE_ID, GRANTED_BY, "INVITE_TOKEN"))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_003);
                        assertThat(e.getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
                    });
        }
    }

    @Nested
    @DisplayName("grantMemberRole（参加申請承認）")
    class GrantMemberRole {

        @Test
        @DisplayName("user 行ロックを最初に取り、MEMBER ロールを解決して付与する")
        void MEMBERロール解決() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(roleRepository.findByName("MEMBER"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ROLE_ID).name("MEMBER").build()));
            given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.grantMemberRole("TEAM", TEAM_ID, USER_ID, GRANTED_BY, "JOIN_REQUEST");

            InOrder order = inOrder(userRowLockService, roleRepository);
            order.verify(userRowLockService).lock(USER_ID);
            order.verify(roleRepository).findByName("MEMBER");
            ArgumentCaptor<UserRoleEntity> roleCaptor = ArgumentCaptor.forClass(UserRoleEntity.class);
            verify(userRoleRepository).save(roleCaptor.capture());
            assertThat(roleCaptor.getValue().getRoleId()).isEqualTo(ROLE_ID);
            verify(membershipService).join(any(MembershipCreateRequest.class));
        }

        @Test
        @DisplayName("非 ACTIVE ユーザーでも現行どおり付与する（requireActive=false）")
        void 非ACTIVEでも付与する() {
            userIs(UserRowLockService.UserState.INELIGIBLE_EXISTING);
            given(roleRepository.findByName("MEMBER"))
                    .willReturn(Optional.of(RoleEntity.builder().id(ROLE_ID).name("MEMBER").build()));
            given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.grantMemberRole("TEAM", TEAM_ID, USER_ID, GRANTED_BY, "JOIN_REQUEST");

            verify(membershipService).join(any(MembershipCreateRequest.class));
        }

        @Test
        @DisplayName("在籍判定は現行維持（memberships のみ・スナップショット読み。user_roles は現在読みしない）")
        void 在籍判定は現行維持() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(membershipService.isActiveMember(USER_ID, ScopeType.TEAM, TEAM_ID)).willReturn(true);

            service.grantMemberRole("TEAM", TEAM_ID, USER_ID, GRANTED_BY, "JOIN_REQUEST");

            verify(userRoleRepository, never()).findByUserIdAndTeamIdForUpdate(any(), any());
            verify(membershipService, never()).isActiveMemberForUpdate(any(), any(), any());
            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
            verify(membershipService, never()).join(any(MembershipCreateRequest.class));
        }

        @Test
        @DisplayName("MEMBER ロールがマスタに存在しなければ例外で入会もしない")
        void ロール不在() {
            userIs(UserRowLockService.UserState.ACTIVE);
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.grantMemberRole("TEAM", TEAM_ID, USER_ID, GRANTED_BY, "JOIN_REQUEST"))
                    .isInstanceOf(BusinessException.class);
            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
            verify(membershipService, never()).join(any(MembershipCreateRequest.class));
        }
    }
}

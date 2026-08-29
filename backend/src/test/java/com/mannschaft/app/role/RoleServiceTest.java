package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.RolePermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionGroupRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import com.mannschaft.app.role.repository.RolePermissionRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserPermissionGroupRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.role.service.RolePermissionCleanupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RoleService} の単体テスト。
 * ロール割当・変更・除名・退会・有効権限解決・オーナー譲渡を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleService 単体テスト")
class RoleServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;
    private static final Long SCOPE_ID = 10L;
    private static final Long ADMIN_ROLE_ID = 100L;
    private static final Long MEMBER_ROLE_ID = 101L;

    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private RolePermissionRepository rolePermissionRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private PermissionGroupRepository permissionGroupRepository;
    @Mock private PermissionGroupPermissionRepository permissionGroupPermissionRepository;
    @Mock private UserPermissionGroupRepository userPermissionGroupRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MembershipService membershipService;
    @Mock private UserRowLockService userRowLockService;
    @Mock private RolePermissionCleanupService rolePermissionCleanupService;

    @InjectMocks
    private RoleService roleService;

    /**
     * issue #2544: 本番では {@code @Autowired @Lazy} で注入される自己プロキシ {@code self} を、
     * 純 Mockito UT では自分自身で埋める（キャッシュプロキシは介在しないので挙動は従来どおり）。
     * 埋めないと自己プロキシ経由の呼び出しが NPE になる。
     */
    @org.junit.jupiter.api.BeforeEach
    void setUpSelfProxy() {
        org.springframework.test.util.ReflectionTestUtils.setField(roleService, "self", roleService);
        // F09.14: mutation 操作者の有効ユーザー確認を全テストで満たす。
        lenient().doReturn(true).when(userRoleRepository).isActiveUser(USER_ID);
        lenient().when(membershipService.isActiveMember(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                .thenReturn(true);
        lenient().when(membershipService.isActiveMember(USER_ID, ScopeType.TEAM, SCOPE_ID))
                .thenReturn(true);
    }

    // ========================================
    // assignRole
    // ========================================

    @Nested
    @DisplayName("assignRole")
    class AssignRole {

        @BeforeEach
        void stubActorAdmin() {
            lenient().when(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .thenReturn(Optional.of(operatorAdminRole()));
            lenient().when(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .thenReturn(Optional.of(operatorAdminRole()));
            lenient().when(roleRepository.findById(ADMIN_ROLE_ID))
                    .thenReturn(Optional.of(createAdminRole()));
        }

        @Test
        @DisplayName("正常割当_ロールが保存される")
        void 正常割当_ロールが保存される() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());
            // CMP-052 陽性対照: isActiveUser は default メソッドでモックは default 実装を呼ばないため明示 stub する。
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(true);

            roleService.assignRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID);

            verify(userRoleRepository).save(any(UserRoleEntity.class));
            verify(userRowLockService).lockAll(USER_ID, TARGET_USER_ID);
            // F00.5 認可基盤根治: memberships にも MEMBER として入会させる（join 経由）。
            // 二重発火回避のため assignRole 側の手動 MembershipChangedEvent 発火は削除し join に一本化済み。
            ArgumentCaptor<MembershipCreateRequest> captor =
                    ArgumentCaptor.forClass(MembershipCreateRequest.class);
            verify(membershipService).join(captor.capture());
            MembershipCreateRequest joinReq = captor.getValue();
            assertThat(joinReq.getUserId()).isEqualTo(TARGET_USER_ID);
            assertThat(joinReq.getScopeType()).isEqualTo(ScopeType.ORGANIZATION);
            assertThat(joinReq.getScopeId()).isEqualTo(SCOPE_ID);
            assertThat(joinReq.getRoleKind()).isEqualTo(RoleKind.MEMBER);
            assertThat(joinReq.getSource()).isEqualTo("ROLE_ASSIGN");
        }

        @Test
        @DisplayName("既存ロール上書き_旧ロール削除後に新ロール保存")
        void 既存ロール上書き_旧ロール削除後に新ロール保存() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            UserRoleEntity existing = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(existing));
            // CMP-052 陽性対照: default メソッドのため明示 stub が必要。
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(true);

            roleService.assignRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID);

            verify(userRoleRepository).delete(existing);
            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("存在しないロール_ROLE_001例外")
        void 存在しないロール_ROLE_001例外() {
            given(roleRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> roleService.assignRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, 999L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));
        }

        @Test
        @DisplayName("チームスコープ_teamIdにセットされる")
        void チームスコープ_teamIdにセットされる() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());
            // CMP-052 陽性対照: default メソッドのため明示 stub が必要。
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(true);

            roleService.assignRole(SCOPE_ID, "TEAM", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID);

            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }

        // ------------------------------------------------------------------
        // CMP-052: 権限付与経路の生存確認（transferOwnership と対称にする）
        //
        // isActiveUser は UserRoleRepository の default メソッドだが、Mockito のモックは
        // default 実装を呼ばない（未 stub なら false 相当を返す）。したがって
        // given(userRoleRepository.isActiveUser(...)) で明示的に stub する必要がある。
        // ------------------------------------------------------------------

        @Test
        @DisplayName("凍結ユーザーへの割当_ROLE_001例外でsaveされない")
        void 凍結ユーザーへの割当_ROLE_001例外でsaveされない() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            // FROZEN（非ACTIVE）ユーザーは isActiveUser=false
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(false);

            assertThatThrownBy(() -> roleService.assignRole(
                    SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));

            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
            verify(membershipService, never()).join(any(MembershipCreateRequest.class));
        }

        @Test
        @DisplayName("論理削除済みユーザーへの割当_ROLE_001例外でsaveされない")
        void 論理削除済みユーザーへの割当_ROLE_001例外でsaveされない() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            // 論理削除済み（deleted_at IS NOT NULL）も isActiveUser=false に畳まれる
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(false);

            assertThatThrownBy(() -> roleService.assignRole(
                    SCOPE_ID, "TEAM", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));

            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("ロックアウト防止_凍結ユーザーをADMINに昇格できない")
        void ロックアウト防止_凍結ユーザーをADMINに昇格できない() {
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(false);

            assertThatThrownBy(() -> roleService.assignRole(
                    SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, ADMIN_ROLE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);

            // 凍結ユーザーが唯一の ADMIN になる経路が成立しないこと
            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
            verify(userRoleRepository, never()).delete(any(UserRoleEntity.class));
        }
    }

    // ========================================
    // changeRole
    // ========================================

    @Nested
    @DisplayName("changeRole")
    class ChangeRole {

        @Test
        @DisplayName("正常変更_ロールが変更される")
        void 正常変更_ロールが変更される() {
            // 束1 権限昇格根治: 操作者(USER_ID)は当該スコープの ADMIN である必要がある（requireActorAdmin）。
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            // CMP-052 陽性対照: isActiveUser は default メソッドでモックは default 実装を呼ばないため明示 stub する。
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(true);

            roleService.changeRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID,
                    new RoleChangeRequest(ADMIN_ROLE_ID), USER_ID);

            verify(userRoleRepository).delete(current);
            verify(userRoleRepository).save(any(UserRoleEntity.class));
            verify(userRowLockService).lockAll(USER_ID, TARGET_USER_ID);
        }

        @Test
        @DisplayName("根治回帰_delete直後にflushしてからsaveする")
        void 根治回帰_delete直後にflushしてからsaveする() {
            // 回帰防止: changeRole は delete → flush → save の順で呼ばねばならない。
            // flush を挟まないと Hibernate の write-behind が INSERT を先に発行し、
            // user_roles の uq_user_roles_user_scope(user_id, scope_key) ユニーク制約に
            // 旧行と衝突して 500 になる（実機 E2E + general_log で実証済みのバグ）。
            // 束1 権限昇格根治: 操作者(USER_ID)は当該スコープの ADMIN である必要がある（requireActorAdmin）。
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            // CMP-052 陽性対照: default メソッドのため明示 stub が必要。
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(true);

            roleService.changeRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID,
                    new RoleChangeRequest(ADMIN_ROLE_ID), USER_ID);

            InOrder inOrder = inOrder(userRoleRepository);
            inOrder.verify(userRoleRepository).delete(current);
            inOrder.verify(userRoleRepository).flush();
            inOrder.verify(userRoleRepository).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("最後のADMIN変更_ROLE_004例外")
        void 最後のADMIN変更_ROLE_004例外() {
            // 束1 権限昇格根治: 操作者(USER_ID)は当該スコープの ADMIN である必要がある（requireActorAdmin）。
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.countByOrganizationIdAndRoleId(SCOPE_ID, ADMIN_ROLE_ID)).willReturn(1L);

            assertThatThrownBy(() -> roleService.changeRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID,
                    new RoleChangeRequest(MEMBER_ROLE_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_004"));
        }

        // ------------------------------------------------------------------
        // CMP-052: ロール変更経路の生存確認
        // isActiveUser は default メソッドのため Mockito は default 実装を呼ばない。明示 stub する。
        // ------------------------------------------------------------------

        @Test
        @DisplayName("凍結ユーザーのロール変更_ROLE_001例外でsaveされない")
        void 凍結ユーザーのロール変更_ROLE_001例外でsaveされない() {
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(false);

            assertThatThrownBy(() -> roleService.changeRole(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID,
                    new RoleChangeRequest(ADMIN_ROLE_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));

            // ロックアウト防止: 凍結ユーザーが ADMIN になる経路が成立しない
            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
            verify(userRoleRepository, never()).delete(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("論理削除済みユーザーのロール変更_ROLE_001例外でsaveされない")
        void 論理削除済みユーザーのロール変更_ROLE_001例外でsaveされない() {
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).teamId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            // 論理削除済み（deleted_at IS NOT NULL）も isActiveUser=false に畳まれる
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(false);

            assertThatThrownBy(() -> roleService.changeRole(SCOPE_ID, "TEAM", TARGET_USER_ID,
                    new RoleChangeRequest(ADMIN_ROLE_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));

            verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
        }
    }

    // ========================================
    // removeMember
    // ========================================

    @Nested
    @DisplayName("removeMember")
    class RemoveMember {

        @Test
        @DisplayName("正常除名_ユーザーロールが削除される")
        void 正常除名_ユーザーロールが削除される() {
            // 束1 権限昇格根治: 操作者(USER_ID)は当該スコープの ADMIN である必要がある（requireActorAdmin）。
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));

            roleService.removeMember(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, USER_ID);

            verify(userRoleRepository).delete(current);
            verify(userRowLockService).lockAll(USER_ID, TARGET_USER_ID);
        }

        @Test
        @DisplayName("最後のADMIN除名_ROLE_004例外")
        void 最後のADMIN除名_ROLE_004例外() {
            // 束1 権限昇格根治: 操作者(USER_ID)は当該スコープの ADMIN である必要がある（requireActorAdmin）。
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(userRoleRepository.countByOrganizationIdAndRoleId(SCOPE_ID, ADMIN_ROLE_ID)).willReturn(1L);

            assertThatThrownBy(() -> roleService.removeMember(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_004"));
        }

        @Test
        @DisplayName("除名時_membershipsの離脱がREMOVEDと操作者付きで確定される")
        void 除名時_membershipsの離脱がREMOVEDと操作者付きで確定される() {
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            given(membershipService.leaveByUserAndScope(
                    TARGET_USER_ID, ScopeType.ORGANIZATION, SCOPE_ID, LeaveReason.REMOVED, USER_ID))
                    .willReturn(true);

            roleService.removeMember(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, USER_ID);

            verify(membershipService).leaveByUserAndScope(
                    TARGET_USER_ID, ScopeType.ORGANIZATION, SCOPE_ID, LeaveReason.REMOVED, USER_ID);
        }

        @Test
        @DisplayName("user_roles削除はmembershipsの離脱より先にflushされる")
        void user_roles削除はmembershipsの離脱より先にflushされる() {
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));

            roleService.removeMember(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, USER_ID);

            InOrder order = inOrder(userRoleRepository, membershipService);
            order.verify(userRoleRepository).delete(current);
            order.verify(userRoleRepository).flush();
            order.verify(membershipService).leaveByUserAndScope(
                    TARGET_USER_ID, ScopeType.ORGANIZATION, SCOPE_ID, LeaveReason.REMOVED, USER_ID);
        }

        @Test
        @DisplayName("membershipsの離脱が成立した場合_MembershipChangedEventを二重発火しない")
        void membershipsの離脱が成立した場合_MembershipChangedEventを二重発火しない() {
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            // 委譲先（MembershipService.leave）が REMOVED イベントを発火する経路。
            given(membershipService.leaveByUserAndScope(
                    any(), any(), any(), any(), any())).willReturn(true);

            roleService.removeMember(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, USER_ID);

            verify(eventPublisher, never()).publishEvent(any(MembershipChangedEvent.class));
        }

        @Test
        @DisplayName("在籍行が無い場合_MembershipChangedEventが補填発火される")
        void 在籍行が無い場合_MembershipChangedEventが補填発火される() {
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdminRole()));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            given(membershipService.leaveByUserAndScope(
                    any(), any(), any(), any(), any())).willReturn(false);

            roleService.removeMember(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID, USER_ID);

            ArgumentCaptor<MembershipChangedEvent> captor =
                    ArgumentCaptor.forClass(MembershipChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().changeType())
                    .isEqualTo(MembershipChangedEvent.ChangeType.REMOVED);
        }
    }

    // ========================================
    // removeMemberWithoutAdminCheck
    // ========================================

    @Nested
    @DisplayName("removeMemberWithoutAdminCheck")
    class RemoveMemberWithoutAdminCheck {

        @Test
        @DisplayName("正常_最後のADMINでも削除成功_MembershipChangedEvent発火")
        void 正常_最後のADMINでも削除成功_MembershipChangedEvent発火() {
            // 最後の ADMIN のシナリオを構築（adminCount = 1 でも例外を投げないことを検証）
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            // checkLastAdmin はスキップされるため roleRepository.findById や countByOrganizationIdAndRoleId は
            // 呼ばれないが、stubbing しなくても MockitoExtension は厳格でないため問題なし

            roleService.removeMemberWithoutAdminCheck(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID);

            // userRole が DELETE されたことを検証
            verify(userRoleRepository).delete(current);
            verify(userRowLockService).lockAll(TARGET_USER_ID);
            // MembershipChangedEvent(REMOVED) が発火されたことを検証
            verify(eventPublisher).publishEvent(any(MembershipChangedEvent.class));
        }

        @Test
        @DisplayName("対象未所属_ROLE_001例外")
        void 対象未所属_ROLE_001例外() {
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    roleService.removeMemberWithoutAdminCheck(SCOPE_ID, "ORGANIZATION", TARGET_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));
        }

        @Test
        @DisplayName("退会者purge時_membershipsの離脱がREMOVEDで確定される")
        void 退会者purge時_membershipsの離脱がREMOVEDで確定される() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(ADMIN_ROLE_ID).teamId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));

            roleService.removeMemberWithoutAdminCheck(SCOPE_ID, "TEAM", TARGET_USER_ID);

            verify(membershipService).leaveByUserAndScope(
                    TARGET_USER_ID, ScopeType.TEAM, SCOPE_ID, LeaveReason.REMOVED, null);
        }

        @Test
        @DisplayName("event_payload確認_REMOVED_userId_scopeId_scopeType")
        void event_payload確認_REMOVED_userId_scopeId_scopeType() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(TARGET_USER_ID).roleId(ADMIN_ROLE_ID).teamId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));

            roleService.removeMemberWithoutAdminCheck(SCOPE_ID, "TEAM", TARGET_USER_ID);

            // 在籍行が無い（leaveByUserAndScope が false）ため補填発火される経路。
            ArgumentCaptor<MembershipChangedEvent> captor =
                    ArgumentCaptor.forClass(MembershipChangedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            MembershipChangedEvent event = captor.getValue();
            assertThat(event.userId()).isEqualTo(TARGET_USER_ID);
            assertThat(event.scopeType()).isEqualTo("TEAM");
            assertThat(event.scopeId()).isEqualTo(SCOPE_ID);
            assertThat(event.changeType()).isEqualTo(MembershipChangedEvent.ChangeType.REMOVED);
        }
    }

    // ========================================
    // leaveScope
    // ========================================

    @Nested
    @DisplayName("leaveScope")
    class LeaveScope {

        @Test
        @DisplayName("正常退会_ユーザーロールが削除される")
        void 正常退会_ユーザーロールが削除される() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));

            roleService.leaveScope(USER_ID, SCOPE_ID, "ORGANIZATION");

            verify(userRoleRepository).delete(current);
            verify(userRowLockService).lockAll(USER_ID);
        }

        @Test
        @DisplayName("自主退会時_membershipsの離脱がSELFで確定される")
        void 自主退会時_membershipsの離脱がSELFで確定される() {
            UserRoleEntity current = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(MEMBER_ROLE_ID).teamId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(current));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));

            roleService.leaveScope(USER_ID, SCOPE_ID, "TEAM");

            verify(membershipService).leaveByUserAndScope(
                    USER_ID, ScopeType.TEAM, SCOPE_ID, LeaveReason.SELF, null);
        }
    }

    // ========================================
    // resolveEffectivePermissions
    // ========================================

    @Nested
    @DisplayName("resolveEffectivePermissions")
    class ResolveEffectivePermissions {

        @Test
        @DisplayName("ロール由来と権限グループ由来が統合される")
        void ロール由来と権限グループ由来が統合される() {
            UserRoleEntity ur = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(ur));
            given(userRoleRepository.isActiveUser(USER_ID)).willReturn(true);
            given(membershipService.isActiveMember(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID)).willReturn(true);
            given(membershipService.findActiveRoleKind(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(Optional.of(RoleKind.MEMBER));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(createAdminRole()));

            RolePermissionEntity rp = RolePermissionEntity.builder()
                    .id(1L).roleId(ADMIN_ROLE_ID).permissionId(1L).isDefault(true).build();
            given(rolePermissionRepository.findByRoleId(ADMIN_ROLE_ID)).willReturn(List.of(rp));

            PermissionEntity perm = PermissionEntity.builder()
                    .id(1L).name("MEMBER_MANAGE").displayName("メンバー管理")
                    .scope(PermissionEntity.Scope.ORGANIZATION).build();
            given(permissionRepository.findByIdIn(List.of(1L))).willReturn(List.of(perm));

            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of());

            List<String> permissions = roleService.resolveEffectivePermissions(USER_ID, SCOPE_ID, "ORGANIZATION");

            assertThat(permissions).contains("MEMBER_MANAGE");
        }

        @Test
        @DisplayName("ロール未割当_空リストが返される")
        void ロール未割当_空リストが返される() {
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of());

            List<String> permissions = roleService.resolveEffectivePermissions(USER_ID, SCOPE_ID, "ORGANIZATION");

            assertThat(permissions).isEmpty();
        }

        @Test
        @DisplayName("MEMBER の実効ロールでは DEPUTY_ADMIN 向け permission group を解決しない")
        void memberDoesNotResolveDeputyPermissionGroup() {
            UserRoleEntity role = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.isActiveUser(USER_ID)).willReturn(true);
            given(membershipService.isActiveMember(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID)).willReturn(true);
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(role));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));
            given(membershipService.findActiveRoleKind(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(Optional.of(RoleKind.MEMBER));

            PermissionGroupEntity group = PermissionGroupEntity.builder()
                    .id(99L).organizationId(SCOPE_ID).name("deputy-only")
                    .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).build();
            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of(group));
            given(userPermissionGroupRepository.findByUserId(USER_ID)).willReturn(List.of(
                    UserPermissionGroupEntity.builder().userId(USER_ID).groupId(99L).build()));
            assertThat(roleService.resolveEffectivePermissions(USER_ID, SCOPE_ID, "ORGANIZATION"))
                    .doesNotContain("DEPUTY_ONLY");
        }
    }

    // ========================================
    // hasPermission
    // ========================================

    @Nested
    @DisplayName("hasPermission")
    class HasPermission {

        @Test
        @DisplayName("権限あり_trueが返される")
        void 権限あり_trueが返される() {
            UserRoleEntity ur = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(ur));
            given(userRoleRepository.isActiveUser(USER_ID)).willReturn(true);
            given(membershipService.isActiveMember(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID)).willReturn(true);
            given(membershipService.findActiveRoleKind(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(Optional.of(RoleKind.MEMBER));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(createAdminRole()));

            RolePermissionEntity rp = RolePermissionEntity.builder()
                    .id(1L).roleId(ADMIN_ROLE_ID).permissionId(1L).isDefault(true).build();
            given(rolePermissionRepository.findByRoleId(ADMIN_ROLE_ID)).willReturn(List.of(rp));

            PermissionEntity perm = PermissionEntity.builder()
                    .id(1L).name("MEMBER_MANAGE").displayName("メンバー管理")
                    .scope(PermissionEntity.Scope.ORGANIZATION).build();
            given(permissionRepository.findByIdIn(List.of(1L))).willReturn(List.of(perm));
            given(permissionGroupRepository.findByOrganizationId(SCOPE_ID)).willReturn(List.of());

            boolean result = roleService.hasPermission(USER_ID, SCOPE_ID, "ORGANIZATION", "MEMBER_MANAGE");

            assertThat(result).isTrue();
        }
    }

    // ========================================
    // transferOwnership
    // ========================================

    @Nested
    @DisplayName("transferOwnership")
    class TransferOwnership {

        @Test
        @DisplayName("正常譲渡_ADMINとMEMBERが入れ替わる")
        void 正常譲渡_ADMINとMEMBERが入れ替わる() {
            UserRoleEntity currentUserRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
            UserRoleEntity targetUserRole = UserRoleEntity.builder()
                    .id(2L).userId(TARGET_USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();

            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(currentUserRole));
            // CMP-027: 譲渡先の在籍確認は existsByUserIdAndOrganizationId(user_roles ∪ memberships)経由。
            // findByUserIdAndOrganizationId は既存 user_roles 行の削除(ifPresent)にのみ使われる。
            given(userRoleRepository.existsByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(true);
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(targetUserRole));
            // CMP-050 AC-14【陽性対照】: 譲渡先のアカウント生存確認。isActiveUser は default メソッドだが
            // Mockito は default 実装を呼ばないため、明示的に stub する必要がある。
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(true);
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(createMemberRole()));

            roleService.transferOwnership(SCOPE_ID, "ORGANIZATION", USER_ID, TARGET_USER_ID);

            verify(userRoleRepository).delete(targetUserRole);
            verify(userRoleRepository).delete(currentUserRole);
            verify(userRowLockService).lockAll(USER_ID, TARGET_USER_ID);
            // F00.5 認可基盤根治（防御補填）: 譲渡当事者両名に冪等 join を補填する
            ArgumentCaptor<MembershipCreateRequest> captor =
                    ArgumentCaptor.forClass(MembershipCreateRequest.class);
            verify(membershipService, org.mockito.Mockito.times(2)).join(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(MembershipCreateRequest::getUserId)
                    .containsExactlyInAnyOrder(USER_ID, TARGET_USER_ID);
            assertThat(captor.getAllValues())
                    .allSatisfy(r -> {
                        assertThat(r.getScopeType()).isEqualTo(ScopeType.ORGANIZATION);
                        assertThat(r.getScopeId()).isEqualTo(SCOPE_ID);
                        assertThat(r.getRoleKind()).isEqualTo(RoleKind.MEMBER);
                        assertThat(r.getSource()).isEqualTo("OWNERSHIP_TRANSFER");
                    });
        }

        @Test
        @DisplayName("自分自身への譲渡_ROLE_001例外")
        void 自分自身への譲渡_ROLE_001例外() {
            assertThatThrownBy(() -> roleService.transferOwnership(SCOPE_ID, "ORGANIZATION", USER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));
        }

        @Test
        @DisplayName("非ADMIN_ROLE_001例外")
        void 非ADMIN_ROLE_001例外() {
            UserRoleEntity currentUserRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(MEMBER_ROLE_ID).organizationId(SCOPE_ID).build();

            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(currentUserRole));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(createMemberRole()));

            assertThatThrownBy(() -> roleService.transferOwnership(SCOPE_ID, "ORGANIZATION", USER_ID, TARGET_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));
        }

        /**
         * CMP-050 AC-13: 譲渡先が在籍はしているが FROZEN のとき ROLE_001 で拒否し、
         * {@code save} を一度も呼ばないこと。
         *
         * <p>在籍プリミティブが ACTIVE を問わないままだと、凍結ユーザーがスコープ唯一の
         * ADMIN へ昇格し、以後そのスコープは誰も操作できなくなる。ErrorCode を分けると
         * 他人のアカウント状態が漏れるため、本メソッドの他の拒否と同じ ROLE_001 へ畳む。</p>
         *
         * <p>{@code isActiveUser} は default メソッドであり Mockito は default 実装を呼ばない。
         * 実際の SQL 判定ではなく stub の戻り値で分岐を締める。</p>
         */
        @Test
        @DisplayName("CMP-050 AC-13: 譲渡先が非ACTIVE_ROLE_001例外でsaveを呼ばない")
        void cmp050_AC13_譲渡先が非ACTIVE_ROLE_001例外() {
            UserRoleEntity currentUserRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();

            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(currentUserRole));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(createAdminRole()));
            // 在籍はしている（凍結ユーザーの在籍行自体は残る運用）
            given(userRoleRepository.existsByUserIdAndOrganizationId(TARGET_USER_ID, SCOPE_ID))
                    .willReturn(true);
            given(userRoleRepository.isActiveUser(TARGET_USER_ID)).willReturn(false);

            assertThatThrownBy(() -> roleService.transferOwnership(SCOPE_ID, "ORGANIZATION", USER_ID, TARGET_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .as("他人のアカウント状態を漏らさないため他の拒否と同じ ROLE_001 へ畳むこと")
                            .isEqualTo("ROLE_001"));

            verify(userRoleRepository, org.mockito.Mockito.never())
                    .save(org.mockito.ArgumentMatchers.any(UserRoleEntity.class));
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RoleEntity createAdminRole() {
        return RoleEntity.builder()
                .id(ADMIN_ROLE_ID).name("ADMIN").displayName("管理者").priority(2).isSystem(true).build();
    }

    private RoleEntity createMemberRole() {
        return RoleEntity.builder()
                .id(MEMBER_ROLE_ID).name("MEMBER").displayName("メンバー").priority(4).isSystem(true).build();
    }

    /**
     * 束1 権限昇格根治: 操作者(USER_ID)が当該スコープの ADMIN であることを表す user_roles 行。
     * requireActorAdmin が {@code findUserRole(actor) → roleRepository.findById(roleId=ADMIN_ROLE_ID)} で
     * ADMIN 判定するために用いる。
     */
    private UserRoleEntity operatorAdminRole() {
        return UserRoleEntity.builder()
                .id(99L).userId(USER_ID).roleId(ADMIN_ROLE_ID).organizationId(SCOPE_ID).build();
    }
}

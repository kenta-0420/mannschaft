package com.mannschaft.app.common;

import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link AccessControlService} の単体テスト。
 * メンバーシップ検証・ロール判定・権限チェック・複合チェックを検証する。
 *
 * <p>F00.5 Phase 3: isMember() / checkMembership() は memberships テーブルを参照するよう切替済み。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccessControlService 単体テスト")
class AccessControlServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private RoleService roleService;

    @Mock
    private UserCareLinkRepository userCareLinkRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private OrganizationMembershipService organizationMembershipService;

    @InjectMocks
    private AccessControlService accessControlService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long ROLE_ID = 100L;

    private UserRoleEntity createUserRole(Long roleId) {
        return UserRoleEntity.builder()
                .id(1L)
                .userId(USER_ID)
                .roleId(roleId)
                .teamId(SCOPE_ID)
                .build();
    }

    private RoleEntity createRole(String name, int priority) {
        return RoleEntity.builder()
                .id(ROLE_ID)
                .name(name)
                .displayName(name)
                .priority(priority)
                .isSystem(true)
                .build();
    }

    // ========================================
    // checkMembership
    // ========================================

    @Nested
    @DisplayName("checkMembership")
    class CheckMembership {

        @Test
        @DisplayName("正常系: TEAMスコープでアクティブなメンバーシップがある場合は例外なし")
        void checkMembership_TEAMスコープでメンバー_例外なし() {
            // Given: F00.5 Phase 3 — memberships 参照に切替済み
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(true);

            // When / Then（例外が発生しないことを確認）
            accessControlService.checkMembership(USER_ID, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでアクティブなメンバーシップがある場合は例外なし")
        void checkMembership_ORGANIZATIONスコープでメンバー_例外なし() {
            // Given: F00.5 Phase 3 — memberships 参照に切替済み
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(true);

            // When / Then
            accessControlService.checkMembership(USER_ID, SCOPE_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("異常系: アクティブなメンバーシップがない場合はCOMMON_002例外")
        void checkMembership_非メンバー_COMMON002例外() {
            // Given: F00.5 Phase 3 — memberships 参照に切替済み
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(false);

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkMembership(USER_ID, SCOPE_ID, "TEAM"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    // ========================================
    // isMember
    // ========================================

    @Nested
    @DisplayName("isMember")
    class IsMember {

        @Test
        @DisplayName("正常系: TEAMスコープでアクティブなメンバーシップがあればtrue")
        void isMember_TEAMスコープでメンバー_true() {
            // Given: F00.5 Phase 3 — memberships 参照に切替済み
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(true);

            // When
            boolean result = accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでアクティブなメンバーシップがあればtrue")
        void isMember_ORGANIZATIONスコープでメンバー_true() {
            // Given: F00.5 Phase 3 — memberships 参照に切替済み
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(true);

            // When
            boolean result = accessControlService.isMember(USER_ID, SCOPE_ID, "ORGANIZATION");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: アクティブなメンバーシップがなければfalse")
        void isMember_非メンバー_false() {
            // Given: F00.5 Phase 3 — memberships 参照に切替済み
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(false);

            // When
            boolean result = accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("追加: アクティブなメンバーシップが存在する場合true (isMember_returns_true_when_active_membership_exists)")
        void isMember_returns_true_when_active_membership_exists() {
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(true);
            assertThat(accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM")).isTrue();
        }

        @Test
        @DisplayName("追加: アクティブなメンバーシップが存在しない場合false (isMember_returns_false_when_no_active_membership)")
        void isMember_returns_false_when_no_active_membership() {
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(false);
            assertThat(accessControlService.isMember(USER_ID, SCOPE_ID, "TEAM")).isFalse();
        }

        @Test
        @DisplayName("追加: left_at がセット済み（退会済）の場合はfalse (isMember_returns_false_when_membership_left)")
        void isMember_returns_false_when_membership_left() {
            // left_at がある場合、existsActiveByUserAndScope は false を返す（leftAt IS NULL 条件）
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(false);
            assertThat(accessControlService.isMember(USER_ID, SCOPE_ID, "ORGANIZATION")).isFalse();
        }
    }

    // ========================================
    // isMemberOrDescendant / checkMembershipOrDescendant（欠陥Z 根治）
    // ========================================

    @Nested
    @DisplayName("isMemberOrDescendant / checkMembershipOrDescendant")
    class IsMemberOrDescendant {

        @Test
        @DisplayName("ORGANIZATION: 直接所属メンバーはtrue（配下判定を呼ばずに短絡）")
        void organization_直接所属はtrue() {
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(true);

            assertThat(accessControlService.isMemberOrDescendant(USER_ID, SCOPE_ID, "ORGANIZATION")).isTrue();
            // 直接所属で短絡するため配下判定は呼ばれない
            verifyNoInteractions(organizationMembershipService);
        }

        @Test
        @DisplayName("ORGANIZATION: 配下チームのみ所属MEMBERはtrue（応答母集団・純SUPPORTER除外版で救済）")
        void organization_配下チームのみ所属メンバーはtrue() {
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(false);
            given(organizationMembershipService.isActiveMemberInOrgDistributionUniverse(SCOPE_ID, USER_ID))
                    .willReturn(true);

            assertThat(accessControlService.isMemberOrDescendant(USER_ID, SCOPE_ID, "ORGANIZATION")).isTrue();
            // checkMembershipOrDescendant も例外なし
            accessControlService.checkMembershipOrDescendant(USER_ID, SCOPE_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("ORGANIZATION: 配下の純SUPPORTER（応答母集団に非該当）はfalse→checkで COMMON_002")
        void organization_配下純SUPPORTERはfalse() {
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(false);
            given(organizationMembershipService.isActiveMemberInOrgDistributionUniverse(SCOPE_ID, USER_ID))
                    .willReturn(false);

            assertThat(accessControlService.isMemberOrDescendant(USER_ID, SCOPE_ID, "ORGANIZATION")).isFalse();
            assertThatThrownBy(() ->
                    accessControlService.checkMembershipOrDescendant(USER_ID, SCOPE_ID, "ORGANIZATION"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("ORGANIZATION: 組織にも配下にも無関係なユーザーはfalse→checkで COMMON_002")
        void organization_無関係ユーザーはfalse() {
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(false);
            given(organizationMembershipService.isActiveMemberInOrgDistributionUniverse(SCOPE_ID, USER_ID))
                    .willReturn(false);

            assertThatThrownBy(() ->
                    accessControlService.checkMembershipOrDescendant(USER_ID, SCOPE_ID, "ORGANIZATION"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("TEAM: 挙動不変（配下概念を持ち込まない）— 直接所属はtrue・配下判定は呼ばない")
        void team_直接所属はtrue配下判定呼ばない() {
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(true);

            assertThat(accessControlService.isMemberOrDescendant(USER_ID, SCOPE_ID, "TEAM")).isTrue();
            verifyNoInteractions(organizationMembershipService);
        }

        @Test
        @DisplayName("TEAM: 非メンバーはfalse（配下フォールバックを使わない・回帰ガード）→checkで COMMON_002")
        void team_非メンバーはfalse配下フォールバックなし() {
            given(membershipRepository.existsActiveByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(false);

            assertThat(accessControlService.isMemberOrDescendant(USER_ID, SCOPE_ID, "TEAM")).isFalse();
            assertThatThrownBy(() ->
                    accessControlService.checkMembershipOrDescendant(USER_ID, SCOPE_ID, "TEAM"))
                    .isInstanceOf(BusinessException.class);
            // TEAM では配下判定（organization 越境窓口）を一切呼ばない
            verifyNoInteractions(organizationMembershipService);
        }
    }

    // ========================================
    // getRoleName
    // ========================================

    @Nested
    @DisplayName("getRoleName")
    class GetRoleName {

        @Test
        @DisplayName("正常系: TEAMスコープでロール名が返る")
        void getRoleName_TEAMスコープ_ロール名が返る() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            String roleName = accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(roleName).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープでロール名が返る")
        void getRoleName_ORGANIZATIONスコープ_ロール名が返る() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            String roleName = accessControlService.getRoleName(USER_ID, SCOPE_ID, "ORGANIZATION");

            // Then
            assertThat(roleName).isEqualTo("MEMBER");
        }

        @Test
        @DisplayName("正常系: メンバーでない場合はnull")
        void getRoleName_非メンバー_null() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When
            String roleName = accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(roleName).isNull();
        }
    }

    // ========================================
    // isAdminOrAbove
    // ========================================

    @Nested
    @DisplayName("isAdminOrAbove")
    class IsAdminOrAbove {

        @Test
        @DisplayName("正常系: ADMINロールでtrue")
        void isAdminOrAbove_ADMINロール_true() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: DEPUTY_ADMINロールでtrue")
        void isAdminOrAbove_DEPUTY_ADMINロール_true() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("DEPUTY_ADMIN", 2);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: MEMBERロールでfalse")
        void isAdminOrAbove_MEMBERロール_false() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("正常系: 非メンバーでfalse")
        void isAdminOrAbove_非メンバー_false() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When
            boolean result = accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // isAdmin
    // ========================================

    @Nested
    @DisplayName("isAdmin")
    class IsAdmin {

        @Test
        @DisplayName("正常系: ADMINロールでtrue")
        void isAdmin_ADMINロール_true() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdmin(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: DEPUTY_ADMINロールでfalse")
        void isAdmin_DEPUTY_ADMINロール_false() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("DEPUTY_ADMIN", 2);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When
            boolean result = accessControlService.isAdmin(USER_ID, SCOPE_ID, "TEAM");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // hasRoleOrAbove
    // ========================================

    @Nested
    @DisplayName("hasRoleOrAbove")
    class HasRoleOrAbove {

        @Test
        @DisplayName("正常系: ADMINがMEMBER以上を満たすのでtrue")
        void hasRoleOrAbove_ADMIN対MEMBER_true() {
            // Given
            Long adminRoleId = 1L;
            UserRoleEntity userRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(adminRoleId).teamId(SCOPE_ID).build();
            RoleEntity adminRole = RoleEntity.builder()
                    .id(adminRoleId).name("ADMIN").displayName("管理者").priority(1).isSystem(true).build();
            RoleEntity memberRole = RoleEntity.builder()
                    .id(3L).name("MEMBER").displayName("メンバー").priority(3).isSystem(true).build();

            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(adminRoleId)).willReturn(Optional.of(adminRole));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(memberRole));

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "MEMBER");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: MEMBERがADMIN以上を満たさないのでfalse")
        void hasRoleOrAbove_MEMBER対ADMIN_false() {
            // Given
            Long memberRoleId = 3L;
            UserRoleEntity userRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(memberRoleId).teamId(SCOPE_ID).build();
            RoleEntity memberRole = RoleEntity.builder()
                    .id(memberRoleId).name("MEMBER").displayName("メンバー").priority(3).isSystem(true).build();
            RoleEntity adminRole = RoleEntity.builder()
                    .id(1L).name("ADMIN").displayName("管理者").priority(1).isSystem(true).build();

            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(memberRoleId)).willReturn(Optional.of(memberRole));
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(adminRole));

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "ADMIN");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("境界値: 同一ロール同士でtrue")
        void hasRoleOrAbove_同一ロール_true() {
            // Given
            Long memberRoleId = 3L;
            UserRoleEntity userRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(memberRoleId).teamId(SCOPE_ID).build();
            RoleEntity memberRole = RoleEntity.builder()
                    .id(memberRoleId).name("MEMBER").displayName("メンバー").priority(3).isSystem(true).build();

            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(memberRoleId)).willReturn(Optional.of(memberRole));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(memberRole));

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "MEMBER");

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: 非メンバーでfalse")
        void hasRoleOrAbove_非メンバー_false() {
            // Given
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "MEMBER");

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("境界値: 要求ロールが存在しない場合はfalse")
        void hasRoleOrAbove_要求ロール不在_false() {
            // Given
            Long memberRoleId = 3L;
            UserRoleEntity userRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(memberRoleId).teamId(SCOPE_ID).build();
            RoleEntity memberRole = RoleEntity.builder()
                    .id(memberRoleId).name("MEMBER").displayName("メンバー").priority(3).isSystem(true).build();

            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(memberRoleId)).willReturn(Optional.of(memberRole));
            given(roleRepository.findByName("NONEXISTENT")).willReturn(Optional.empty());

            // When
            boolean result = accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "NONEXISTENT");

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // checkAdminOrAbove
    // ========================================

    @Nested
    @DisplayName("checkAdminOrAbove")
    class CheckAdminOrAbove {

        @Test
        @DisplayName("正常系: ADMINロールで例外なし")
        void checkAdminOrAbove_ADMINロール_例外なし() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then
            accessControlService.checkAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("異常系: MEMBERロールでCOMMON_002例外")
        void checkAdminOrAbove_MEMBERロール_COMMON002例外() {
            // Given
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkAdminOrAbove(USER_ID, SCOPE_ID, "TEAM"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    // ========================================
    // checkAdminOrHasPermission（F18 Phase 4 第二陣 2B）
    // ========================================

    @Nested
    @DisplayName("checkAdminOrHasPermission")
    class CheckAdminOrHasPermission {

        private static final String PERMISSION = "POINT_CARD_STAMP_ISSUE";

        private UserRoleEntity createOrgUserRole(Long roleId) {
            return UserRoleEntity.builder()
                    .id(1L)
                    .userId(USER_ID)
                    .roleId(roleId)
                    .organizationId(SCOPE_ID)
                    .build();
        }

        @Test
        @DisplayName("正常系: ADMIN は Permission を見ずに無条件許可")
        void checkAdminOrHasPermission_ADMIN_例外なし() {
            // Given: ADMIN ロール（isAdmin で判定される経路）
            UserRoleEntity userRole = createOrgUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then（例外なし）
            accessControlService.checkAdminOrHasPermission(USER_ID, SCOPE_ID, "ORGANIZATION", PERMISSION);
        }

        @Test
        @DisplayName("正常系: DEPUTY_ADMIN + Permission 保有で許可")
        void checkAdminOrHasPermission_DEPUTY_ADMINかつPermissionあり_例外なし() {
            // Given: DEPUTY_ADMIN（isAdmin=false） + リポジトリで Permission 保有あり
            UserRoleEntity userRole = createOrgUserRole(ROLE_ID);
            RoleEntity role = createRole("DEPUTY_ADMIN", 2);
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));
            given(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                    USER_ID, SCOPE_ID, PERMISSION)).willReturn(true);

            // When / Then（例外なし）
            accessControlService.checkAdminOrHasPermission(USER_ID, SCOPE_ID, "ORGANIZATION", PERMISSION);
        }

        @Test
        @DisplayName("異常系: DEPUTY_ADMIN だが Permission 保有なしで COMMON_002")
        void checkAdminOrHasPermission_DEPUTY_ADMINだがPermissionなし_COMMON002例外() {
            // Given: DEPUTY_ADMIN だがリポジトリで Permission 未保有
            UserRoleEntity userRole = createOrgUserRole(ROLE_ID);
            RoleEntity role = createRole("DEPUTY_ADMIN", 2);
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));
            given(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                    USER_ID, SCOPE_ID, PERMISSION)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkAdminOrHasPermission(
                    USER_ID, SCOPE_ID, "ORGANIZATION", PERMISSION))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("異常系: MEMBER は ADMIN でも DEPUTY_ADMIN でもないため COMMON_002")
        void checkAdminOrHasPermission_MEMBER_COMMON002例外() {
            // Given: MEMBER ロール
            UserRoleEntity userRole = createOrgUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));
            // DEPUTY_ADMIN 判定 SQL は false を返す（MEMBER なので当然）
            given(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                    USER_ID, SCOPE_ID, PERMISSION)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkAdminOrHasPermission(
                    USER_ID, SCOPE_ID, "ORGANIZATION", PERMISSION))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("異常系: 非メンバーで COMMON_002")
        void checkAdminOrHasPermission_非メンバー_COMMON002例外() {
            // Given: user_roles に行なし
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(userRoleRepository.existsDeputyAdminWithPermissionInOrganization(
                    USER_ID, SCOPE_ID, PERMISSION)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkAdminOrHasPermission(
                    USER_ID, SCOPE_ID, "ORGANIZATION", PERMISSION))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("異常系: TEAM スコープ指定で IllegalArgumentException")
        void checkAdminOrHasPermission_TEAMスコープ_IllegalArgumentException() {
            // When / Then
            assertThatThrownBy(() -> accessControlService.checkAdminOrHasPermission(
                    USER_ID, SCOPE_ID, "TEAM", PERMISSION))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ========================================
    // checkPermission
    // ========================================

    @Nested
    @DisplayName("checkPermission")
    class CheckPermission {

        @Test
        @DisplayName("正常系: 権限ありで例外なし")
        void checkPermission_権限あり_例外なし() {
            // Given
            given(roleService.hasPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE")).willReturn(true);

            // When / Then
            accessControlService.checkPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE");
            verify(roleService).hasPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE");
        }

        @Test
        @DisplayName("異常系: 権限なしでCOMMON_002例外")
        void checkPermission_権限なし_COMMON002例外() {
            // Given
            given(roleService.hasPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE")).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkPermission(USER_ID, SCOPE_ID, "TEAM", "BULLETIN_CREATE"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    // ========================================
    // checkOwnerOrAdmin
    // ========================================

    @Nested
    @DisplayName("checkOwnerOrAdmin")
    class CheckOwnerOrAdmin {

        @Test
        @DisplayName("正常系: 本人の場合は例外なし")
        void checkOwnerOrAdmin_本人_例外なし() {
            // Given
            Long resourceOwnerId = USER_ID;

            // When / Then（本人なのでロール判定は呼ばれない）
            accessControlService.checkOwnerOrAdmin(USER_ID, resourceOwnerId, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("正常系: 他人だがADMINの場合は例外なし")
        void checkOwnerOrAdmin_他人だがADMIN_例外なし() {
            // Given
            Long resourceOwnerId = 999L;
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("ADMIN", 1);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then
            accessControlService.checkOwnerOrAdmin(USER_ID, resourceOwnerId, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("異常系: 他人かつ非ADMINでCOMMON_002例外")
        void checkOwnerOrAdmin_他人かつ非ADMIN_COMMON002例外() {
            // Given
            Long resourceOwnerId = 999L;
            UserRoleEntity userRole = createUserRole(ROLE_ID);
            RoleEntity role = createRole("MEMBER", 3);
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(userRole));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));

            // When / Then
            assertThatThrownBy(() -> accessControlService.checkOwnerOrAdmin(USER_ID, resourceOwnerId, SCOPE_ID, "TEAM"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    // ========================================
    // F00.5 §8.3 根治: memberships 統合ロール解決
    // （user_roles から MEMBER/SUPPORTER 削除済みのため、所属ロールは memberships 由来）
    // ========================================

    @Nested
    @DisplayName("F00.5 §8.3 memberships 統合: getRoleName / hasRoleOrAbove / resolveEffectiveRoleName")
    class MembershipRoleResolution {

        private RoleEntity role(String name) {
            int priority = switch (name) {
                case "SYSTEM_ADMIN" -> 1;
                case "ADMIN" -> 2;
                case "DEPUTY_ADMIN" -> 3;
                case "MEMBER" -> 4;
                case "SUPPORTER" -> 5;
                case "GUEST" -> 6;
                default -> Integer.MAX_VALUE;
            };
            return RoleEntity.builder()
                    .id((long) priority)
                    .name(name)
                    .displayName(name)
                    .priority(priority)
                    .isSystem(true)
                    .build();
        }

        @Test
        @DisplayName("memberships 専属 MEMBER → getRoleName=MEMBER / hasRoleOrAbove(MEMBER)=true")
        void memberships専属MEMBER() {
            // Given: user_roles には行が無い（V60.010 で MEMBER 行は削除済み）
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());
            // memberships に MEMBER の active 行がある
            given(membershipRepository.findActiveRoleKinds(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(List.of(RoleKind.MEMBER));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(role("MEMBER")));

            // When / Then
            assertThat(accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM")).isEqualTo("MEMBER");
            assertThat(accessControlService.resolveEffectiveRoleName(USER_ID, SCOPE_ID, "TEAM")).isEqualTo("MEMBER");
            assertThat(accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("memberships 専属 SUPPORTER → getRoleName=SUPPORTER / hasRoleOrAbove(SUPPORTER)=true・(MEMBER)=false")
        void memberships専属SUPPORTER() {
            // Given: user_roles に行なし（V60.010 で SUPPORTER 行も削除済み）
            given(userRoleRepository.findByUserIdAndOrganizationId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(membershipRepository.findActiveRoleKinds(USER_ID, ScopeType.ORGANIZATION, SCOPE_ID))
                    .willReturn(List.of(RoleKind.SUPPORTER));
            given(roleRepository.findByName("SUPPORTER")).willReturn(Optional.of(role("SUPPORTER")));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(role("MEMBER")));

            // When / Then
            assertThat(accessControlService.getRoleName(USER_ID, SCOPE_ID, "ORGANIZATION")).isEqualTo("SUPPORTER");
            // SUPPORTER(5) <= SUPPORTER(5) → true
            assertThat(accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION", "SUPPORTER")).isTrue();
            // SUPPORTER(5) <= MEMBER(4)? → false（MEMBERS_AND_ABOVE は不可視）
            assertThat(accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION", "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("ADMIN(user_roles) + MEMBER(memberships) 併存 → priority 最強の ADMIN を採用")
        void adminUserRoleとmembershipMember併存_ADMIN優先() {
            // Given: user_roles に ADMIN 行（権限ロールは user_roles に残置）
            UserRoleEntity adminUserRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(2L).teamId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(adminUserRole));
            given(roleRepository.findById(2L)).willReturn(Optional.of(role("ADMIN")));
            // memberships にも MEMBER 行
            given(membershipRepository.findActiveRoleKinds(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(List.of(RoleKind.MEMBER));
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(role("MEMBER")));

            // When / Then: ADMIN(2) < MEMBER(4) なので ADMIN を採用
            assertThat(accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM")).isEqualTo("ADMIN");
            assertThat(accessControlService.isAdmin(USER_ID, SCOPE_ID, "TEAM")).isTrue();
            assertThat(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).isTrue();
        }

        @Test
        @DisplayName("回帰防止: ADMIN(user_roles) のみ・memberships 無し → ADMIN 系は不変")
        void 回帰_ADMINのみ_不変() {
            UserRoleEntity adminUserRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(2L).teamId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(adminUserRole));
            given(roleRepository.findById(2L)).willReturn(Optional.of(role("ADMIN")));
            // memberships は空（findActiveRoleKinds 未スタブ → 空リスト）

            assertThat(accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM")).isEqualTo("ADMIN");
            assertThat(accessControlService.isAdmin(USER_ID, SCOPE_ID, "TEAM")).isTrue();
            assertThat(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).isTrue();
        }

        @Test
        @DisplayName("回帰防止: DEPUTY_ADMIN(user_roles) のみ → isAdmin=false・isAdminOrAbove=true")
        void 回帰_DEPUTY_ADMINのみ_不変() {
            UserRoleEntity deputyUserRole = UserRoleEntity.builder()
                    .id(1L).userId(USER_ID).roleId(3L).teamId(SCOPE_ID).build();
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(deputyUserRole));
            given(roleRepository.findById(3L)).willReturn(Optional.of(role("DEPUTY_ADMIN")));

            assertThat(accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM")).isEqualTo("DEPUTY_ADMIN");
            assertThat(accessControlService.isAdmin(USER_ID, SCOPE_ID, "TEAM")).isFalse();
            assertThat(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).isTrue();
        }

        @Test
        @DisplayName("user_roles も memberships も無し → getRoleName=null・hasRoleOrAbove=false")
        void 所属なし_null() {
            given(userRoleRepository.findByUserIdAndTeamId(USER_ID, SCOPE_ID))
                    .willReturn(Optional.empty());
            // memberships 未スタブ → 空リスト

            assertThat(accessControlService.getRoleName(USER_ID, SCOPE_ID, "TEAM")).isNull();
            assertThat(accessControlService.resolveEffectiveRoleName(USER_ID, SCOPE_ID, "TEAM")).isNull();
            assertThat(accessControlService.hasRoleOrAbove(USER_ID, SCOPE_ID, "TEAM", "SUPPORTER")).isFalse();
        }
    }
}

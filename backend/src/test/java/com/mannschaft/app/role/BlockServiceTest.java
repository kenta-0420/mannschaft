package com.mannschaft.app.role;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationBlockEntity;
import com.mannschaft.app.organization.repository.OrganizationBlockRepository;
import com.mannschaft.app.role.dto.BlockRequest;
import com.mannschaft.app.role.dto.BlockResponse;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.BlockService;
import com.mannschaft.app.team.entity.TeamBlockEntity;
import com.mannschaft.app.team.repository.TeamBlockRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BlockService} の単体テスト。
 * チーム・組織レベルでのユーザーブロック/解除/一覧取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlockService 単体テスト")
class BlockServiceTest {

    @Mock
    private TeamBlockRepository teamBlockRepository;

    @Mock
    private OrganizationBlockRepository organizationBlockRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private BlockService blockService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long ADMIN_USER_ID = 1L;
    private static final Long TARGET_USER_ID = 2L;
    private static final Long ADMIN_ROLE_ID = 100L;
    private static final Long MEMBER_ROLE_ID = 200L;
    private static final String BLOCK_REASON = "規約違反";

    /** ADMIN ロール (priority=10 — 低い値ほど上位) */
    private RoleEntity createAdminRole() {
        return RoleEntity.builder()
                .name("ADMIN")
                .displayName("管理者")
                .priority(10)
                .isSystem(true)
                .build();
    }

    /** MEMBER ロール (priority=40 — 高い値ほど下位) */
    private RoleEntity createMemberRole() {
        return RoleEntity.builder()
                .name("MEMBER")
                .displayName("メンバー")
                .priority(40)
                .isSystem(true)
                .build();
    }

    private UserRoleEntity createUserRole(Long userId, Long roleId, Long teamId, Long orgId) {
        return UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .teamId(teamId)
                .organizationId(orgId)
                .build();
    }

    private UserEntity createUser(Long id, String displayName) {
        return UserEntity.builder()
                .email(displayName + "@example.com")
                .passwordHash("$2a$12$hash")
                .lastName("テスト")
                .firstName(displayName)
                .lastNameKana("テスト")
                .firstNameKana(displayName)
                .displayName(displayName)
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
    }

    private BlockRequest createBlockRequest() {
        return new BlockRequest(TARGET_USER_ID, BLOCK_REASON);
    }

    /**
     * blockUser で共通のロール階層モック設定。
     * blocker が上位ロール(ADMIN, priority=10)、target が下位ロール(MEMBER, priority=40)。
     */
    private void stubRoleHierarchy_blockerIsHigher(String scopeType) {
        RoleEntity adminRole = createAdminRole();
        RoleEntity memberRole = createMemberRole();

        if ("TEAM".equals(scopeType)) {
            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(createUserRole(TARGET_USER_ID, MEMBER_ROLE_ID, TEAM_ID, null)));
            given(userRoleRepository.findByUserIdAndTeamId(ADMIN_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(createUserRole(ADMIN_USER_ID, ADMIN_ROLE_ID, TEAM_ID, null)));
        } else {
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, ORG_ID))
                    .willReturn(Optional.of(createUserRole(TARGET_USER_ID, MEMBER_ROLE_ID, null, ORG_ID)));
            given(userRoleRepository.findByUserIdAndOrganizationId(ADMIN_USER_ID, ORG_ID))
                    .willReturn(Optional.of(createUserRole(ADMIN_USER_ID, ADMIN_ROLE_ID, null, ORG_ID)));
        }

        given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(adminRole));
        given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(memberRole));
    }

    // ========================================
    // blockUser
    // ========================================

    @Nested
    @DisplayName("blockUser")
    class BlockUser {

        @Test
        @DisplayName("正常系: チームスコープでブロック成功しUserRoleが自動削除される")
        void blockUser_チームスコープ_正常ブロック() {
            // Given
            BlockRequest req = createBlockRequest();
            stubRoleHierarchy_blockerIsHigher("TEAM");

            UserRoleEntity targetUserRole = createUserRole(TARGET_USER_ID, MEMBER_ROLE_ID, TEAM_ID, null);
            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(targetUserRole));

            given(teamBlockRepository.save(any(TeamBlockEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(userRepository.findById(TARGET_USER_ID))
                    .willReturn(Optional.of(createUser(TARGET_USER_ID, "target")));
            given(userRepository.findById(ADMIN_USER_ID))
                    .willReturn(Optional.of(createUser(ADMIN_USER_ID, "admin")));

            // When
            ApiResponse<BlockResponse> response = blockService.blockUser(TEAM_ID, "TEAM", req, ADMIN_USER_ID);

            // Then
            assertThat(response.getData()).isNotNull();
            assertThat(response.getData().getUserId()).isEqualTo(TARGET_USER_ID);
            assertThat(response.getData().getReason()).isEqualTo(BLOCK_REASON);
            verify(teamBlockRepository).save(any(TeamBlockEntity.class));
            verify(userRoleRepository).delete(targetUserRole);
        }

        @Test
        @DisplayName("正常系: 組織スコープでブロック成功しUserRoleが自動削除される")
        void blockUser_組織スコープ_正常ブロック() {
            // Given
            BlockRequest req = createBlockRequest();
            stubRoleHierarchy_blockerIsHigher("ORGANIZATION");

            UserRoleEntity targetUserRole = createUserRole(TARGET_USER_ID, MEMBER_ROLE_ID, null, ORG_ID);
            given(userRoleRepository.findByUserIdAndOrganizationId(TARGET_USER_ID, ORG_ID))
                    .willReturn(Optional.of(targetUserRole));

            given(organizationBlockRepository.save(any(OrganizationBlockEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(userRepository.findById(TARGET_USER_ID))
                    .willReturn(Optional.of(createUser(TARGET_USER_ID, "target")));
            given(userRepository.findById(ADMIN_USER_ID))
                    .willReturn(Optional.of(createUser(ADMIN_USER_ID, "admin")));

            // When
            ApiResponse<BlockResponse> response = blockService.blockUser(ORG_ID, "ORGANIZATION", req, ADMIN_USER_ID);

            // Then
            assertThat(response.getData()).isNotNull();
            assertThat(response.getData().getUserId()).isEqualTo(TARGET_USER_ID);
            assertThat(response.getData().getReason()).isEqualTo(BLOCK_REASON);
            verify(organizationBlockRepository).save(any(OrganizationBlockEntity.class));
            verify(userRoleRepository).delete(targetUserRole);
        }

        @Test
        @DisplayName("異常系: 上位ロールのユーザーをブロックしようとするとROLE_005例外")
        void blockUser_上位ロールブロック拒否_ROLE005例外() {
            // Given — target が ADMIN(priority=10)、blocker が MEMBER(priority=40)
            BlockRequest req = createBlockRequest();
            RoleEntity adminRole = createAdminRole();
            RoleEntity memberRole = createMemberRole();

            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(createUserRole(TARGET_USER_ID, ADMIN_ROLE_ID, TEAM_ID, null)));
            given(userRoleRepository.findByUserIdAndTeamId(ADMIN_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(createUserRole(ADMIN_USER_ID, MEMBER_ROLE_ID, TEAM_ID, null)));
            given(roleRepository.findById(ADMIN_ROLE_ID)).willReturn(Optional.of(adminRole));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(memberRole));

            // When / Then
            assertThatThrownBy(() -> blockService.blockUser(TEAM_ID, "TEAM", req, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_005"));

            verify(teamBlockRepository, never()).save(any());
            verify(userRoleRepository, never()).delete(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("異常系: 同一ロールレベルのユーザーをブロックしようとするとROLE_005例外")
        void blockUser_同一ロールレベル_ROLE005例外() {
            // Given — 両者とも MEMBER(priority=40)
            BlockRequest req = createBlockRequest();
            RoleEntity memberRole = createMemberRole();

            given(userRoleRepository.findByUserIdAndTeamId(TARGET_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(createUserRole(TARGET_USER_ID, MEMBER_ROLE_ID, TEAM_ID, null)));
            given(userRoleRepository.findByUserIdAndTeamId(ADMIN_USER_ID, TEAM_ID))
                    .willReturn(Optional.of(createUserRole(ADMIN_USER_ID, MEMBER_ROLE_ID, TEAM_ID, null)));
            given(roleRepository.findById(MEMBER_ROLE_ID)).willReturn(Optional.of(memberRole));

            // When / Then
            assertThatThrownBy(() -> blockService.blockUser(TEAM_ID, "TEAM", req, ADMIN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_005"));
        }
    }

    // ========================================
    // unblockUser
    // ========================================

    @Nested
    @DisplayName("unblockUser")
    class UnblockUser {

        @Test
        @DisplayName("正常系: チームスコープでブロック解除される")
        void unblockUser_チームスコープ_正常解除() {
            // Given
            TeamBlockEntity block = TeamBlockEntity.builder()
                    .teamId(TEAM_ID)
                    .userId(TARGET_USER_ID)
                    .blockedBy(ADMIN_USER_ID)
                    .reason(BLOCK_REASON)
                    .build();
            given(teamBlockRepository.findByTeamIdAndUserId(TEAM_ID, TARGET_USER_ID))
                    .willReturn(Optional.of(block));

            // When
            blockService.unblockUser(TEAM_ID, "TEAM", TARGET_USER_ID);

            // Then
            verify(teamBlockRepository).delete(block);
        }

        @Test
        @DisplayName("正常系: 組織スコープでブロック解除される")
        void unblockUser_組織スコープ_正常解除() {
            // Given
            OrganizationBlockEntity block = OrganizationBlockEntity.builder()
                    .organizationId(ORG_ID)
                    .userId(TARGET_USER_ID)
                    .blockedBy(ADMIN_USER_ID)
                    .reason(BLOCK_REASON)
                    .build();
            given(organizationBlockRepository.findByOrganizationIdAndUserId(ORG_ID, TARGET_USER_ID))
                    .willReturn(Optional.of(block));

            // When
            blockService.unblockUser(ORG_ID, "ORGANIZATION", TARGET_USER_ID);

            // Then
            verify(organizationBlockRepository).delete(block);
        }

        @Test
        @DisplayName("正常系: ブロックが存在しない場合は何もしない")
        void unblockUser_ブロック未存在_何もしない() {
            // Given
            given(teamBlockRepository.findByTeamIdAndUserId(TEAM_ID, TARGET_USER_ID))
                    .willReturn(Optional.empty());

            // When
            blockService.unblockUser(TEAM_ID, "TEAM", TARGET_USER_ID);

            // Then
            verify(teamBlockRepository, never()).delete(any(TeamBlockEntity.class));
        }
    }

    // ========================================
    // getBlocks
    // ========================================

    @Nested
    @DisplayName("getBlocks")
    class GetBlocks {

        @Test
        @DisplayName("正常系: チームスコープのブロック一覧を取得する")
        void getBlocks_チームスコープ_一覧取得() {
            // Given
            TeamBlockEntity block1 = TeamBlockEntity.builder()
                    .teamId(TEAM_ID)
                    .userId(TARGET_USER_ID)
                    .blockedBy(ADMIN_USER_ID)
                    .reason("理由1")
                    .build();
            TeamBlockEntity block2 = TeamBlockEntity.builder()
                    .teamId(TEAM_ID)
                    .userId(3L)
                    .blockedBy(ADMIN_USER_ID)
                    .reason("理由2")
                    .build();

            given(teamBlockRepository.findByTeamId(TEAM_ID)).willReturn(List.of(block1, block2));
            given(userRepository.findById(TARGET_USER_ID))
                    .willReturn(Optional.of(createUser(TARGET_USER_ID, "target")));
            given(userRepository.findById(3L))
                    .willReturn(Optional.of(createUser(3L, "user3")));
            given(userRepository.findById(ADMIN_USER_ID))
                    .willReturn(Optional.of(createUser(ADMIN_USER_ID, "admin")));

            // When
            List<BlockResponse> result = blockService.getBlocks(TEAM_ID, "TEAM");

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getUserId()).isEqualTo(TARGET_USER_ID);
            assertThat(result.get(0).getReason()).isEqualTo("理由1");
            assertThat(result.get(1).getUserId()).isEqualTo(3L);
            assertThat(result.get(1).getReason()).isEqualTo("理由2");
        }

        @Test
        @DisplayName("正常系: 組織スコープのブロック一覧を取得する")
        void getBlocks_組織スコープ_一覧取得() {
            // Given
            OrganizationBlockEntity block = OrganizationBlockEntity.builder()
                    .organizationId(ORG_ID)
                    .userId(TARGET_USER_ID)
                    .blockedBy(ADMIN_USER_ID)
                    .reason(BLOCK_REASON)
                    .build();

            given(organizationBlockRepository.findByOrganizationId(ORG_ID)).willReturn(List.of(block));
            given(userRepository.findById(TARGET_USER_ID))
                    .willReturn(Optional.of(createUser(TARGET_USER_ID, "target")));
            given(userRepository.findById(ADMIN_USER_ID))
                    .willReturn(Optional.of(createUser(ADMIN_USER_ID, "admin")));

            // When
            List<BlockResponse> result = blockService.getBlocks(ORG_ID, "ORGANIZATION");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo(TARGET_USER_ID);
            assertThat(result.get(0).getDisplayName()).isEqualTo("target");
            assertThat(result.get(0).getBlockedByName()).isEqualTo("admin");
            assertThat(result.get(0).getReason()).isEqualTo(BLOCK_REASON);
        }

        @Test
        @DisplayName("正常系: ブロックが存在しない場合は空リストを返す")
        void getBlocks_ブロック未存在_空リスト() {
            // Given
            given(teamBlockRepository.findByTeamId(TEAM_ID)).willReturn(List.of());

            // When
            List<BlockResponse> result = blockService.getBlocks(TEAM_ID, "TEAM");

            // Then
            assertThat(result).isEmpty();
        }
    }
}

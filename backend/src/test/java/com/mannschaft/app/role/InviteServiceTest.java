package com.mannschaft.app.role;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.dto.CreateInviteTokenRequest;
import com.mannschaft.app.role.dto.InvitePreviewResponse;
import com.mannschaft.app.role.dto.InviteTokenResponse;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.InviteService;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link InviteService} の単体テスト。
 * 招待トークンの作成・一覧取得・失効・プレビュー・参加を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteService 単体テスト")
class InviteServiceTest {

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamBlockRepository teamBlockRepository;

    @InjectMocks
    private InviteService inviteService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long ROLE_ID = 3L;
    private static final Long USER_ID = 1L;
    private static final Long CREATED_BY = 2L;
    private static final Long TOKEN_ID = 100L;
    private static final String TOKEN_STR = "test-token-uuid";
    private static final String ROLE_NAME = "MEMBER";

    private RoleEntity createRole() {
        return RoleEntity.builder()
                .id(ROLE_ID)
                .name(ROLE_NAME)
                .displayName("メンバー")
                .priority(4)
                .isSystem(true)
                .build();
    }

    private InviteTokenEntity createTeamInviteToken() {
        return InviteTokenEntity.builder()
                .id(TOKEN_ID)
                .token(TOKEN_STR)
                .teamId(TEAM_ID)
                .roleId(ROLE_ID)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .maxUses(10)
                .usedCount(0)
                .createdBy(CREATED_BY)
                .build();
    }

    private InviteTokenEntity createOrgInviteToken() {
        return InviteTokenEntity.builder()
                .id(TOKEN_ID)
                .token(TOKEN_STR)
                .organizationId(ORG_ID)
                .roleId(ROLE_ID)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .maxUses(10)
                .usedCount(0)
                .createdBy(CREATED_BY)
                .build();
    }

    private InviteTokenEntity createMaxedOutToken() {
        return InviteTokenEntity.builder()
                .id(TOKEN_ID)
                .token(TOKEN_STR)
                .teamId(TEAM_ID)
                .roleId(ROLE_ID)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .maxUses(5)
                .usedCount(5)
                .createdBy(CREATED_BY)
                .build();
    }

    // ========================================
    // createInviteToken
    // ========================================

    @Nested
    @DisplayName("createInviteToken")
    class CreateInviteToken {

        @Test
        @DisplayName("正常系: 招待トークンが作成される")
        void 作成_正常_トークン返却() {
            // Given
            CreateInviteTokenRequest req = new CreateInviteTokenRequest(ROLE_ID, "7d", 10);
            RoleEntity role = createRole();
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(role));
            given(inviteTokenRepository.save(any(InviteTokenEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<InviteTokenResponse> response =
                    inviteService.createInviteToken(TEAM_ID, "TEAM", req, CREATED_BY);

            // Then
            InviteTokenResponse data = response.getData();
            assertThat(data.getRoleName()).isEqualTo(ROLE_NAME);
            assertThat(data.getMaxUses()).isEqualTo(10);
            assertThat(data.getUsedCount()).isZero();
            assertThat(data.getToken()).isNotNull();
            verify(inviteTokenRepository).save(any(InviteTokenEntity.class));
        }

        @Test
        @DisplayName("異常系: ロール不在でROLE_001例外")
        void 作成_ロール不在_ROLE001例外() {
            // Given
            CreateInviteTokenRequest req = new CreateInviteTokenRequest(ROLE_ID, "7d", 10);
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> inviteService.createInviteToken(TEAM_ID, "TEAM", req, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_001"));
        }
    }

    // ========================================
    // getInviteTokens
    // ========================================

    @Nested
    @DisplayName("getInviteTokens")
    class GetInviteTokens {

        @Test
        @DisplayName("正常系: チームスコープのトークン一覧取得")
        void 取得_チームスコープ_一覧返却() {
            // Given
            InviteTokenEntity token = createTeamInviteToken();
            given(inviteTokenRepository.findByTeamIdAndRevokedAtIsNull(TEAM_ID))
                    .willReturn(List.of(token));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(createRole()));

            // When
            List<InviteTokenResponse> result = inviteService.getInviteTokens(TEAM_ID, "TEAM");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getToken()).isEqualTo(TOKEN_STR);
            assertThat(result.get(0).getRoleName()).isEqualTo(ROLE_NAME);
        }

        @Test
        @DisplayName("正常系: 組織スコープのトークン一覧取得")
        void 取得_組織スコープ_一覧返却() {
            // Given
            InviteTokenEntity token = createOrgInviteToken();
            given(inviteTokenRepository.findByOrganizationIdAndRevokedAtIsNull(ORG_ID))
                    .willReturn(List.of(token));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(createRole()));

            // When
            List<InviteTokenResponse> result = inviteService.getInviteTokens(ORG_ID, "ORGANIZATION");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getToken()).isEqualTo(TOKEN_STR);
            assertThat(result.get(0).getRoleName()).isEqualTo(ROLE_NAME);
        }
    }

    // ========================================
    // revokeInviteToken
    // ========================================

    @Nested
    @DisplayName("revokeInviteToken")
    class RevokeInviteToken {

        @Test
        @DisplayName("正常系: トークンが失効される")
        void 失効_正常_revokedAt設定() {
            // Given
            InviteTokenEntity token = createTeamInviteToken();
            given(inviteTokenRepository.findById(TOKEN_ID)).willReturn(Optional.of(token));

            // When
            inviteService.revokeInviteToken(TOKEN_ID);

            // Then
            assertThat(token.getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: トークン不在でROLE_002例外")
        void 失効_トークン不在_ROLE002例外() {
            // Given
            given(inviteTokenRepository.findById(TOKEN_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> inviteService.revokeInviteToken(TOKEN_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_002"));
        }
    }

    // ========================================
    // previewInvite
    // ========================================

    @Nested
    @DisplayName("previewInvite")
    class PreviewInvite {

        @Test
        @DisplayName("正常系: チーム招待のプレビューが返却される")
        void プレビュー_正常_チーム招待情報返却() {
            // Given
            InviteTokenEntity token = createTeamInviteToken();
            given(inviteTokenRepository.findByToken(TOKEN_STR)).willReturn(Optional.of(token));
            given(roleRepository.findById(ROLE_ID)).willReturn(Optional.of(createRole()));
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(
                    TeamEntity.builder().name("テストチーム").build()));

            // When
            ApiResponse<InvitePreviewResponse> response = inviteService.previewInvite(TOKEN_STR);

            // Then
            InvitePreviewResponse data = response.getData();
            assertThat(data.getTargetName()).isEqualTo("テストチーム");
            assertThat(data.getTargetType()).isEqualTo("TEAM");
            assertThat(data.getRoleName()).isEqualTo(ROLE_NAME);
            assertThat(data.isValid()).isTrue();
        }

        @Test
        @DisplayName("異常系: トークン不在でROLE_002例外")
        void プレビュー_トークン不在_ROLE002例外() {
            // Given
            given(inviteTokenRepository.findByToken(TOKEN_STR)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> inviteService.previewInvite(TOKEN_STR))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_002"));
        }
    }

    // ========================================
    // joinByInvite
    // ========================================

    @Nested
    @DisplayName("joinByInvite")
    class JoinByInvite {

        @Test
        @DisplayName("正常系: チームに参加しロールが割り当てられる")
        void 参加_正常_ロール割当() {
            // Given
            InviteTokenEntity token = createTeamInviteToken();
            given(inviteTokenRepository.findByTokenForUpdate(TOKEN_STR)).willReturn(Optional.of(token));
            given(teamBlockRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(false);
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(false);
            given(userRoleRepository.save(any(UserRoleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            inviteService.joinByInvite(TOKEN_STR, USER_ID);

            // Then
            verify(userRoleRepository).save(any(UserRoleEntity.class));
            assertThat(token.getUsedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系: トークン不在でROLE_002例外")
        void 参加_トークン不在_ROLE002例外() {
            // Given
            given(inviteTokenRepository.findByTokenForUpdate(TOKEN_STR)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> inviteService.joinByInvite(TOKEN_STR, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_002"));
        }

        @Test
        @DisplayName("異常系: 使用回数上限でROLE_003例外")
        void 参加_使用回数上限_ROLE003例外() {
            // Given
            InviteTokenEntity token = createMaxedOutToken();
            given(inviteTokenRepository.findByTokenForUpdate(TOKEN_STR)).willReturn(Optional.of(token));

            // When / Then
            assertThatThrownBy(() -> inviteService.joinByInvite(TOKEN_STR, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ROLE_003"));
        }

        @Test
        @DisplayName("異常系: 既に参加済みでTEAM_003例外")
        void 参加_既参加_TEAM003例外() {
            // Given
            InviteTokenEntity token = createTeamInviteToken();
            given(inviteTokenRepository.findByTokenForUpdate(TOKEN_STR)).willReturn(Optional.of(token));
            given(teamBlockRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(false);
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> inviteService.joinByInvite(TOKEN_STR, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_003"));
        }

        @Test
        @DisplayName("異常系: ブロック済みユーザーでTEAM_004例外")
        void 参加_ブロック済み_TEAM004例外() {
            // Given
            InviteTokenEntity token = createTeamInviteToken();
            given(inviteTokenRepository.findByTokenForUpdate(TOKEN_STR)).willReturn(Optional.of(token));
            given(teamBlockRepository.existsByTeamIdAndUserId(TEAM_ID, USER_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> inviteService.joinByInvite(TOKEN_STR, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_004"));
        }
    }
}

package com.mannschaft.app.organization;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.dto.OrganizationResponse;
import com.mannschaft.app.organization.dto.OrganizationSummaryResponse;
import com.mannschaft.app.organization.dto.UpdateOrganizationRequest;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link OrganizationService} の単体テスト。
 * 組織のCRUD・アーカイブ・フォロー・メンバー一覧を検証する。
 *
 * <p>F00.5 Phase 3: getMembers() は MemberQueryDispatcher 経由で memberships 参照に切替済み。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationService 単体テスト")
class OrganizationServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 10L;
    private static final Long ADMIN_ROLE_ID = 100L;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Mock
    private MemberQueryDispatcher memberQueryDispatcher;

    @InjectMocks
    private OrganizationService organizationService;

    // ========================================
    // createOrganization
    // ========================================

    @Nested
    @DisplayName("createOrganization")
    class CreateOrganization {

        @Test
        @DisplayName("正常作成_組織とADMINロールが保存される")
        void 正常作成_組織とADMINロールが保存される() {
            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "テスト組織", "SCHOOL", "東京都", "渋谷区", "PUBLIC", null);

            given(organizationRepository.existsByName("テスト組織")).willReturn(false);

            RoleEntity adminRole = RoleEntity.builder()
                    .id(ADMIN_ROLE_ID).name("ADMIN").displayName("管理者").priority(2).isSystem(true).build();
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(adminRole));

            given(organizationRepository.save(any(OrganizationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ApiResponse<OrganizationResponse> response =
                    organizationService.createOrganization(USER_ID, req);

            assertThat(response.getData().getName()).isEqualTo("テスト組織");
            assertThat(response.getData().getOrgType()).isEqualTo("SCHOOL");
            assertThat(response.getData().getVisibility()).isEqualTo("PUBLIC");
            verify(organizationRepository).save(any(OrganizationEntity.class));
            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("組織名重複_ORG_002例外")
        void 組織名重複_ORG_002例外() {
            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "既存組織", "SCHOOL", null, null, null, null);

            given(organizationRepository.existsByName("既存組織")).willReturn(true);

            assertThatThrownBy(() -> organizationService.createOrganization(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_002"));
        }

        @Test
        @DisplayName("ADMINロール未定義_ORG_005例外")
        void ADMINロール未定義_ORG_005例外() {
            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "新組織", "COMPANY", null, null, null, null);

            given(organizationRepository.existsByName("新組織")).willReturn(false);
            given(organizationRepository.save(any(OrganizationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.createOrganization(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_005"));
        }

        @Test
        @DisplayName("visibility省略時_PRIVATEがデフォルト")
        void visibility省略時_PRIVATEがデフォルト() {
            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "非公開組織", "NPO", null, null, null, null);

            given(organizationRepository.existsByName("非公開組織")).willReturn(false);

            RoleEntity adminRole = RoleEntity.builder()
                    .id(ADMIN_ROLE_ID).name("ADMIN").displayName("管理者").priority(2).isSystem(true).build();
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(adminRole));

            given(organizationRepository.save(any(OrganizationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ApiResponse<OrganizationResponse> response =
                    organizationService.createOrganization(USER_ID, req);

            assertThat(response.getData().getVisibility()).isEqualTo("PRIVATE");
        }

        @Test
        @DisplayName("正常系: parentOrganizationId付きで作成される")
        void parentOrganizationId付き_正常作成() {
            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "子組織", "SCHOOL", "東京都", "渋谷区", "PUBLIC", 5L);

            given(organizationRepository.existsByName("子組織")).willReturn(false);

            RoleEntity adminRole = RoleEntity.builder()
                    .id(ADMIN_ROLE_ID).name("ADMIN").displayName("管理者").priority(2).isSystem(true).build();
            given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(adminRole));

            given(organizationRepository.save(any(OrganizationEntity.class)))
                    .willAnswer(inv -> {
                        OrganizationEntity saved = inv.getArgument(0);
                        assertThat(saved.getParentOrganizationId()).isEqualTo(5L);
                        return saved;
                    });

            ApiResponse<OrganizationResponse> response =
                    organizationService.createOrganization(USER_ID, req);

            assertThat(response.getData().getName()).isEqualTo("子組織");
        }
    }

    // ========================================
    // getOrganization
    // ========================================

    @Nested
    @DisplayName("getOrganization")
    class GetOrganization {

        @Test
        @DisplayName("正常取得_組織情報が返される")
        void 正常取得_組織情報が返される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(userRoleRepository.countByOrganizationId(ORG_ID)).willReturn(5L);

            ApiResponse<OrganizationResponse> response =
                    organizationService.getOrganization(ORG_ID);

            assertThat(response.getData().getName()).isEqualTo("テスト組織");
            assertThat(response.getData().getMemberCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.getOrganization(999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    // ========================================
    // updateOrganization
    // ========================================

    @Nested
    @DisplayName("updateOrganization")
    class UpdateOrganization {

        @Test
        @DisplayName("正常更新_指定フィールドのみ更新される")
        void 正常更新_指定フィールドのみ更新される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(organizationRepository.save(any(OrganizationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(userRoleRepository.countByOrganizationId(ORG_ID)).willReturn(3L);

            UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                    "更新後の名前", null, null, null, "大阪府", null, null, null, null, 0L);

            ApiResponse<OrganizationResponse> response =
                    organizationService.updateOrganization(ORG_ID, req);

            assertThat(response.getData().getName()).isEqualTo("更新後の名前");
            assertThat(response.getData().getPrefecture()).isEqualTo("大阪府");
            // 未指定フィールドは元のまま
            assertThat(response.getData().getOrgType()).isEqualTo("SCHOOL");
        }

        @Test
        @DisplayName("アーカイブ済み組織_ORG_003例外")
        void アーカイブ済み組織_ORG_003例外() {
            OrganizationEntity org = createArchivedOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                    "更新", null, null, null, null, null, null, null, null, 0L);

            assertThatThrownBy(() -> organizationService.updateOrganization(ORG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_003"));
        }

        @Test
        @DisplayName("正常系: 組織不在でORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                    "更新", null, null, null, null, null, null, null, null, 0L);

            assertThatThrownBy(() -> organizationService.updateOrganization(999L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }

        @Test
        @DisplayName("正常系: 全フィールド更新")
        void 全フィールド更新() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(organizationRepository.save(any(OrganizationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(userRoleRepository.countByOrganizationId(ORG_ID)).willReturn(3L);

            UpdateOrganizationRequest req = new UpdateOrganizationRequest(
                    "新名前", "しんなまえ", "ニックネーム1", "ニックネーム2",
                    "大阪府", "大阪市", "PUBLIC", "FULL", true, 0L);

            ApiResponse<OrganizationResponse> response =
                    organizationService.updateOrganization(ORG_ID, req);

            assertThat(response.getData().getName()).isEqualTo("新名前");
            assertThat(response.getData().getNameKana()).isEqualTo("しんなまえ");
            assertThat(response.getData().getNickname1()).isEqualTo("ニックネーム1");
            assertThat(response.getData().getNickname2()).isEqualTo("ニックネーム2");
            assertThat(response.getData().getPrefecture()).isEqualTo("大阪府");
            assertThat(response.getData().getCity()).isEqualTo("大阪市");
            assertThat(response.getData().getSupporterEnabled()).isTrue();
        }
    }

    // ========================================
    // deleteOrganization
    // ========================================

    @Nested
    @DisplayName("deleteOrganization")
    class DeleteOrganization {

        @Test
        @DisplayName("正常削除_論理削除と招待トークン失効が実行される")
        void 正常削除_論理削除と招待トークン失効が実行される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            InviteTokenEntity token = InviteTokenEntity.builder()
                    .id(1L).token("abc").organizationId(ORG_ID).roleId(1L).usedCount(0).build();
            given(inviteTokenRepository.findByOrganizationIdAndRevokedAtIsNull(ORG_ID))
                    .willReturn(List.of(token));

            organizationService.deleteOrganization(ORG_ID);

            assertThat(org.getDeletedAt()).isNotNull();
            assertThat(token.getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.deleteOrganization(999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }

        @Test
        @DisplayName("正常系: 招待トークンなしでも正常に削除される")
        void 招待トークンなし_正常削除() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(inviteTokenRepository.findByOrganizationIdAndRevokedAtIsNull(ORG_ID))
                    .willReturn(List.of());

            organizationService.deleteOrganization(ORG_ID);

            assertThat(org.getDeletedAt()).isNotNull();
        }
    }

    // ========================================
    // archiveOrganization / unarchiveOrganization
    // ========================================

    @Nested
    @DisplayName("archiveOrganization")
    class ArchiveOrganization {

        @Test
        @DisplayName("正常アーカイブ_archivedAtが設定される")
        void 正常アーカイブ_archivedAtが設定される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            organizationService.archiveOrganization(ORG_ID);

            assertThat(org.getArchivedAt()).isNotNull();
        }

        @Test
        @DisplayName("既にアーカイブ済み_ORG_003例外")
        void 既にアーカイブ済み_ORG_003例外() {
            OrganizationEntity org = createArchivedOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            assertThatThrownBy(() -> organizationService.archiveOrganization(ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_003"));
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.archiveOrganization(999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    @Nested
    @DisplayName("unarchiveOrganization")
    class UnarchiveOrganization {

        @Test
        @DisplayName("正常アーカイブ解除_archivedAtがnullになる")
        void 正常アーカイブ解除_archivedAtがnullになる() {
            OrganizationEntity org = createArchivedOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            organizationService.unarchiveOrganization(ORG_ID);

            assertThat(org.getArchivedAt()).isNull();
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.unarchiveOrganization(999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    // ========================================
    // searchOrganizations
    // ========================================

    @Nested
    @DisplayName("searchOrganizations")
    class SearchOrganizations {

        @Test
        @DisplayName("キーワード検索_ページングされた結果が返される")
        void キーワード検索_ページングされた結果が返される() {
            OrganizationEntity org = createOrganization();
            Pageable pageable = PageRequest.of(0, 10);
            given(organizationRepository.searchByKeyword("テスト", pageable))
                    .willReturn(new PageImpl<>(List.of(org), pageable, 1));
            given(userRoleRepository.countByOrganizationId(any())).willReturn(3L);

            PagedResponse<OrganizationSummaryResponse> response =
                    organizationService.searchOrganizations("テスト", pageable);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getName()).isEqualTo("テスト組織");
            assertThat(response.getMeta().getTotal()).isEqualTo(1);
        }

        @Test
        @DisplayName("キーワードnullの場合_空文字で検索される")
        void キーワードnull_空文字で検索() {
            Pageable pageable = PageRequest.of(0, 10);
            given(organizationRepository.searchByKeyword("", pageable))
                    .willReturn(new PageImpl<>(List.of(), pageable, 0));

            PagedResponse<OrganizationSummaryResponse> response =
                    organizationService.searchOrganizations(null, pageable);

            assertThat(response.getData()).isEmpty();
            assertThat(response.getMeta().getTotal()).isZero();
        }

        @Test
        @DisplayName("検索結果なし_空リストが返される")
        void 検索結果なし_空リスト() {
            Pageable pageable = PageRequest.of(0, 10);
            given(organizationRepository.searchByKeyword("存在しない", pageable))
                    .willReturn(new PageImpl<>(List.of(), pageable, 0));

            PagedResponse<OrganizationSummaryResponse> response =
                    organizationService.searchOrganizations("存在しない", pageable);

            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // getMembers
    // ========================================

    @Nested
    @DisplayName("getMembers")
    class GetMembers {

        @Test
        @DisplayName("メンバー一覧取得_ユーザー情報付きで返される")
        void メンバー一覧取得_ユーザー情報付きで返される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            // F00.5 Phase 3: MemberQueryDispatcher 経由で memberships 参照
            MemberDto memberDto = new MemberDto(USER_ID, "yamada", null, "ADMIN",
                    LocalDateTime.now().minusMonths(1));
            given(memberQueryDispatcher.queryMembers(ORG_ID, ScopeType.ORGANIZATION, null))
                    .willReturn(List.of(memberDto));

            Pageable pageable = PageRequest.of(0, 10);
            PagedResponse<MemberResponse> response =
                    organizationService.getMembers(ORG_ID, pageable);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getDisplayName()).isEqualTo("yamada");
            assertThat(response.getData().get(0).getRoleName()).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("メンバー一覧取得_空リストの場合は空で返される")
        void メンバー一覧取得_空リスト返却() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            // Dispatcher が空リストを返す場合
            given(memberQueryDispatcher.queryMembers(ORG_ID, ScopeType.ORGANIZATION, null))
                    .willReturn(List.of());

            Pageable pageable = PageRequest.of(0, 10);
            PagedResponse<MemberResponse> response =
                    organizationService.getMembers(ORG_ID, pageable);

            assertThat(response.getData()).isEmpty();
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.getMembers(999L, PageRequest.of(0, 10)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    // ========================================
    // followOrganization / unfollowOrganization
    // ========================================

    @Nested
    @DisplayName("followOrganization")
    class FollowOrganization {

        @Test
        @DisplayName("正常フォロー_SUPPORTERロールで参加される")
        void 正常フォロー_SUPPORTERロールで参加される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(userRoleRepository.existsByUserIdAndOrganizationId(USER_ID, ORG_ID)).willReturn(false);

            RoleEntity supporterRole = RoleEntity.builder()
                    .id(200L).name("SUPPORTER").displayName("サポーター").priority(5).isSystem(true).build();
            given(roleRepository.findByName("SUPPORTER")).willReturn(Optional.of(supporterRole));

            organizationService.followOrganization(USER_ID, ORG_ID);

            verify(userRoleRepository).save(any(UserRoleEntity.class));
        }

        @Test
        @DisplayName("既にメンバー_ORG_007例外")
        void 既にメンバー_ORG_007例外() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(userRoleRepository.existsByUserIdAndOrganizationId(USER_ID, ORG_ID)).willReturn(true);

            assertThatThrownBy(() -> organizationService.followOrganization(USER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_007"));
        }

        @Test
        @DisplayName("SUPPORTERロール未定義_ORG_005例外")
        void SUPPORTERロール未定義_ORG_005例外() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));
            given(userRoleRepository.existsByUserIdAndOrganizationId(USER_ID, ORG_ID)).willReturn(false);
            given(roleRepository.findByName("SUPPORTER")).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.followOrganization(USER_ID, ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_005"));
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.followOrganization(USER_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    @Nested
    @DisplayName("unfollowOrganization")
    class UnfollowOrganization {

        @Test
        @DisplayName("正常フォロー解除_ユーザーロールが削除される")
        void 正常フォロー解除_ユーザーロールが削除される() {
            OrganizationEntity org = createOrganization();
            given(organizationRepository.findById(ORG_ID)).willReturn(Optional.of(org));

            organizationService.unfollowOrganization(USER_ID, ORG_ID);

            verify(userRoleRepository).deleteByUserIdAndOrganizationId(USER_ID, ORG_ID);
        }

        @Test
        @DisplayName("組織不在_ORG_001例外")
        void 組織不在_ORG_001例外() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.unfollowOrganization(USER_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private OrganizationEntity createOrganization() {
        return OrganizationEntity.builder()
                .name("テスト組織")
                .orgType(OrganizationEntity.OrgType.SCHOOL)
                .prefecture("東京都")
                .city("渋谷区")
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .version(0L)
                .build();
    }

    private OrganizationEntity createArchivedOrganization() {
        return OrganizationEntity.builder()
                .name("アーカイブ組織")
                .orgType(OrganizationEntity.OrgType.COMPANY)
                .prefecture("東京都")
                .city("千代田区")
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .archivedAt(LocalDateTime.now())
                .version(0L)
                .build();
    }
}

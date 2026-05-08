package com.mannschaft.app.property.service;

import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.service.PropertyWorkPackageMaskingService.MaskedView;
import com.mannschaft.app.role.service.PermissionGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PropertyWorkPackageMaskingService} 単体テスト（F09.13 Phase 2-α-3 厳密化版）。
 *
 * <p>設計書 §5.5 マスキング処理マトリクス全 20 セルを網羅
 * （ADMIN / DEPUTY_ADMIN(MANAGE) / DEPUTY_ADMIN(VIEW) / MEMBER / SUPPORTER × 4 visibility）。</p>
 *
 * <p><b>Phase 2-α-3 厳密化</b>: DEPUTY_ADMIN は {@link PermissionGroupService#hasPermission}
 * で {@code PROPERTY_HISTORY_MANAGE} 保有有無を照会し、保有時は MANAGE / 非保有時は VIEW として動作する。
 * 1-β 暫定で「DEPUTY_ADMIN は常に MANAGE 相当」だった挙動から完全に切替。</p>
 *
 * <p>マトリクス（設計書 §5.5）:</p>
 * <table>
 *   <caption>visibility × ロール × マスク</caption>
 *   <tr><th>visibility</th><th>ADMIN</th><th>DEPUTY_ADMIN(MANAGE)</th><th>DEPUTY_ADMIN(VIEW)</th><th>MEMBER</th><th>SUPPORTER</th></tr>
 *   <tr><td>ADMINS_ONLY</td><td>全表示</td><td>全表示</td><td>金額マスク</td><td>不可視</td><td>不可視</td></tr>
 *   <tr><td>MEMBERS_ONLY</td><td>全表示</td><td>全表示</td><td>金額マスク</td><td>全表示</td><td>不可視</td></tr>
 *   <tr><td>MEMBERS_MASKED</td><td>全表示</td><td>全表示</td><td>金額マスク</td><td>金額マスク</td><td>不可視</td></tr>
 *   <tr><td>PUBLIC_MASKED</td><td>全表示</td><td>全表示</td><td>金額マスク</td><td>金額マスク</td><td>金額マスク</td></tr>
 * </table>
 */
@DisplayName("PropertyWorkPackageMaskingService 単体テスト（F09.13 §5.5 マスキングマトリクス全20セル）")
class PropertyWorkPackageMaskingServiceTest {

    private PermissionGroupService permissionGroupService;
    private PropertyWorkPackageMaskingService service;

    private static final String SCOPE_TEAM = "TEAM";
    private static final Long TEAM_ID = 100L;
    private static final ScopeKey SCOPE = new ScopeKey(SCOPE_TEAM, TEAM_ID);
    private static final Long VIEWER_USER_ID = 7L;
    private static final String PERM_PROPERTY_HISTORY_MANAGE = "PROPERTY_HISTORY_MANAGE";

    @BeforeEach
    void setUp() {
        permissionGroupService = mock(PermissionGroupService.class);
        service = new PropertyWorkPackageMaskingService(permissionGroupService);
    }

    private PropertyWorkPackageEntity packageOf(WorkPackageVisibility v) {
        PropertyWorkPackageEntity e = PropertyWorkPackageEntity.builder()
                .scopeType(SCOPE_TEAM)
                .scopeId(TEAM_ID)
                .workType(WorkType.RENOVATION)
                .title("外壁塗装")
                .estimatedAmount(12_000_000L)
                .contractAmount(11_500_000L)
                .actualAmount(11_400_000L)
                .currency("JPY")
                .visibility(v)
                .status(WorkPackageStatus.IN_PROGRESS)
                .attachmentCount(0)
                .commentCount(0)
                .isDisclosable(true)
                .createdBy(7L)
                .build();
        ReflectionTestUtils.setField(e, "id", 1L);
        return e;
    }

    private VendorEntity vendor() {
        VendorEntity v = VendorEntity.builder()
                .scopeType(SCOPE_TEAM)
                .scopeId(TEAM_ID)
                .name("○○塗装工業")
                .nameKana("マルマル")
                .category(VendorCategory.CONSTRUCTION)
                .phone("03-1234-5678")
                .email("info@example.jp")
                .address("東京都千代田区1-2-3")
                .contactPerson("担当者")
                .isActive(true)
                .createdBy(7L)
                .build();
        ReflectionTestUtils.setField(v, "id", 11L);
        return v;
    }

    private UserScopeRoleSnapshot snapshotWithRole(String role) {
        return new UserScopeRoleSnapshot(
                false, Map.of(SCOPE, role), Map.of(), Set.of(), Set.of());
    }

    private UserScopeRoleSnapshot adminSnapshot() {
        return snapshotWithRole("ADMIN");
    }

    private UserScopeRoleSnapshot deputyAdminSnapshot() {
        return snapshotWithRole("DEPUTY_ADMIN");
    }

    private UserScopeRoleSnapshot memberSnapshot() {
        return snapshotWithRole("MEMBER");
    }

    private UserScopeRoleSnapshot supporterSnapshot() {
        return snapshotWithRole("SUPPORTER");
    }

    /** PROPERTY_HISTORY_MANAGE 権限グループ保有あり（MANAGE 相当） */
    private void grantManagePermission() {
        given(permissionGroupService.hasPermission(
                eq(VIEWER_USER_ID), eq(SCOPE_TEAM), eq(TEAM_ID), eq(PERM_PROPERTY_HISTORY_MANAGE)))
                .willReturn(true);
    }

    /** PROPERTY_HISTORY_MANAGE 権限グループ保有なし（VIEW 相当 / 暗黙のデフォルト動作） */
    private void denyManagePermission() {
        given(permissionGroupService.hasPermission(
                eq(VIEWER_USER_ID), eq(SCOPE_TEAM), eq(TEAM_ID), eq(PERM_PROPERTY_HISTORY_MANAGE)))
                .willReturn(false);
    }

    // =========================================================================
    // ADMINS_ONLY 行（5 セル）
    // =========================================================================

    @Nested
    @DisplayName("visibility=ADMINS_ONLY")
    class AdminsOnly {

        private PropertyWorkPackageEntity pkg;

        @BeforeEach
        void prep() {
            pkg = packageOf(WorkPackageVisibility.ADMINS_ONLY);
        }

        @Test
        @DisplayName("ADMIN → 全表示（金額・連絡先）")
        void admin_full() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, adminSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isTrue();
            assertThat(m.estimatedAmount()).isEqualTo(12_000_000L);
            assertThat(m.contractAmount()).isEqualTo(11_500_000L);
            assertThat(m.vendor().phone()).isEqualTo("03-1234-5678");
            assertThat(m.vendor().email()).isEqualTo("info@example.jp");
            // ADMIN 路線では permissionGroupService 照会は行わぬ（不要呼出し回避）
            verify(permissionGroupService, never()).hasPermission(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("DEPUTY_ADMIN(MANAGE) → 全表示")
        void deputyAdminManage_full() {
            grantManagePermission();
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, deputyAdminSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isTrue();
            assertThat(m.estimatedAmount()).isEqualTo(12_000_000L);
        }

        @Test
        @DisplayName("DEPUTY_ADMIN(VIEW) → 可視・金額マスク（連絡先 ●●●）")
        void deputyAdminView_masked() {
            denyManagePermission();
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, deputyAdminSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isFalse();
            assertThat(m.estimatedAmount()).isNull();
            assertThat(m.contractAmount()).isNull();
            assertThat(m.actualAmount()).isNull();
            assertThat(m.vendor().phone()).isEqualTo("●●●");
            assertThat(m.vendor().email()).isEqualTo("●●●");
        }

        @Test
        @DisplayName("MEMBER → 不可視（visible=false）")
        void member_hidden() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, memberSnapshot());
            assertThat(m.visible()).isFalse();
        }

        @Test
        @DisplayName("SUPPORTER → 不可視")
        void supporter_hidden() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, supporterSnapshot());
            assertThat(m.visible()).isFalse();
        }
    }

    // =========================================================================
    // MEMBERS_ONLY 行（5 セル）
    // =========================================================================

    @Nested
    @DisplayName("visibility=MEMBERS_ONLY")
    class MembersOnly {

        private PropertyWorkPackageEntity pkg;

        @BeforeEach
        void prep() {
            pkg = packageOf(WorkPackageVisibility.MEMBERS_ONLY);
        }

        @Test
        @DisplayName("ADMIN → 全表示")
        void admin_full() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, adminSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isTrue();
            assertThat(m.contractAmount()).isEqualTo(11_500_000L);
        }

        @Test
        @DisplayName("DEPUTY_ADMIN(MANAGE) → 全表示")
        void deputyAdminManage_full() {
            grantManagePermission();
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, deputyAdminSnapshot());
            assertThat(m.canViewAmount()).isTrue();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN(VIEW) → 可視・金額マスク")
        void deputyAdminView_masked() {
            denyManagePermission();
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, deputyAdminSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isFalse();
            assertThat(m.estimatedAmount()).isNull();
            assertThat(m.vendor().phone()).isEqualTo("●●●");
        }

        @Test
        @DisplayName("MEMBER → 全表示（MEMBERS_ONLY のみ MEMBER に金額開示）")
        void member_full() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, memberSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isTrue();
            assertThat(m.contractAmount()).isEqualTo(11_500_000L);
            assertThat(m.vendor().phone()).isEqualTo("03-1234-5678");
        }

        @Test
        @DisplayName("SUPPORTER → 不可視")
        void supporter_hidden() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, supporterSnapshot());
            assertThat(m.visible()).isFalse();
        }
    }

    // =========================================================================
    // MEMBERS_MASKED 行（5 セル）
    // =========================================================================

    @Nested
    @DisplayName("visibility=MEMBERS_MASKED")
    class MembersMasked {

        private PropertyWorkPackageEntity pkg;

        @BeforeEach
        void prep() {
            pkg = packageOf(WorkPackageVisibility.MEMBERS_MASKED);
        }

        @Test
        @DisplayName("ADMIN → 全表示")
        void admin_full() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, adminSnapshot());
            assertThat(m.canViewAmount()).isTrue();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN(MANAGE) → 全表示")
        void deputyAdminManage_full() {
            grantManagePermission();
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, deputyAdminSnapshot());
            assertThat(m.canViewAmount()).isTrue();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN(VIEW) → 金額マスク")
        void deputyAdminView_masked() {
            denyManagePermission();
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, deputyAdminSnapshot());
            assertThat(m.canViewAmount()).isFalse();
            assertThat(m.contractAmount()).isNull();
            assertThat(m.vendor().phone()).isEqualTo("●●●");
        }

        @Test
        @DisplayName("MEMBER → 金額マスク（金額 null + 連絡先 ●●●）")
        void member_masked() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, memberSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isFalse();
            assertThat(m.estimatedAmount()).isNull();
            assertThat(m.contractAmount()).isNull();
            assertThat(m.actualAmount()).isNull();
            assertThat(m.vendor().name()).isEqualTo("○○塗装工業"); // 業者名は表示
            assertThat(m.vendor().phone()).isEqualTo("●●●");
            assertThat(m.vendor().email()).isEqualTo("●●●");
            assertThat(m.vendor().address()).isEqualTo("●●●");
            assertThat(m.vendor().contactPerson()).isEqualTo("●●●");
        }

        @Test
        @DisplayName("SUPPORTER → 不可視")
        void supporter_hidden() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, supporterSnapshot());
            assertThat(m.visible()).isFalse();
        }
    }

    // =========================================================================
    // PUBLIC_MASKED 行（5 セル）
    // =========================================================================

    @Nested
    @DisplayName("visibility=PUBLIC_MASKED")
    class PublicMasked {

        private PropertyWorkPackageEntity pkg;

        @BeforeEach
        void prep() {
            pkg = packageOf(WorkPackageVisibility.PUBLIC_MASKED);
        }

        @Test
        @DisplayName("ADMIN → 全表示")
        void admin_full() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, adminSnapshot());
            assertThat(m.canViewAmount()).isTrue();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN(MANAGE) → 全表示")
        void deputyAdminManage_full() {
            grantManagePermission();
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, deputyAdminSnapshot());
            assertThat(m.canViewAmount()).isTrue();
        }

        @Test
        @DisplayName("DEPUTY_ADMIN(VIEW) → 金額マスク")
        void deputyAdminView_masked() {
            denyManagePermission();
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, deputyAdminSnapshot());
            assertThat(m.canViewAmount()).isFalse();
        }

        @Test
        @DisplayName("MEMBER → 金額マスク")
        void member_masked() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, memberSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isFalse();
            assertThat(m.contractAmount()).isNull();
        }

        @Test
        @DisplayName("SUPPORTER → 金額マスク（可視だが連絡先 ●●●）")
        void supporter_masked() {
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, supporterSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.canViewAmount()).isFalse();
            assertThat(m.estimatedAmount()).isNull();
            assertThat(m.vendor().phone()).isEqualTo("●●●");
        }
    }

    // =========================================================================
    // ヘルパ／特殊系
    // =========================================================================

    @Nested
    @DisplayName("ヘルパおよび境界系")
    class Helpers {

        @Test
        @DisplayName("isVisible: SystemAdmin は visibility に関わらず可視")
        void isVisible_systemAdmin_true() {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.ADMINS_ONLY);
            assertThat(service.isVisible(pkg, UserScopeRoleSnapshot.forSystemAdmin())).isTrue();
        }

        @Test
        @DisplayName("isVisible: snapshot null は不可視（fail-closed）")
        void isVisible_nullSnapshot_false() {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.ADMINS_ONLY);
            assertThat(service.isVisible(pkg, null)).isFalse();
        }

        @Test
        @DisplayName("isVisible: visibility が null（不正データ）は不可視（fail-closed）")
        void isVisible_nullVisibility_false() {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.MEMBERS_ONLY);
            ReflectionTestUtils.setField(pkg, "visibility", null);
            assertThat(service.isVisible(pkg, memberSnapshot())).isFalse();
        }

        @Test
        @DisplayName("applyMasking: vendor=null でも空 vendor view を返して visible 判定は維持")
        void apply_vendorNull() {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.MEMBERS_ONLY);
            MaskedView m = service.applyMasking(pkg, null, VIEWER_USER_ID, memberSnapshot());
            assertThat(m.visible()).isTrue();
            assertThat(m.vendor().id()).isNull();
            assertThat(m.vendor().name()).isNull();
        }

        @Test
        @DisplayName("applyMasking: SystemAdmin はすべて全表示で permissionGroupService を呼ばない")
        void apply_systemAdmin_all() {
            for (WorkPackageVisibility v : WorkPackageVisibility.values()) {
                PropertyWorkPackageEntity pkg = packageOf(v);
                MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID,
                        UserScopeRoleSnapshot.forSystemAdmin());
                assertThat(m.visible()).as("visibility=%s", v).isTrue();
                assertThat(m.canViewAmount()).as("visibility=%s", v).isTrue();
            }
            verify(permissionGroupService, never()).hasPermission(anyLong(), any(), any(), any());
        }

        @Test
        @DisplayName("applyMasking: entity null は hidden")
        void apply_entityNull() {
            MaskedView m = service.applyMasking(null, null, VIEWER_USER_ID, adminSnapshot());
            assertThat(m.visible()).isFalse();
        }

        @Test
        @DisplayName("applyMasking: MEMBER 路線では permissionGroupService を呼ばない（不要呼出し回避）")
        void apply_member_skipsPermissionLookup() {
            PropertyWorkPackageEntity pkg = packageOf(WorkPackageVisibility.MEMBERS_ONLY);
            MaskedView m = service.applyMasking(pkg, vendor(), VIEWER_USER_ID, memberSnapshot());
            assertThat(m.canViewAmount()).isTrue();
            verify(permissionGroupService, never()).hasPermission(anyLong(), any(), any(), any());
        }
    }
}

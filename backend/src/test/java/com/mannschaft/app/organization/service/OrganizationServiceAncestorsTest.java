package com.mannschaft.app.organization.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.dto.AncestorOrganizationResponse;
import com.mannschaft.app.organization.dto.AncestorsResponse;
import com.mannschaft.app.organization.dto.ChildOrganizationResponse;
import com.mannschaft.app.organization.dto.ChildrenResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link OrganizationService} の F01.2 階層表示API（祖先・子組織）の単体テスト。
 *
 * <p>祖先個別の返却フィルタ（直接所属／子孫メンバー＋hierarchyVisibility／外部）と、
 * 子組織取得の visibility フィルタ・認可・archived 表示を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrganizationService 階層表示API（祖先・子組織）")
class OrganizationServiceAncestorsTest {

    private static final Long REQUESTER_ID = 1L;
    private static final Long TARGET_ORG_ID = 100L;
    private static final Long PARENT_ORG_ID = 200L;
    private static final Long GRANDPARENT_ORG_ID = 300L;

    @Mock private OrganizationRepository organizationRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @Mock private InviteTokenRepository inviteTokenRepository;

    @InjectMocks
    private OrganizationService organizationService;

    @BeforeEach
    void setUp() {
        // @Value 注入と同等の設定（テスト時は ReflectionTestUtils で直接代入）
        ReflectionTestUtils.setField(organizationService, "maxDepth", 5);

        // findParentOrganizationIdById のデフォルト戻り値は Optional.empty()。
        // 個別テストで specific ID にスタブを上書きする運用。
        given(organizationRepository.findParentOrganizationIdById(anyLong())).willReturn(Optional.empty());

        // 所属系 stub のデフォルトは「所属なし」
        given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(anyLong())).willReturn(List.of());
        given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(anyLong())).willReturn(List.of());
    }

    // ========================================
    // getAncestors
    // ========================================

    @Nested
    @DisplayName("getAncestors")
    class GetAncestors {

        @Test
        @DisplayName("トップレベル組織_dataが空でdepth0を返す")
        void トップレベル組織_dataが空でdepth0を返す() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "対象組織")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(null)
                    .build();
            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));

            AncestorsResponse response = organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID);

            assertThat(response.getData()).isEmpty();
            assertThat(response.getMeta().getDepth()).isZero();
            assertThat(response.getMeta().isTruncated()).isFalse();
        }

        @Test
        @DisplayName("直接所属メンバー_全祖先がフル情報")
        void 直接所属メンバー_全祖先がフル情報() {
            OrganizationEntity grandparent = orgBuilder(GRANDPARENT_ORG_ID, "祖父組織")
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .iconUrl("icon-gp.png")
                    .build();
            OrganizationEntity parent = orgBuilder(PARENT_ORG_ID, "親組織")
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .parentOrganizationId(GRANDPARENT_ORG_ID)
                    .iconUrl("icon-p.png")
                    .build();
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "対象組織")
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .parentOrganizationId(PARENT_ORG_ID)
                    .build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findById(PARENT_ORG_ID)).willReturn(Optional.of(parent));
            given(organizationRepository.findById(GRANDPARENT_ORG_ID)).willReturn(Optional.of(grandparent));

            // 対象＋全祖先に直接所属
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, TARGET_ORG_ID)).willReturn(true);
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, PARENT_ORG_ID)).willReturn(true);
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, GRANDPARENT_ORG_ID)).willReturn(true);

            AncestorsResponse response = organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID);

            assertThat(response.getData()).hasSize(2);
            // root 先頭（祖父→親 の順）
            AncestorOrganizationResponse first = response.getData().get(0);
            assertThat(first.getId()).isEqualTo(GRANDPARENT_ORG_ID);
            assertThat(first.getName()).isEqualTo("祖父組織");
            assertThat(first.isHidden()).isFalse();
            assertThat(first.getIconUrl()).isEqualTo("icon-gp.png");
            assertThat(first.getVisibility()).isEqualTo("PRIVATE");

            AncestorOrganizationResponse second = response.getData().get(1);
            assertThat(second.getId()).isEqualTo(PARENT_ORG_ID);
            assertThat(second.getName()).isEqualTo("親組織");

            assertThat(response.getMeta().getDepth()).isEqualTo(2);
            assertThat(response.getMeta().isTruncated()).isFalse();
        }

        @Test
        @DisplayName("子孫メンバー_祖先がBASIC_限定フィールドのみ")
        void 子孫メンバー_祖先BASIC_限定フィールド() {
            OrganizationEntity parent = orgBuilder(PARENT_ORG_ID, "親組織")
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.BASIC)
                    .iconUrl("icon-p.png")
                    .nickname1("親愛称")
                    .build();
            // 対象は PUBLIC（認可チェックをスキップさせる）
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "対象組織")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(PARENT_ORG_ID)
                    .build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findById(PARENT_ORG_ID)).willReturn(Optional.of(parent));

            // 親には直接所属していない
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, PARENT_ORG_ID)).willReturn(false);

            // 子孫メンバー判定: ユーザーは対象組織（PARENT の子）に所属
            UserRoleEntity ur = UserRoleEntity.builder()
                    .id(1L).userId(REQUESTER_ID).roleId(10L).organizationId(TARGET_ORG_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(REQUESTER_ID))
                    .willReturn(List.of(ur));
            // TARGET_ORG_ID の親 = PARENT_ORG_ID（hasAncestor で参照）
            given(organizationRepository.findParentOrganizationIdById(TARGET_ORG_ID))
                    .willReturn(Optional.of(PARENT_ORG_ID));

            AncestorsResponse response = organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID);

            assertThat(response.getData()).hasSize(1);
            AncestorOrganizationResponse a = response.getData().get(0);
            assertThat(a.getId()).isEqualTo(PARENT_ORG_ID);
            assertThat(a.getName()).isEqualTo("親組織");
            assertThat(a.getNickname1()).isEqualTo("親愛称");
            assertThat(a.getIconUrl()).isEqualTo("icon-p.png");
            assertThat(a.getVisibility()).isEqualTo("PRIVATE");
            assertThat(a.isHidden()).isFalse();
        }

        @Test
        @DisplayName("子孫メンバー_祖先がNONE_hiddenプレースホルダ")
        void 子孫メンバー_祖先NONE_hiddenプレースホルダ() {
            OrganizationEntity parent = orgBuilder(PARENT_ORG_ID, "親組織")
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .build();
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "対象組織")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(PARENT_ORG_ID)
                    .build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findById(PARENT_ORG_ID)).willReturn(Optional.of(parent));

            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, PARENT_ORG_ID)).willReturn(false);

            UserRoleEntity ur = UserRoleEntity.builder()
                    .id(1L).userId(REQUESTER_ID).roleId(10L).organizationId(TARGET_ORG_ID).build();
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(REQUESTER_ID))
                    .willReturn(List.of(ur));
            given(organizationRepository.findParentOrganizationIdById(TARGET_ORG_ID))
                    .willReturn(Optional.of(PARENT_ORG_ID));

            AncestorsResponse response = organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID);

            assertThat(response.getData()).hasSize(1);
            AncestorOrganizationResponse a = response.getData().get(0);
            assertThat(a.getId()).isEqualTo(PARENT_ORG_ID);
            assertThat(a.isHidden()).isTrue();
            // hidden=true なら他フィールドは null
            assertThat(a.getName()).isNull();
            assertThat(a.getVisibility()).isNull();
            assertThat(a.getIconUrl()).isNull();
        }

        @Test
        @DisplayName("外部ユーザー_祖先PUBLIC_限定フィールドのみ")
        void 外部ユーザー_祖先PUBLIC_限定フィールド() {
            OrganizationEntity parent = orgBuilder(PARENT_ORG_ID, "公開親組織")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .iconUrl("icon-p.png")
                    .nickname1("公開愛称")
                    .build();
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "対象組織")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(PARENT_ORG_ID)
                    .build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findById(PARENT_ORG_ID)).willReturn(Optional.of(parent));

            // 直接所属ではなく所属組織もない（外部ユーザー）
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, PARENT_ORG_ID)).willReturn(false);

            AncestorsResponse response = organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID);

            assertThat(response.getData()).hasSize(1);
            AncestorOrganizationResponse a = response.getData().get(0);
            assertThat(a.getId()).isEqualTo(PARENT_ORG_ID);
            assertThat(a.getName()).isEqualTo("公開親組織");
            assertThat(a.getNickname1()).isEqualTo("公開愛称");
            assertThat(a.getIconUrl()).isEqualTo("icon-p.png");
            assertThat(a.getVisibility()).isEqualTo("PUBLIC");
            assertThat(a.isHidden()).isFalse();
        }

        @Test
        @DisplayName("外部ユーザー_祖先PRIVATE_hiddenプレースホルダ")
        void 外部ユーザー_祖先PRIVATE_hiddenプレースホルダ() {
            OrganizationEntity parent = orgBuilder(PARENT_ORG_ID, "非公開親")
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.FULL) // PRIVATE 単独で hide される
                    .build();
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "対象組織")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(PARENT_ORG_ID)
                    .build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findById(PARENT_ORG_ID)).willReturn(Optional.of(parent));
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, PARENT_ORG_ID)).willReturn(false);

            AncestorsResponse response = organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID);

            assertThat(response.getData()).hasSize(1);
            AncestorOrganizationResponse a = response.getData().get(0);
            assertThat(a.getId()).isEqualTo(PARENT_ORG_ID);
            assertThat(a.isHidden()).isTrue();
            assertThat(a.getName()).isNull();
        }

        @Test
        @DisplayName("max-depth到達_truncatedがtrue")
        void maxDepth到達_truncated() {
            // maxDepth=2 にして 2hop で打ち切り、それ以上の親が残る状況を再現
            ReflectionTestUtils.setField(organizationService, "maxDepth", 2);

            OrganizationEntity grandparent = orgBuilder(GRANDPARENT_ORG_ID, "祖父")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(999L)  // さらに上があるという仮定
                    .build();
            OrganizationEntity parent = orgBuilder(PARENT_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(GRANDPARENT_ORG_ID)
                    .build();
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "対象")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(PARENT_ORG_ID)
                    .build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findById(PARENT_ORG_ID)).willReturn(Optional.of(parent));
            given(organizationRepository.findById(GRANDPARENT_ORG_ID)).willReturn(Optional.of(grandparent));

            AncestorsResponse response = organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID);

            // depth=2 まで取れる → grandparent と parent
            assertThat(response.getData()).hasSize(2);
            assertThat(response.getMeta().getDepth()).isEqualTo(2);
            assertThat(response.getMeta().isTruncated()).isTrue();
        }

        @Test
        @DisplayName("サイクル検出_ループせず正常終了")
        void サイクル検出_ループせず終了() {
            // PARENT が自分自身を親とするサイクル
            OrganizationEntity parent = orgBuilder(PARENT_ORG_ID, "自己参照親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(PARENT_ORG_ID)
                    .build();
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "対象")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(PARENT_ORG_ID)
                    .build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findById(PARENT_ORG_ID)).willReturn(Optional.of(parent));

            AncestorsResponse response = organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID);

            // サイクル検出で打ち切り → parent の1件のみ
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getId()).isEqualTo(PARENT_ORG_ID);
            // truncated はサイクル検出時には false（max-depth による打ち切りではないため）
            assertThat(response.getMeta().isTruncated()).isFalse();
        }

        @Test
        @DisplayName("対象がPRIVATE_外部ユーザー_403相当の例外")
        void 対象PRIVATE_外部ユーザー_403() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "非公開組織")
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .build();
            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, TARGET_ORG_ID)).willReturn(false);

            assertThatThrownBy(() -> organizationService.getAncestors(TARGET_ORG_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("対象組織不在_ORG_001例外")
        void 組織不在_ORG_001() {
            given(organizationRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> organizationService.getAncestors(999L, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_001"));
        }
    }

    // ========================================
    // getChildren
    // ========================================

    @Nested
    @DisplayName("getChildren")
    class GetChildren {

        @Test
        @DisplayName("対象PUBLIC_全PUBLIC子のみ返却")
        void 対象PUBLIC_全PUBLIC子のみ() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            OrganizationEntity child1 = orgBuilder(11L, "子1")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(TARGET_ORG_ID)
                    .iconUrl("c1.png").build();
            OrganizationEntity child2 = orgBuilder(12L, "子2")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(TARGET_ORG_ID).build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findByParentOrganizationIdAndDeletedAtIsNull(eq(TARGET_ORG_ID), any(Pageable.class)))
                    .willReturn(List.of(child1, child2));
            given(userRoleRepository.countByOrganizationId(11L)).willReturn(3L);
            given(userRoleRepository.countByOrganizationId(12L)).willReturn(0L);

            ChildrenResponse response = organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 50);

            assertThat(response.getData()).hasSize(2);
            ChildOrganizationResponse first = response.getData().get(0);
            assertThat(first.getId()).isEqualTo(11L);
            assertThat(first.getName()).isEqualTo("子1");
            assertThat(first.getVisibility()).isEqualTo("PUBLIC");
            assertThat(first.getMemberCount()).isEqualTo(3);
            assertThat(first.isArchived()).isFalse();
            assertThat(first.getIconUrl()).isEqualTo("c1.png");
            assertThat(response.getMeta().isHasNext()).isFalse();
        }

        @Test
        @DisplayName("PRIVATE子は非メンバーには除外される")
        void PRIVATE子_非メンバー除外() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            OrganizationEntity publicChild = orgBuilder(11L, "公開子")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(TARGET_ORG_ID).build();
            OrganizationEntity privateChild = orgBuilder(12L, "非公開子")
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .parentOrganizationId(TARGET_ORG_ID).build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findByParentOrganizationIdAndDeletedAtIsNull(eq(TARGET_ORG_ID), any(Pageable.class)))
                    .willReturn(List.of(publicChild, privateChild));
            // 呼び出し者は privateChild のメンバーではない
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, 12L)).willReturn(false);
            given(userRoleRepository.countByOrganizationId(11L)).willReturn(0L);

            ChildrenResponse response = organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 50);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getId()).isEqualTo(11L);
        }

        @Test
        @DisplayName("archived子は除外せずarchived_trueで返す")
        void archived子_archivedフラグで返却() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            OrganizationEntity archivedChild = orgBuilder(11L, "アーカイブ子")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(TARGET_ORG_ID)
                    .archivedAt(java.time.LocalDateTime.now()).build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findByParentOrganizationIdAndDeletedAtIsNull(eq(TARGET_ORG_ID), any(Pageable.class)))
                    .willReturn(List.of(archivedChild));
            given(userRoleRepository.countByOrganizationId(11L)).willReturn(2L);

            ChildrenResponse response = organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 50);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).isArchived()).isTrue();
        }

        @Test
        @DisplayName("対象PRIVATE_非所属_403相当の例外")
        void 対象PRIVATE_非所属_403() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "非公開親")
                    .visibility(OrganizationEntity.Visibility.PRIVATE).build();
            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(userRoleRepository.existsByUserIdAndOrganizationId(REQUESTER_ID, TARGET_ORG_ID)).willReturn(false);

            assertThatThrownBy(() -> organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 50))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    /**
     * デフォルト値で OrganizationEntity を組み立てる Builder を返す。
     *
     * <p>BaseEntity の {@code id} は子クラスの Lombok {@code @Builder} には含まれないので、
     * 内部で参照を保持して build() 直前ではなく build() 後に setField で埋める方式を取る。</p>
     */
    private TestOrgBuilder orgBuilder(Long id, String name) {
        return new TestOrgBuilder(id).name(name);
    }

    /**
     * テスト用の OrganizationEntity ビルダーラッパー。Lombok ビルダーに加えて
     * BaseEntity.id を ReflectionTestUtils で埋めて返す。
     */
    private static class TestOrgBuilder {
        private final Long id;
        private final OrganizationEntity.OrganizationEntityBuilder inner;

        TestOrgBuilder(Long id) {
            this.id = id;
            this.inner = OrganizationEntity.builder()
                    .orgType(OrganizationEntity.OrgType.OTHER)
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .supporterEnabled(false)
                    .version(0L);
        }

        TestOrgBuilder name(String name) { inner.name(name); return this; }
        TestOrgBuilder nickname1(String v) { inner.nickname1(v); return this; }
        TestOrgBuilder visibility(OrganizationEntity.Visibility v) { inner.visibility(v); return this; }
        TestOrgBuilder hierarchyVisibility(OrganizationEntity.HierarchyVisibility v) { inner.hierarchyVisibility(v); return this; }
        TestOrgBuilder iconUrl(String v) { inner.iconUrl(v); return this; }
        TestOrgBuilder parentOrganizationId(Long v) { inner.parentOrganizationId(v); return this; }
        TestOrgBuilder archivedAt(java.time.LocalDateTime v) { inner.archivedAt(v); return this; }

        OrganizationEntity build() {
            OrganizationEntity entity = inner.build();
            ReflectionTestUtils.setField(entity, "id", id);
            return entity;
        }
    }
}

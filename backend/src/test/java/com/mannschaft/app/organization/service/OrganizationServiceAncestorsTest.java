package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.dto.AncestorOrganizationResponse;
import com.mannschaft.app.organization.dto.AncestorsResponse;
import com.mannschaft.app.organization.dto.ChildOrganizationResponse;
import com.mannschaft.app.organization.dto.ChildrenResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link OrganizationHierarchyService} の F01.2 階層表示API（祖先・子組織）の単体テスト。
 *
 * <p>祖先個別の返却フィルタ（直接所属／子孫メンバー＋hierarchyVisibility／外部）と、
 * 子組織取得の visibility フィルタ・認可・archived 表示を検証する。</p>
 *
 * <p>リファクタリング Phase 5 で OrganizationService から階層ロジックを切り出した。
 * テスト対象クラスのみ差し替え、テスト内容（assertion・stub）は分割前から変更していない。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrganizationHierarchyService 階層表示API（祖先・子組織）")
class OrganizationServiceAncestorsTest {

    private static final Long REQUESTER_ID = 1L;
    private static final Long TARGET_ORG_ID = 100L;
    private static final Long PARENT_ORG_ID = 200L;
    private static final Long GRANDPARENT_ORG_ID = 300L;

    @Mock private OrganizationRepository organizationRepository;
    @Mock private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private OrganizationHierarchyService organizationService;

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

        // 画像 URL 根治 Phase 2: 既存アサーション（iconUrl=生キー値）を温存するため、
        // デフォルトは恒等変換（resolve(key)=key）にしておく。署名 URL 化の検証は専用テストで上書きする。
        given(mediaUrlResolver.resolve(any())).willAnswer(inv -> inv.getArgument(0));
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

        /**
         * findChildrenPage は可視性・カーソル・ORDER BY をすべて SQL 側で解決している前提の
         * クエリのため、モックでは「クエリが返すべき（＝既に可視性フィルタ済みの）行」を
         * そのまま stub すればよい。旧実装のようにサービス側で個別 exists 判定は行わない。
         */
        private void stubChildrenPage(List<OrganizationEntity> rows) {
            given(organizationRepository.findChildrenPage(
                    eq(TARGET_ORG_ID), any(), any(), any(Pageable.class)))
                    .willReturn(rows);
        }

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
            stubChildrenPage(List.of(child1, child2));
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
        @DisplayName("画像URL根治Phase2_子のiconUrlが署名付き表示URLへ解決される")
        void 子のiconUrlが署名付き表示URLへ解決される() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            OrganizationEntity child = orgBuilder(11L, "子")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(TARGET_ORG_ID)
                    .iconUrl("org/11/icon/raw.png").build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            stubChildrenPage(List.of(child));
            given(userRoleRepository.countByOrganizationId(11L)).willReturn(0L);
            // setUp の恒等変換を上書きし、生キーが署名付き表示 URL へ解決されることを検証する
            given(mediaUrlResolver.resolve("org/11/icon/raw.png"))
                    .willReturn("https://cdn.example.com/signed/org-icon.png");

            ChildrenResponse response = organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 50);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getIconUrl())
                    .isEqualTo("https://cdn.example.com/signed/org-icon.png");
        }

        /**
         * <b>検分での指摘（PR #2599）を受けた作り替え</b>: 可視性（PRIVATE 子の除外）を
         * Repository の JPQL（{@code visibility = PUBLIC OR o.id IN :memberOrgIds}）へ
         * 移した結果、Mockito でリポジトリをモックする本 UT には「非公開の子が実際に
         * 除外されるか」を検証する術が原理的に無くなった（モックの戻り値をそのまま
         * 返すだけの主張になってしまうため）。そこで本 UT の役割を
         * 「サービスが {@code findOrganizationIdsByUserId} の戻り値を
         * {@code findChildrenPage} の {@code memberOrgIds} 引数へ正しく伝播させるか」
         * （＝可視性判定の材料を正しく Repository へ渡しているか）の検証に絞り込んだ。
         * 「渡した先の SQL が実際に非公開の子を除外するか」は
         * {@code OrganizationChildrenCursorPagingContractIT}（実 DB を使う契約 IT・
         * AC-1/AC-2）が担う。UT と IT で役割を分担している。
         */
        @Test
        @DisplayName("PRIVATE子除外の材料_findOrganizationIdsByUserIdの戻り値がmemberOrgIdsとしてクエリへ渡る")
        @SuppressWarnings("unchecked")
        void PRIVATE子除外の材料_所属組織IDがクエリへ伝播する() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            OrganizationEntity publicChild = orgBuilder(11L, "公開子")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(TARGET_ORG_ID).build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            // 呼び出し者は組織 77L・88L に所属している想定
            given(userRoleRepository.findOrganizationIdsByUserId(REQUESTER_ID)).willReturn(List.of(77L, 88L));
            stubChildrenPage(List.of(publicChild));
            given(userRoleRepository.countByOrganizationId(11L)).willReturn(0L);

            organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 50);

            ArgumentCaptor<Collection<Long>> memberOrgIdsCaptor = ArgumentCaptor.forClass(Collection.class);
            verify(organizationRepository).findChildrenPage(
                    eq(TARGET_ORG_ID), any(), memberOrgIdsCaptor.capture(), any(Pageable.class));
            assertThat(memberOrgIdsCaptor.getValue()).containsExactlyInAnyOrder(77L, 88L);
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
            stubChildrenPage(List.of(archivedChild));
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

        // ========================================================================
        // 試練（先行テスト）: 子組織一覧カーソルページングの根治3欠陥
        // AC-7-1〜AC-7-5（軍議受け入れ条件）
        // ========================================================================

        @Test
        @DisplayName("AC-7-1_cursorを渡すとRepositoryへ渡るクエリ引数にcursorIdが乗る")
        void AC_7_1_cursorがクエリ引数に乗る() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findChildrenPage(
                    eq(TARGET_ORG_ID), any(), any(), any(Pageable.class)))
                    .willReturn(List.of());

            organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, "42", 50);

            ArgumentCaptor<Long> cursorCaptor = ArgumentCaptor.forClass(Long.class);
            verify(organizationRepository).findChildrenPage(
                    eq(TARGET_ORG_ID), cursorCaptor.capture(), any(), any(Pageable.class));
            assertThat(cursorCaptor.getValue()).isEqualTo(42L);
        }

        @Test
        @DisplayName("AC-7-1b_cursorがnullのときはnullがそのままクエリ引数に乗る")
        void AC_7_1b_cursorがnullのときnullが乗る() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(organizationRepository.findChildrenPage(
                    eq(TARGET_ORG_ID), any(), any(), any(Pageable.class)))
                    .willReturn(List.of());

            organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 50);

            verify(organizationRepository).findChildrenPage(
                    eq(TARGET_ORG_ID), isNull(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("AC-7-2_DBがpageSize+1件返せば可視件数がpageSize未満でもhasNext=true")
        void AC_7_2_DB取得件数でhasNextを判定する() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            // pageSize=2 に対し、SQL 側の可視性フィルタを通過済みの3件が返る想定
            // （このうち1件は非公開だが呼び出し者がメンバーなので SQL 側で既に含まれている）
            OrganizationEntity c1 = orgBuilder(11L, "子1").parentOrganizationId(TARGET_ORG_ID).build();
            OrganizationEntity c2 = orgBuilder(12L, "子2").parentOrganizationId(TARGET_ORG_ID).build();
            OrganizationEntity c3 = orgBuilder(13L, "子3").parentOrganizationId(TARGET_ORG_ID).build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            stubChildrenPage(List.of(c1, c2, c3));
            given(userRoleRepository.countByOrganizationId(anyLong())).willReturn(0L);

            ChildrenResponse response = organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 2);

            assertThat(response.getData()).hasSize(2);
            assertThat(response.getMeta().isHasNext()).isTrue();
            assertThat(response.getMeta().getNextCursor()).isEqualTo("12");
        }

        @Test
        @DisplayName("AC-7-3_nextCursorが次回取得起点として機能し前回分を再取得しない")
        void AC_7_3_nextCursorが次回起点として機能する() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            OrganizationEntity c1 = orgBuilder(11L, "子1").parentOrganizationId(TARGET_ORG_ID).build();
            OrganizationEntity c2 = orgBuilder(12L, "子2").parentOrganizationId(TARGET_ORG_ID).build();
            OrganizationEntity c3 = orgBuilder(13L, "子3").parentOrganizationId(TARGET_ORG_ID).build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            given(userRoleRepository.countByOrganizationId(anyLong())).willReturn(0L);
            // 1回目: cursor=null → DB は c1, c2, c3 を返す（pageSize=2 なので c3 が次ページ判定用）
            given(organizationRepository.findChildrenPage(eq(TARGET_ORG_ID), isNull(), any(), any(Pageable.class)))
                    .willReturn(List.of(c1, c2, c3));

            ChildrenResponse first = organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 2);
            assertThat(first.getData()).extracting(ChildOrganizationResponse::getId)
                    .containsExactly(11L, 12L);
            String nextCursor = first.getMeta().getNextCursor();
            assertThat(nextCursor).isEqualTo("12");

            // 2回目: 1回目の nextCursor を渡す → DB は c1・c2 を除外した c3 のみ返す想定
            given(organizationRepository.findChildrenPage(eq(TARGET_ORG_ID), eq(12L), any(), any(Pageable.class)))
                    .willReturn(List.of(c3));

            ChildrenResponse second = organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, nextCursor, 2);

            assertThat(second.getData()).extracting(ChildOrganizationResponse::getId)
                    .containsExactly(13L);
            assertThat(second.getData()).extracting(ChildOrganizationResponse::getId)
                    .doesNotContain(11L, 12L);
            assertThat(second.getMeta().isHasNext()).isFalse();

            // クエリ引数として cursorId=12 が実際に渡されたことを確認する（何を渡したか、を検証）
            verify(organizationRepository).findChildrenPage(eq(TARGET_ORG_ID), eq(12L), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("AC-7-4_所属組織0件でもPUBLIC子は見える_IN空コレクションの罠を踏まない")
        @SuppressWarnings("unchecked")
        void AC_7_4_所属0件でもPUBLIC子は見える() {
            OrganizationEntity target = orgBuilder(TARGET_ORG_ID, "親")
                    .visibility(OrganizationEntity.Visibility.PUBLIC).build();
            OrganizationEntity publicChild = orgBuilder(11L, "公開子")
                    .visibility(OrganizationEntity.Visibility.PUBLIC)
                    .parentOrganizationId(TARGET_ORG_ID).build();

            given(organizationRepository.findById(TARGET_ORG_ID)).willReturn(Optional.of(target));
            // 呼び出し者は所属組織0件（Mockito のデフォルト空リストをそのまま使う）
            given(userRoleRepository.findOrganizationIdsByUserId(REQUESTER_ID)).willReturn(List.of());
            given(userRoleRepository.countByOrganizationId(11L)).willReturn(0L);
            given(organizationRepository.findChildrenPage(
                    eq(TARGET_ORG_ID), any(), any(), any(Pageable.class)))
                    .willReturn(List.of(publicChild));

            ChildrenResponse response = organizationService.getChildren(TARGET_ORG_ID, REQUESTER_ID, null, 50);

            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getId()).isEqualTo(11L);

            // memberOrgIds として空コレクションではなくセンチネル入りの非空リストが渡ったことを確認する
            ArgumentCaptor<Collection<Long>> memberOrgIdsCaptor = ArgumentCaptor.forClass(Collection.class);
            verify(organizationRepository).findChildrenPage(
                    eq(TARGET_ORG_ID), any(), memberOrgIdsCaptor.capture(), any(Pageable.class));
            assertThat(memberOrgIdsCaptor.getValue()).isNotEmpty();
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

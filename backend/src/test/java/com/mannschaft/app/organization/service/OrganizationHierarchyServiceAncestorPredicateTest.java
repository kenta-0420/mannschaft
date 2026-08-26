package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * {@link OrganizationHierarchyService#isAncestorOf(Long, Long)} /
 * {@link OrganizationHierarchyService#isDescendantOf(Long, Long)} の単体テスト（F08.7.1 隊0）。
 *
 * <p>org → org の祖先/子孫を真偽判定する新設公開メソッドを検証する。
 * 既存 private {@code hasAncestor} のサイクル検出・{@code maxDepth} 制限ロジックを土台に
 * 公開化したもの。リーグ・ピラミッド（F08.7.1 §2）が組織階層から導出する際の基盤。</p>
 *
 * <p>組織階層の親リンクは {@code OrganizationRepository.findParentOrganizationIdById} で辿る。
 * 既存 {@link OrganizationServiceAncestorsTest} と同じ作法でスタブする。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrganizationHierarchyService org→org 祖先/子孫 真偽判定（isAncestorOf / isDescendantOf）")
class OrganizationHierarchyServiceAncestorPredicateTest {

    /** 全国 ⊃ 九州 ⊃ 大分 ⊃ チーム所属org のテスト用階層。 */
    private static final Long NATIONAL_ORG_ID = 1000L;   // 全国（root）
    private static final Long KYUSHU_ORG_ID = 2000L;     // 九州（NATIONAL の子）
    private static final Long OITA_ORG_ID = 3000L;       // 大分（KYUSHU の子）
    private static final Long UNRELATED_ORG_ID = 9000L;  // 無関係（root）

    @Mock private OrganizationRepository organizationRepository;
    @Mock private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private OrganizationHierarchyService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxDepth", 5);

        // デフォルトは「親なし」。各テストで具体 ID にスタブを上書きする。
        given(organizationRepository.findParentOrganizationIdById(anyLong()))
                .willReturn(Optional.empty());

        // 既定の階層: 全国 ⊃ 九州 ⊃ 大分
        given(organizationRepository.findParentOrganizationIdById(OITA_ORG_ID))
                .willReturn(Optional.of(KYUSHU_ORG_ID));
        given(organizationRepository.findParentOrganizationIdById(KYUSHU_ORG_ID))
                .willReturn(Optional.of(NATIONAL_ORG_ID));
        given(organizationRepository.findParentOrganizationIdById(NATIONAL_ORG_ID))
                .willReturn(Optional.empty());
        given(organizationRepository.findParentOrganizationIdById(UNRELATED_ORG_ID))
                .willReturn(Optional.empty());
    }

    @Nested
    @DisplayName("isAncestorOf(ancestorOrgId, descendantOrgId)")
    class IsAncestorOf {

        @Test
        @DisplayName("直接の親は子の祖先である_true")
        void 直接の親は子の祖先である_true() {
            assertThat(service.isAncestorOf(KYUSHU_ORG_ID, OITA_ORG_ID)).isTrue();
        }

        @Test
        @DisplayName("2段以上の祖先も祖先である_true")
        void 二段以上の祖先も祖先である_true() {
            assertThat(service.isAncestorOf(NATIONAL_ORG_ID, OITA_ORG_ID)).isTrue();
        }

        @Test
        @DisplayName("無関係な組織は祖先でない_false")
        void 無関係な組織は祖先でない_false() {
            assertThat(service.isAncestorOf(UNRELATED_ORG_ID, OITA_ORG_ID)).isFalse();
        }

        @Test
        @DisplayName("自分自身は自分の祖先でない_false")
        void 自分自身は自分の祖先でない_false() {
            assertThat(service.isAncestorOf(OITA_ORG_ID, OITA_ORG_ID)).isFalse();
        }

        @Test
        @DisplayName("逆方向_子は親の祖先でない_false")
        void 逆方向_子は親の祖先でない_false() {
            assertThat(service.isAncestorOf(OITA_ORG_ID, NATIONAL_ORG_ID)).isFalse();
        }

        @Test
        @DisplayName("null引数は安全にfalseを返す")
        void null引数は安全にfalseを返す() {
            assertThat(service.isAncestorOf(null, OITA_ORG_ID)).isFalse();
            assertThat(service.isAncestorOf(KYUSHU_ORG_ID, null)).isFalse();
            assertThat(service.isAncestorOf(null, null)).isFalse();
        }

        @Test
        @DisplayName("存在しない組織_親リンクなしは祖先でない_false")
        void 存在しない組織は祖先でない_false() {
            // 99999 は findParentOrganizationIdById でデフォルトの empty を返す（親リンクなし）
            assertThat(service.isAncestorOf(NATIONAL_ORG_ID, 99999L)).isFalse();
        }

        @Test
        @DisplayName("サイクルがあっても無限ループせず停止しfalse")
        void サイクルがあっても無限ループせず停止する() {
            // A → B → A のサイクルを作る
            Long cycleA = 5001L;
            Long cycleB = 5002L;
            given(organizationRepository.findParentOrganizationIdById(cycleA))
                    .willReturn(Optional.of(cycleB));
            given(organizationRepository.findParentOrganizationIdById(cycleB))
                    .willReturn(Optional.of(cycleA));

            // cycleA の祖先チェーンに UNRELATED は現れない → false（かつ無限ループしない）
            assertThat(service.isAncestorOf(UNRELATED_ORG_ID, cycleA)).isFalse();
        }

        @Test
        @DisplayName("maxDepthを超える深い祖先は到達できずfalse")
        void maxDepthを超える深い祖先は到達できずfalse() {
            // maxDepth=2 に絞り、4段の深いチェーン d4→d3→d2→d1→top を作る
            ReflectionTestUtils.setField(service, "maxDepth", 2);
            Long d4 = 6004L;
            Long d3 = 6003L;
            Long d2 = 6002L;
            Long d1 = 6001L;
            Long top = 6000L;
            given(organizationRepository.findParentOrganizationIdById(d4)).willReturn(Optional.of(d3));
            given(organizationRepository.findParentOrganizationIdById(d3)).willReturn(Optional.of(d2));
            given(organizationRepository.findParentOrganizationIdById(d2)).willReturn(Optional.of(d1));
            given(organizationRepository.findParentOrganizationIdById(d1)).willReturn(Optional.of(top));
            given(organizationRepository.findParentOrganizationIdById(top)).willReturn(Optional.empty());

            // d2（2hop以内）は祖先として到達できる
            assertThat(service.isAncestorOf(d2, d4)).isTrue();
            // top（4hop先）は maxDepth=2 を超えるため到達できない
            assertThat(service.isAncestorOf(top, d4)).isFalse();
        }
    }

    @Nested
    @DisplayName("isDescendantOf(descendantOrgId, ancestorOrgId)")
    class IsDescendantOf {

        @Test
        @DisplayName("子は親の子孫である_true")
        void 子は親の子孫である_true() {
            assertThat(service.isDescendantOf(OITA_ORG_ID, KYUSHU_ORG_ID)).isTrue();
        }

        @Test
        @DisplayName("孫も祖先の子孫である_true")
        void 孫も祖先の子孫である_true() {
            assertThat(service.isDescendantOf(OITA_ORG_ID, NATIONAL_ORG_ID)).isTrue();
        }

        @Test
        @DisplayName("無関係な組織は子孫でない_false")
        void 無関係な組織は子孫でない_false() {
            assertThat(service.isDescendantOf(OITA_ORG_ID, UNRELATED_ORG_ID)).isFalse();
        }

        @Test
        @DisplayName("自分自身は自分の子孫でない_false")
        void 自分自身は自分の子孫でない_false() {
            assertThat(service.isDescendantOf(OITA_ORG_ID, OITA_ORG_ID)).isFalse();
        }

        @Test
        @DisplayName("逆方向_親は子の子孫でない_false")
        void 逆方向_親は子の子孫でない_false() {
            assertThat(service.isDescendantOf(NATIONAL_ORG_ID, OITA_ORG_ID)).isFalse();
        }

        @Test
        @DisplayName("null引数は安全にfalseを返す")
        void null引数は安全にfalseを返す() {
            assertThat(service.isDescendantOf(null, NATIONAL_ORG_ID)).isFalse();
            assertThat(service.isDescendantOf(OITA_ORG_ID, null)).isFalse();
        }
    }
}

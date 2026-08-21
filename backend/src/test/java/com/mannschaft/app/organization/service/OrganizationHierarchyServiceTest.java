package com.mannschaft.app.organization.service;

import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;

/**
 * {@link OrganizationHierarchyService} の祖先展開ヘルパー単体テスト（配下配信の土台）。
 *
 * <p>配下配信 AC-12（サイクルで無限ループしない）・AC-11（max-depth 打ち切り）と、
 * 「1 リクエスト内メモ化」の実効性をここで押さえる。フィードに現れるかどうかの検証は
 * {@code TimelineMyFeedControllerIntegrationTest} が実 DB で行う。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrganizationHierarchyService 祖先展開ヘルパー単体テスト")
class OrganizationHierarchyServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private com.mannschaft.app.role.repository.UserRoleRepository userRoleRepository;

    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Mock
    private com.mannschaft.app.common.storage.MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private OrganizationHierarchyService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxDepth", 5);
    }

    /** {@code child → parent} の親リンクを仕込む。 */
    private void parentOf(Long childId, Long parentId) {
        given(organizationRepository.findParentOrganizationIdById(childId))
                .willReturn(Optional.ofNullable(parentId));
    }

    @Nested
    @DisplayName("getAncestorOrgIdsWithDepth")
    class AncestorExpansion {

        @Test
        @DisplayName("直線階層: 距離 1,2,3 が正しく付く（起点自身は含まない）")
        void linearChain_depthsAssigned() {
            parentOf(10L, 20L);
            parentOf(20L, 30L);
            parentOf(30L, 40L);
            parentOf(40L, null);

            Map<Long, Integer> result = service.getAncestorOrgIdsWithDepth(List.of(10L));

            assertThat(result).containsOnlyKeys(20L, 30L, 40L);
            assertThat(result).containsEntry(20L, 1).containsEntry(30L, 2).containsEntry(40L, 3);
            assertThat(result).doesNotContainKey(10L);
        }

        @Test
        @DisplayName("複数起点: 同一祖先には最小距離が採られる")
        void multipleStarts_minDepthWins() {
            // 10 → 20 → 30 、 25 → 30（30 は 10 から距離 2・25 から距離 1）
            parentOf(10L, 20L);
            parentOf(20L, 30L);
            parentOf(25L, 30L);
            parentOf(30L, null);

            Map<Long, Integer> result = service.getAncestorOrgIdsWithDepth(List.of(10L, 25L));

            assertThat(result).containsEntry(30L, 1);
            assertThat(result).containsEntry(20L, 1);
        }

        @Test
        @DisplayName("配AC-11 max-depth: 5 を超える祖先は返らない")
        void maxDepth_truncates() {
            parentOf(1L, 2L);
            parentOf(2L, 3L);
            parentOf(3L, 4L);
            parentOf(4L, 5L);
            parentOf(5L, 6L);
            parentOf(6L, 7L);   // 距離 6 → 対象外
            parentOf(7L, null);

            Map<Long, Integer> result = service.getAncestorOrgIdsWithDepth(List.of(1L));

            assertThat(result).containsKeys(2L, 3L, 4L, 5L, 6L);
            assertThat(result).doesNotContainKey(7L);
            assertThat(result.get(6L)).isEqualTo(5);
        }

        @Test
        @DisplayName("配AC-12 サイクル: 相互参照でも無限ループせず打ち切る")
        void cycle_terminates() {
            // 100 → 200 → 100（サイクル）
            parentOf(100L, 200L);
            parentOf(200L, 100L);

            Map<Long, Integer> result = service.getAncestorOrgIdsWithDepth(List.of(100L));

            // 200 までは辿れるが、100 に戻った時点で打ち切る
            assertThat(result).containsEntry(200L, 1);
            assertThat(result).doesNotContainKey(100L);
        }

        @Test
        @DisplayName("メモ化: 同じ親リンクを複数起点から辿ってもクエリは 1 回まで")
        void parentLinkIsMemoizedWithinRequest() {
            // 10 → 30 、 20 → 30 、 30 → 40
            parentOf(10L, 30L);
            parentOf(20L, 30L);
            parentOf(30L, 40L);
            parentOf(40L, null);

            service.getAncestorOrgIdsWithDepth(List.of(10L, 20L));

            verify(organizationRepository, atMost(1)).findParentOrganizationIdById(30L);
        }

        @Test
        @DisplayName("空・null 入力は空 Map（クエリを発行しない）")
        void emptyInput_returnsEmpty() {
            assertThat(service.getAncestorOrgIdsWithDepth(List.of())).isEmpty();
            assertThat(service.getAncestorOrgIdsWithDepth(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAnchorOrgIdsByTeamIds")
    class AnchorOrgs {

        @Test
        @DisplayName("ACTIVE なチーム所属組織を重複なく返す")
        void returnsActiveAnchorOrgIds() {
            given(teamOrgMembershipRepository.findByTeamIdAndStatus(
                    701L, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of(membership(701L, 801L)));
            given(teamOrgMembershipRepository.findByTeamIdAndStatus(
                    702L, TeamOrgMembershipEntity.Status.ACTIVE))
                    .willReturn(List.of(membership(702L, 801L)));

            assertThat(service.getAnchorOrgIdsByTeamIds(List.of(701L, 702L)))
                    .containsExactly(801L);
        }

        @Test
        @DisplayName("空入力ではクエリを発行せず空リスト")
        void emptyInput_returnsEmpty() {
            assertThat(service.getAnchorOrgIdsByTeamIds(List.of())).isEmpty();
            assertThat(service.getAnchorOrgIdsByTeamIds(null)).isEmpty();
        }

        private TeamOrgMembershipEntity membership(Long teamId, Long orgId) {
            return TeamOrgMembershipEntity.builder()
                    .teamId(teamId)
                    .organizationId(orgId)
                    .status(TeamOrgMembershipEntity.Status.ACTIVE)
                    .invitedAt(java.time.LocalDateTime.now())
                    .build();
        }
    }
}

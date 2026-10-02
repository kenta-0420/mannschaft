package com.mannschaft.app.team;

import com.mannschaft.app.common.visibility.ScopeAncestorResolver;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 §9・AC-M04（部隊 3-F）— 複数加盟が無い（各チームの ACTIVE な親組織が高々1つの）データでは、
 * 単一親前提を外した改修の前後で応答が一致することの契約テスト。
 *
 * <p>「改修前」の挙動は、旧 {@code findOrganizationIdByTeamIdIn} と同じ意味の独立した SQL
 * （{@code team_id → organization_id}、ACTIVE のみ）で再現して基準にする。旧メソッドは 3-F で削除したため、
 * 基準は本テスト内の native SQL である。改修後の各 API（集合・代表親組織・ScopeAncestorResolver）が、
 * 単一親のデータでは基準と<strong>同じ組織</strong>を返すことを確かめる。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 3-F 単一親データでは改修前後で応答が一致する（AC-M04）")
class TeamOrgSingleParentResponseParityIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Autowired
    private TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    @Autowired
    private ScopeAncestorResolver scopeAncestorResolver;

    @PersistenceContext
    private EntityManager em;

    private Long orgA;
    private Long orgB;
    private Long teamInA;
    private Long teamInB;
    private Long teamPendingOnly;
    private Long teamOrphan;

    @BeforeEach
    void setUp() {
        String sfx = String.valueOf(System.nanoTime());
        orgA = insertOrganization("M04 組織A " + sfx, "m04-a-" + sfx);
        orgB = insertOrganization("M04 組織B " + sfx, "m04-b-" + sfx);
        teamInA = insertTeam("M04 チームA所属 " + sfx, "m04-ta-" + sfx);
        teamInB = insertTeam("M04 チームB所属 " + sfx, "m04-tb-" + sfx);
        teamPendingOnly = insertTeam("M04 チームPENDINGのみ " + sfx, "m04-tp-" + sfx);
        teamOrphan = insertTeam("M04 チーム無所属 " + sfx, "m04-to-" + sfx);

        insertMembership(teamInA, orgA, "ACTIVE", 5);
        insertMembership(teamInB, orgB, "ACTIVE", 4);
        insertMembership(teamPendingOnly, orgA, "PENDING", 3);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("AC-M04 チーム→親組織の一括解決は、改修前と同じ組織を返す（単一親では集合が1要素）")
    void 一括解決は改修前と同じ組織() {
        Set<Long> teamIds = Set.of(teamInA, teamInB, teamPendingOnly, teamOrphan);
        Map<Long, Long> before = legacySingleParentMap(teamIds);

        Map<Long, Set<Long>> after = teamOrgMembershipRepository.findOrganizationIdsByTeamIdIn(teamIds);

        assertThat(before).containsOnlyKeys(teamInA, teamInB);
        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((teamId, orgId) -> assertThat(after.get(teamId)).containsExactly(orgId));
    }

    @Test
    @DisplayName("AC-M04 代表親組織の順序つき一括解決も、単一親では改修前と同じ組織だけが並ぶ")
    void 代表親組織の順序つき解決は改修前と同じ() {
        Set<Long> teamIds = Set.of(teamInA, teamInB, teamPendingOnly, teamOrphan);
        Map<Long, Long> before = legacySingleParentMap(teamIds);

        Map<Long, List<Long>> after = teamOrgMembershipRepository.findOrganizationIdsInPrimaryOrderByTeamIdIn(teamIds);

        assertThat(after.keySet()).isEqualTo(before.keySet());
        before.forEach((teamId, orgId) -> assertThat(after.get(teamId)).containsExactly(orgId));
    }

    @Test
    @DisplayName("AC-M04 代表親組織・ACTIVE な親組織一覧は、単一親では改修前の組織と一致し、無所属は空")
    void 代表親組織と親組織一覧は改修前と同じ() {
        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamInA)).contains(orgA);
        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamInB)).contains(orgB);
        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamPendingOnly)).isEmpty();
        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamOrphan)).isEmpty();

        assertThat(teamOrgMembershipQueryService.findActiveOrganizationIds(teamInA)).containsExactly(orgA);
        assertThat(teamOrgMembershipQueryService.findActiveOrganizationIdsInPrimaryOrder(teamInB))
                .containsExactly(orgB);
        assertThat(teamOrgMembershipQueryService.findActiveOrganizationIds(teamOrphan)).isEmpty();
    }

    @Test
    @DisplayName("AC-M04 ScopeAncestorResolver は、単一親では改修前と同じ組織を1要素の集合で返す")
    void スコープ祖先解決は改修前と同じ() {
        ScopeKey a = new ScopeKey("TEAM", teamInA);
        ScopeKey b = new ScopeKey("TEAM", teamInB);
        ScopeKey orphan = new ScopeKey("TEAM", teamOrphan);
        ScopeKey org = new ScopeKey("ORGANIZATION", orgA);

        Map<ScopeKey, Set<Long>> result = scopeAncestorResolver.resolveParentOrgIds(Set.of(a, b, orphan, org));

        assertThat(result.get(a)).containsExactly(orgA);
        assertThat(result.get(b)).containsExactly(orgB);
        assertThat(result).doesNotContainKey(orphan);
        assertThat(result.get(org)).containsExactly(orgA);
    }

    /** 改修前の {@code findOrganizationIdByTeamIdIn} と同じ意味（ACTIVE のみの team_id → organization_id）。 */
    private Map<Long, Long> legacySingleParentMap(Set<Long> teamIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT team_id, organization_id FROM team_org_memberships "
                                + "WHERE team_id IN (:ids) AND status = 'ACTIVE'")
                .setParameter("ids", teamIds)
                .getResultList();
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            Long previous = map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            assertThat(previous).as("このテストの前提: 各チームの ACTIVE な親組織は高々1つ").isNull();
        }
        return map;
    }

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private void insertMembership(Long teamId, Long orgId, String status, int daysAgo) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships ("
                                + "team_id, organization_id, status, invited_at, responded_at, created_at) "
                                + "VALUES (:tid, :oid, :st, DATE_SUB(NOW(), INTERVAL :d DAY), "
                                + "DATE_SUB(NOW(), INTERVAL :d DAY), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("st", status)
                .setParameter("d", daysAgo)
                .executeUpdate();
    }
}

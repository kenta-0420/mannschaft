package com.mannschaft.app.team;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 §9.2 #1・§9.3（部隊 3-A）— 複数組織への同時加盟に対応した
 * チーム→親組織の解決の契約テスト（試練・先行 red）。
 *
 * <ul>
 *   <li>{@link TeamOrgMembershipRepository#findOrganizationIdsByTeamIdIn}: チームごとに
 *       ACTIVE な親組織を<strong>集合で</strong>返す（旧 {@code findOrganizationIdByTeamIdIn} の
 *       {@code HashMap.put} 後勝ちで任意の1件に潰れる欠陥の置き換え）。</li>
 *   <li>{@link TeamOrgMembershipQueryService#findPrimaryParentOrganizationId}: 代表親組織
 *       （最初に成立した ACTIVE 加盟。同時刻なら organization_id 最小）。</li>
 * </ul>
 *
 * <p>team_org_memberships の書き込み API はまだ無いため、行は native INSERT で直接作る。
 * Repository・Service はモックしない。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 3-A チーム→親組織（複数親）解決の契約")
class TeamOrgMultiParentContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Autowired
    private TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    @PersistenceContext
    private EntityManager em;

    private Long orgX;
    private Long orgY;
    private Long orgZ;
    /** X と Y の両方に ACTIVE で加盟するチーム。 */
    private Long teamT;
    /** どの組織にも加盟していないチーム（G131）。 */
    private Long teamOrphan;
    /** Z にだけ ACTIVE で加盟するチーム（単一親の対照）。 */
    private Long teamSingle;

    @BeforeEach
    void setUp() {
        String sfx = String.valueOf(System.nanoTime());
        orgX = insertOrganization("MP契約 組織X " + sfx, "mpc-x-" + sfx);
        orgY = insertOrganization("MP契約 組織Y " + sfx, "mpc-y-" + sfx);
        orgZ = insertOrganization("MP契約 組織Z " + sfx, "mpc-z-" + sfx);
        teamT = insertTeam("MP契約 チームT " + sfx, "mpc-t-" + sfx);
        teamOrphan = insertTeam("MP契約 チーム無所属 " + sfx, "mpc-o-" + sfx);
        teamSingle = insertTeam("MP契約 チーム単一 " + sfx, "mpc-s-" + sfx);

        LocalDateTime base = LocalDateTime.of(2026, 4, 1, 9, 0);
        insertMembership(teamT, orgX, "ACTIVE", base);
        insertMembership(teamT, orgY, "ACTIVE", base.plusDays(1));
        insertMembership(teamSingle, orgZ, "ACTIVE", base);
        em.flush();
        em.clear();
    }

    // =========================================================================
    // findOrganizationIdsByTeamIdIn（§9.2 #1）
    // =========================================================================

    @Test
    @DisplayName("AC-N01(repo) T が X・Y の両方に ACTIVE なら {X, Y} の集合を返す（後勝ちで1件に潰れない）")
    void 複数親は集合で全件返る() {
        Map<Long, Set<Long>> result = teamOrgMembershipRepository.findOrganizationIdsByTeamIdIn(
                Set.of(teamT, teamSingle));

        assertThat(result.get(teamT)).containsExactlyInAnyOrder(orgX, orgY);
        assertThat(result.get(teamSingle)).containsExactly(orgZ);
    }

    @Test
    @DisplayName("PENDING の加盟は親組織に数えない（ACTIVE のみ）")
    void ACTIVE以外は含めない() {
        insertMembership(teamT, orgZ, "PENDING", LocalDateTime.of(2026, 3, 1, 9, 0));
        em.flush();
        em.clear();

        Map<Long, Set<Long>> result = teamOrgMembershipRepository.findOrganizationIdsByTeamIdIn(
                Set.of(teamT));

        assertThat(result.get(teamT)).containsExactlyInAnyOrder(orgX, orgY);
    }

    @Test
    @DisplayName("AC-G131(repo) 親組織が0件のチームは空集合扱い（entry 無しか空 Set）で、例外にならない")
    void 親組織0件は空() {
        Map<Long, Set<Long>> result = teamOrgMembershipRepository.findOrganizationIdsByTeamIdIn(
                Set.of(teamOrphan));

        assertThat(result.getOrDefault(teamOrphan, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("空集合・null を渡すと SQL を発行せず空 Map を返す")
    void 空入力はSQLゼロで空() {
        Statistics stats = statisticsCleared();

        assertThat(teamOrgMembershipRepository.findOrganizationIdsByTeamIdIn(Set.of())).isEmpty();
        assertThat(teamOrgMembershipRepository.findOrganizationIdsByTeamIdIn(null)).isEmpty();

        assertThat(stats.getPrepareStatementCount()).isZero();
    }

    @Test
    @DisplayName("AC-G130(repo) チーム数・親組織数が増えても発行 SQL は1本（IN 句の拡大だけ）")
    void 親組織数に比例してSQLが増えない() {
        Statistics stats = statisticsCleared();

        teamOrgMembershipRepository.findOrganizationIdsByTeamIdIn(Set.of(teamT, teamSingle, teamOrphan));

        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);
    }

    // =========================================================================
    // findPrimaryParentOrganizationId（§9.3）
    // =========================================================================

    @Test
    @DisplayName("§9.3 代表親組織は responded_at が最も古い ACTIVE 加盟（organization_id の大小ではない）")
    void 代表親組織は最初に成立した加盟() {
        // organization_id 最小や挿入順で採る誤実装を弾くため、
        // 先に採番した組織（id が小さい・先に INSERT）との加盟を、後から成立させる。
        String sfx = String.valueOf(System.nanoTime());
        Long olderIdOrg = insertOrganization("MP契約 先採番 " + sfx, "mpc-p1-" + sfx);
        Long newerIdOrg = insertOrganization("MP契約 後採番 " + sfx, "mpc-p2-" + sfx);
        Long team = insertTeam("MP契約 代表判定 " + sfx, "mpc-pt-" + sfx);
        insertMembership(team, olderIdOrg, "ACTIVE", LocalDateTime.of(2026, 5, 2, 9, 0));
        insertMembership(team, newerIdOrg, "ACTIVE", LocalDateTime.of(2026, 5, 1, 9, 0));
        em.flush();
        em.clear();

        assertThat(olderIdOrg).isLessThan(newerIdOrg);
        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(team))
                .contains(newerIdOrg);
    }

    @Test
    @DisplayName("§9.3 responded_at が同時刻なら organization_id が最小の加盟を採る")
    void 同時刻ならorganizationId最小() {
        String sfx = String.valueOf(System.nanoTime());
        Long a = insertOrganization("MP契約 同時A " + sfx, "mpc-sa-" + sfx);
        Long b = insertOrganization("MP契約 同時B " + sfx, "mpc-sb-" + sfx);
        Long team = insertTeam("MP契約 同時刻 " + sfx, "mpc-st-" + sfx);
        LocalDateTime same = LocalDateTime.of(2026, 5, 1, 9, 0);
        // 挿入順（PK 順）と organization_id 順を逆にし、挿入順で選ぶ誤実装を弾く。
        insertMembership(team, Math.max(a, b), "ACTIVE", same);
        insertMembership(team, Math.min(a, b), "ACTIVE", same);
        em.flush();
        em.clear();

        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(team))
                .contains(Math.min(a, b));
    }

    @Test
    @DisplayName("§9.3 より古い PENDING 加盟は代表親組織に選ばれない（ACTIVE のみ）")
    void PENDINGは代表に選ばれない() {
        insertMembership(teamT, orgZ, "PENDING", LocalDateTime.of(2026, 1, 1, 9, 0));
        em.flush();
        em.clear();

        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamT))
                .contains(orgX);
    }

    @Test
    @DisplayName("§9.3 同じデータで10回呼んでも同じ代表親組織を返す（決定性・AC-N14 の土台）")
    void 代表親組織は決定的() {
        Optional<Long> first = teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamT);
        assertThat(first).contains(orgX);
        for (int i = 0; i < 10; i++) {
            em.clear();
            assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamT))
                    .isEqualTo(first);
        }
    }

    @Test
    @DisplayName("§9.3 responded_at が NULL の ACTIVE 加盟は created_at で代替して比べる（NULL を最初の加盟扱いにしない）")
    void respondedAtがNULLならcreatedAtで代替() {
        String sfx = String.valueOf(System.nanoTime());
        Long nullOrg = insertOrganization("MP契約 NULL側 " + sfx, "mpc-n1-" + sfx);
        Long setOrg = insertOrganization("MP契約 非NULL側 " + sfx, "mpc-n2-" + sfx);
        Long lateNull = insertTeam("MP契約 NULL後 " + sfx, "mpc-nt1-" + sfx);
        Long earlyNull = insertTeam("MP契約 NULL先 " + sfx, "mpc-nt2-" + sfx);
        // NULL 行の created_at が非 NULL 行の responded_at より後 → 非 NULL 側が先に成立した加盟。
        insertActiveMembershipWithNullRespondedAt(lateNull, nullOrg, LocalDateTime.of(2026, 5, 10, 9, 0));
        insertMembership(lateNull, setOrg, "ACTIVE", LocalDateTime.of(2026, 5, 1, 9, 0));
        // NULL 行の created_at が非 NULL 行の responded_at より前 → NULL 側（created_at）が先。
        insertActiveMembershipWithNullRespondedAt(earlyNull, nullOrg, LocalDateTime.of(2026, 4, 1, 9, 0));
        insertMembership(earlyNull, setOrg, "ACTIVE", LocalDateTime.of(2026, 5, 1, 9, 0));
        em.flush();
        em.clear();

        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(lateNull)).contains(setOrg);
        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(earlyNull)).contains(nullOrg);
    }

    @Test
    @DisplayName("AC-G131 親組織が0件のチームの代表親組織は空（例外にならない）")
    void 親組織0件なら代表は空() {
        assertThat(teamOrgMembershipQueryService.findPrimaryParentOrganizationId(teamOrphan)).isEmpty();
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private Statistics statisticsCleared() {
        em.flush();
        em.clear();
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
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

    /** 書き込み API が未実装のため、加盟行を直接 INSERT する（responded_at = 加盟の成立時刻）。 */
    private void insertActiveMembershipWithNullRespondedAt(Long teamId, Long orgId, LocalDateTime createdAt) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships ("
                                + "team_id, organization_id, status, invited_at, responded_at, created_at) "
                                + "VALUES (:tid, :oid, 'ACTIVE', :created, NULL, :created)")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("created", createdAt)
                .executeUpdate();
    }

    private void insertMembership(Long teamId, Long orgId, String status, LocalDateTime respondedAt) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships ("
                                + "team_id, organization_id, status, invited_at, responded_at, created_at) "
                                + "VALUES (:tid, :oid, :st, :invited, :responded, NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("st", status)
                .setParameter("invited", respondedAt.minusDays(1))
                // PENDING にもわざと古い responded_at を入れ、status を見ずに時刻だけで選ぶ誤実装を弾く。
                .setParameter("responded", respondedAt)
                .executeUpdate();
    }
}

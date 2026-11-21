package com.mannschaft.app.tournament;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse;
import com.mannschaft.app.tournament.service.ContactAccessLevel;
import com.mannschaft.app.tournament.service.ParticipantContactSpaceFacade;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261118-0900 参加者向け連絡スペース一覧 — AC-P20（クエリ本数が部門数 D・参加チーム数に依存しない）の試練（red）。
 *
 * <p><b>測定範囲</b>: {@link ParticipantContactSpaceFacade#listForParticipant} の 1 呼び出し（軍議の懸念 4）。
 * {@link SqlIntentCounter#captureCurrentThread()} で現在スレッドの SQL だけを数え、非同期処理の SQL を混ぜない。
 * 各シナリオで 1 回空打ちしてから測る（初回のメタデータ解決・キャッシュ充填を除く）。</p>
 *
 * <p><b>主アサーション</b>: 同一権限経路（代表 a1・一般 a3 それぞれ）で、D=1/3 × 参加チーム 2/40 の 4 通りの
 * 総本数と意図別本数（{@code tournament_participants}・{@code tournament_contact_space}・{@code teams}・
 * {@code memberships}・{@code user_roles}・{@code tournaments}・{@code tournament_divisions}）がすべて同じ
 * （戦役台帳「P20 強化」）。定数上限 {@link #PROVISIONAL_CEILING} は設計書 §10.3.5 の推測（9〜12 本）による
 * <b>仮置き</b>で、実装時の実測で確定して書き換える（D・チーム数に依存する式にはしない）。</p>
 *
 * <p><b>red の根拠</b>: 骨格の Facade は {@code UnsupportedOperationException} を投げる。</p>
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261118-0900 AC-P20 参加者向け一覧のクエリ本数は D・参加チーム数に依存しない（実DB・試練）")
class ParticipantContactSpaceQueryCountIT extends AbstractMySqlIntegrationTest {

    /** 仮置きの定数上限（実測で確定する。D・参加チーム数に依存する式にしない）。 */
    private static final int PROVISIONAL_CEILING = 12;

    private static final List<String> INTENTS = List.of(
            "tournament_participants", "tournament_contact_space", "teams", "memberships", "user_roles",
            "tournaments", "tournament_divisions");

    @Autowired private ParticipantContactSpaceFacade facade;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> tournamentIds = new ArrayList<>();
    private final List<Long> divisionIds = new ArrayList<>();
    private Long orgId;

    private String slugA;
    private Long a1;
    private Long a3;
    /** シナリオ名 → 大会 ID（D=1/3 × 参加チーム 2/40）。 */
    private final Map<String, Long> scenarios = new LinkedHashMap<>();
    /** シナリオ名 → D。 */
    private final Map<String, Integer> divisionCount = new LinkedHashMap<>();

    @BeforeAll
    void fixtureを別TXでcommitする() {
        new TransactionTemplate(txManager).execute(status -> {
            String s = UUID.randomUUID().toString().substring(0, 8);
            a1 = user("A代表");
            a3 = user("A一般");
            Long organizer = user("主催管理者");
            orgId = organizations.save(OrganizationEntity.builder()
                    .slug("cmp0900p-" + s).name("CMP0900P 主催組織 " + s)
                    .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false)
                    .build()).getId();
            MembershipTestHelper.insertMembership(em, organizer, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, organizer, "ADMIN", null, orgId);

            slugA = "cmp0900p-" + UUID.randomUUID().toString().substring(0, 8);
            Long teamA = team(slugA);
            MembershipTestHelper.insertMembership(em, a1, ScopeType.TEAM, teamA, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, a1, "ADMIN", teamA, null);
            MembershipTestHelper.insertMembership(em, a3, ScopeType.TEAM, teamA, RoleKind.MEMBER);

            // A 以外の参加チーム 39（各 1 名の代表つき。代表判定・会員判定の集約が件数で膨らまないことを見る）
            List<Long> others = new ArrayList<>();
            for (int i = 0; i < 39; i++) {
                Long teamId = team("cmp0900p-" + UUID.randomUUID().toString().substring(0, 8));
                Long admin = user("他チーム代表" + i);
                MembershipTestHelper.insertMembership(em, admin, ScopeType.TEAM, teamId, RoleKind.MEMBER);
                MembershipTestHelper.insertUserRole(em, admin, "ADMIN", teamId, null);
                others.add(teamId);
            }

            for (int d : List.of(1, 3)) {
                for (int teamsTotal : List.of(2, 40)) {
                    String name = "D=" + d + "/teams=" + teamsTotal;
                    Long t = tournament(organizer, "CMP0900P " + name + " " + s);
                    List<Long> divs = new ArrayList<>();
                    for (int k = 0; k < d; k++) {
                        divs.add(division(t, "CMP0900P " + name + " D" + k + " " + s));
                    }
                    space("TOURNAMENT", t, "BULLETIN");
                    space("TOURNAMENT", t, "CHAT");
                    for (Long div : divs) {
                        participant(div, teamA);
                        space("TOURNAMENT_DIVISION", div, "BULLETIN");
                        space("TOURNAMENT_DIVISION", div, "CHAT");
                    }
                    for (int i = 0; i < teamsTotal - 1; i++) {
                        participant(divs.get(i % divs.size()), others.get(i));
                    }
                    scenarios.put(name, t);
                    divisionCount.put(name, d);
                }
            }
            return null;
        });
    }

    @AfterAll
    void 所有行だけ片付ける() {
        for (Long id : tournamentIds) {
            jdbc.update("DELETE FROM tournament_contact_space WHERE scope_type = 'TOURNAMENT' AND scope_id = ?", id);
        }
        for (Long id : divisionIds) {
            jdbc.update("DELETE FROM tournament_contact_space WHERE scope_type = 'TOURNAMENT_DIVISION' AND scope_id = ?", id);
            jdbc.update("DELETE FROM tournament_participants WHERE division_id = ?", id);
            jdbc.update("DELETE FROM tournament_divisions WHERE id = ?", id);
        }
        for (Long id : tournamentIds) {
            jdbc.update("DELETE FROM tournaments WHERE id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", id);
        }
        for (Long id : teamIds) {
            jdbc.update("DELETE FROM teams WHERE id = ?", id);
        }
        if (orgId != null) {
            jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("P20: 代表 a1 の経路で、D=1/3 × 参加チーム 2/40 の総本数・意図別本数が同じで、仮上限以下")
    void P20_代表の経路はDとチーム数に依存しない() {
        assertConstant(a1, ContactAccessLevel.POST);
    }

    @Test
    @DisplayName("P20: 一般 a3 の経路で、D=1/3 × 参加チーム 2/40 の総本数・意図別本数が同じで、仮上限以下")
    void P20_一般の経路はDとチーム数に依存しない() {
        assertConstant(a3, ContactAccessLevel.VIEW);
    }

    private void assertConstant(Long actor, ContactAccessLevel expectedLevel) {
        Map<String, Measured> measured = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : scenarios.entrySet()) {
            int d = divisionCount.get(e.getKey());
            ParticipantContactSpacesResponse warm = facade.listForParticipant(slugA, e.getValue(), actor);
            assertThat(warm.spaces()).as("前提: %s で 2+2D 件", e.getKey()).hasSize(2 + 2 * d);
            assertThat(warm.spaces()).as("前提: %s の水準", e.getKey())
                    .allSatisfy(s -> assertThat(s.accessLevel()).isEqualTo(expectedLevel));
            measured.put(e.getKey(), measure(actor, e.getValue()));
        }
        System.out.printf("AC-P20 実測 actor=%s %s%n", actor, measured);

        Measured baseline = measured.values().iterator().next();
        for (Map.Entry<String, Measured> e : measured.entrySet()) {
            assertThat(e.getValue().total())
                    .as("総本数が D・参加チーム数で変わらない（%s vs 基準 %s）。全測定=%s", e.getKey(), baseline, measured)
                    .isEqualTo(baseline.total());
            assertThat(e.getValue().byIntent())
                    .as("意図別本数が D・参加チーム数で変わらない（%s）。全測定=%s", e.getKey(), measured)
                    .isEqualTo(baseline.byIntent());
            assertThat(e.getValue().total())
                    .as("仮の定数上限 %d 以下（%s）。SQL=%s", PROVISIONAL_CEILING, e.getKey(), e.getValue().sqls())
                    .isLessThanOrEqualTo(PROVISIONAL_CEILING);
        }
    }

    private Measured measure(Long actor, Long tournamentId) {
        try (SqlIntentCounter.CurrentThreadCapture capture = SqlIntentCounter.captureCurrentThread()) {
            facade.listForParticipant(slugA, tournamentId, actor);
            List<String> sqls = capture.capturedSqls();
            Map<String, Long> byIntent = new LinkedHashMap<>();
            for (String intent : INTENTS) {
                // "tournament_participants" が "tournaments" に部分一致しないよう、識別子境界で数える
                byIntent.put(intent, sqls.stream().filter(sql -> mentions(sql, intent)).count());
            }
            return new Measured(capture.totalCount(), byIntent, sqls);
        }
    }

    private static boolean mentions(String sql, String table) {
        return java.util.regex.Pattern.compile("(?i)(^|[^a-z0-9_])" + table + "([^a-z0-9_]|$)").matcher(sql).find();
    }

    private record Measured(int total, Map<String, Long> byIntent, List<String> sqls) {
        @Override
        public String toString() {
            return "{total=" + total + ", byIntent=" + byIntent + "}";
        }
    }

    // ------------------------------------------------------------------------

    private Long user(String label) {
        Long id = users.save(UserEntity.builder().email("cmp0900p-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long team(String slug) {
        Long id = teams.save(TeamEntity.builder().slug(slug).name("CMP0900P " + slug)
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
        teamIds.add(id);
        return id;
    }

    private Long tournament(Long createdBy, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournaments (organization_id, name, format, sport, "
                                + "win_points, draw_points, loss_points, has_draw, has_sets, "
                                + "has_extra_time, has_penalties, score_unit_label, league_round_type, "
                                + "knockout_legs, visibility, status, version, created_by, created_at, updated_at) "
                                + "VALUES (:orgId, :name, 'LEAGUE', 'SOCCER', 3, 1, 0, 1, 0, 0, 0, '点', 'SINGLE', "
                                + "1, 'PUBLIC', 'OPEN', 0, :createdBy, NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM tournaments WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
        tournamentIds.add(id);
        return id;
    }

    private Long division(Long tid, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournament_divisions (tournament_id, name, level, promotion_slots, "
                                + "relegation_slots, playoff_promotion_slots, sort_order, created_at, updated_at) "
                                + "VALUES (:tid, :name, 1, 0, 0, 0, 0, NOW(), NOW())")
                .setParameter("tid", tid)
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM tournament_divisions WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
        divisionIds.add(id);
        return id;
    }

    private void participant(Long divId, Long teamId) {
        em.createNativeQuery("INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                        + "VALUES (:divId, :teamId, 'ACTIVE', NOW())")
                .setParameter("divId", divId)
                .setParameter("teamId", teamId)
                .executeUpdate();
    }

    private void space(String scopeType, Long scopeId, String kind) {
        em.createNativeQuery(
                        "INSERT INTO tournament_contact_space (id, scope_type, scope_id, space_kind, ref_id, is_public, "
                                + "created_at, updated_at, deleted_at) "
                                + "VALUES (UNHEX(REPLACE(:id, '-', '')), :st, :sid, :kind, 0, 0, NOW(), NOW(), NULL)")
                .setParameter("id", UUID.randomUUID().toString())
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("kind", kind)
                .executeUpdate();
    }
}

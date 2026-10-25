package com.mannschaft.app.template;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.template.repository.ScopeFeatureDisplayPolicyRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-8/9: fixtureを明示commitし、実MySQLの途中失敗とAFTER_COMMIT監査を観測する。
 * 内部Bean/DB/認可はモックせず、失敗は所有テーブルの一時TRIGGERから注入する。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScopeFeatureDisplayPolicyCommitIT extends AbstractMySqlIntegrationTest {
    private static final String EVENT = "SCOPE_FEATURE_DISPLAY_POLICY_UPDATED";
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private ScopeFeatureDisplayPolicyRepository policies;
    private Long actor;
    private Long teamId;
    private Long membershipId;
    private Long userRoleId;
    private String path;
    private String trigger;

    @BeforeEach
    void fixtureをcommitする() {
        transactions.executeWithoutResult(tx -> {
            actor = users.saveAndFlush(FeatureDisplayPolicyTestFixture.user()).getId();
            var team = teams.saveAndFlush(FeatureDisplayPolicyTestFixture.team());
            teamId = team.getId();
            path = "/api/v1/teams/" + team.getSlug() + "/feature-display-policies";
            RoleEntity admin = roles.findByName("ADMIN").orElseGet(() -> roles.saveAndFlush(RoleEntity.builder()
                    .name("ADMIN").displayName("ADMIN").priority(2).isSystem(true).build()));
            membershipId = memberships.saveAndFlush(MembershipEntity.builder().userId(actor)
                    .scopeType(ScopeType.TEAM).scopeId(teamId).roleKind(RoleKind.MEMBER).build()).getId();
            userRoleId = userRoles.saveAndFlush(UserRoleEntity.builder().userId(actor).roleId(admin.getId())
                    .teamId(teamId).grantedBy(actor).build()).getId();
        });
        trigger = "sfdp_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void 所有fixtureだけを片付ける() throws SQLException {
        if (trigger != null) {
            triggerDdl("DROP TRIGGER IF EXISTS " + trigger);
        }
        if (teamId == null) {
            return;
        }
        transactions.executeWithoutResult(tx -> {
            policies.deleteAll(policies.findByScopeTypeAndScopeId(FeatureDisplayScope.TEAM, teamId));
            jdbc.update("DELETE FROM audit_logs WHERE event_type = ? AND team_id = ?", EVENT, teamId);
            userRoles.deleteById(userRoleId);
            memberships.deleteById(membershipId);
            teams.deleteById(teamId);
            users.deleteById(actor);
        });
    }

    @Test
    void AC8_二件目の実DB失敗は先行書込もrollbackし監査を確定しない() throws Exception {
        // TRIGGERの識別子は試験が生成した固定形式だけで、利用者入力をSQLへ連結しない。
        triggerDdl("CREATE TRIGGER " + trigger + " BEFORE INSERT ON scope_feature_display_policies FOR EACH ROW "
                + "BEGIN IF NEW.scope_type = 'TEAM' AND NEW.scope_id = " + teamId
                + " AND NEW.feature_key = 'schedule' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'sfdp-injected'; END IF; END");
        var result = mvc.perform(put(path).with(user(actor.toString())).contentType("application/json")
                .content("{\"features\":[" + item("schedule", false) + "," + item("chat", false) + "]}"))
                .andExpect(status().is5xxServerError()).andReturn();
        assertThat(result.getResolvedException()).hasStackTraceContaining("sfdp-injected");
        assertThat(policies.findByScopeTypeAndScopeId(FeatureDisplayScope.TEAM, teamId)).isEmpty();
        assertThat(auditCount()).isZero();
    }

    @Test
    void AC8_AC9_成功commitのみ監査し再表示は同じ行を更新する() throws Exception {
        transactions.executeWithoutResult(tx -> {
            try {
                mvc.perform(put(path).with(user(actor.toString())).contentType("application/json")
                        .content("{\"features\":[" + item("schedule", false) + "]}"))
                        .andExpect(status().isOk());
            } catch (Exception ex) {
                throw new IllegalStateException("表示設定の保存要求に失敗しました", ex);
            }
            assertThat(auditCount()).isZero();
        });
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(1));
        var original = policies.findByScopeTypeAndScopeId(FeatureDisplayScope.TEAM, teamId).getFirst();
        mvc.perform(put(path).with(user(actor.toString())).contentType("application/json")
                .content("{\"features\":[" + item("schedule", true) + "]}"))
                .andExpect(status().isOk());
        var after = policies.findByScopeTypeAndScopeId(FeatureDisplayScope.TEAM, teamId).getFirst();
        assertThat(after.getId()).isEqualTo(original.getId());
        assertThat(after.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(after.isMemberVisible()).isTrue();
        mvc.perform(get(path).with(user(actor.toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.features[?(@.featureKey == 'schedule')].memberVisible").value(true));
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(2));
    }

    private String item(String key, boolean visible) {
        return "{\"featureKey\":\"" + key + "\",\"memberVisible\":" + visible + ",\"supporterVisible\":true}";
    }

    @Test
    void AC1_同じキーへの初回並行保存はunique違反にならない() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> parallelPut(start, false));
            var second = pool.submit(() -> parallelPut(start, true));
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(policies.findByScopeTypeAndScopeId(FeatureDisplayScope.TEAM, teamId)).hasSize(1);
        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(auditCount()).isEqualTo(2));
    }

    private int parallelPut(CountDownLatch start, boolean visible) throws Exception {
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(put(path).with(user(actor.toString())).contentType("application/json")
                .content("{\"features\":[" + item("schedule", visible) + "]}"))
                .andReturn().getResponse().getStatus();
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE event_type = ? AND team_id = ?", Long.class, EVENT, teamId);
    }

    /** binary log有効時のTRIGGER作成だけは共有Testcontainerのroot接続を使う。対象は所有scopeに限定する。 */
    private void triggerDdl(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}

package com.mannschaft.app.team;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.auth.service.StatusClaimResolver;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260902-0059: 実JWT・SecurityFilterChain・実認可・実MySQLによる復元境界の補完。
 *
 * <p>既存TeamCoreAuthzContractITのfilter除外4件は保持し、本classで認証境界も通す。
 * AuthService.login自身やValkey失効は対象外。内部Beanのmock/spyや認証context手注入は行わない。
 * 共有基底の外部Redis mockだけを継承する。fixtureはcommitし、更新を新transactionで読み直す。</p>
 *
 * <p>候補段階: RANDOM_PORT再宣言とDirtiesContextはBase性能規約との適用判断待ち。
 * 正本/既承認根拠とrootレビュー後に適用し、現時点で新CI・pushは行わない。</p>
 */
// 構成判断待ち: @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 構成判断待ち: @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc(addFilters = true)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260902-0059: 実JWTとSecurityFilterによるチーム復元契約")
class TeamRestoreSecurityFilterContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private RoleClaimResolver roleClaimResolver;
    @Autowired private StatusClaimResolver statusClaimResolver;
    @Autowired private TeamRepository teamRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;
    @PersistenceContext private EntityManager em;

    private final List<Long> ownedTeamIds = new ArrayList<>();
    private final List<Long> ownedUserIds = new ArrayList<>();
    private Long teamAId;
    private String teamASlug;
    private String absentSlug;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;
    private Long systemAdminId;

    enum NonSystemActor { TEAM_ADMIN, OTHER_TEAM_ADMIN, MEMBER, OUTSIDER }

    @BeforeEach
    void setUp() {
        inTransaction(() -> {
            teamAId = saveTeam();
            teamASlug = teamRepository.findById(teamAId).orElseThrow().getSlug();
            Long teamBId = saveTeam();
            absentSlug = "absent-" + UUID.randomUUID().toString().substring(0, 20);
            Number absentCount = (Number) em.createNativeQuery("SELECT COUNT(*) FROM teams WHERE slug = :slug")
                    .setParameter("slug", absentSlug).getSingleResult();
            assertThat(absentCount.longValue()).isZero();
            adminAId = saveUser();
            adminBId = saveUser();
            memberAId = saveUser();
            outsiderId = saveUser();
            systemAdminId = saveUser();
            MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
            MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
            MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);
            em.flush();
            return null;
        });
    }

    @AfterEach
    void cleanUp() {
        // commit後の実audit/activity書込が終わってから自分の行だけを消す。配送成功の主張には使わない。
        await().atMost(Duration.ofSeconds(15)).until(() -> eventPool.getActiveCount() == 0
                && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
        inTransaction(() -> {
            for (Long teamId : ownedTeamIds) {
                executeDelete("DELETE FROM activity_feed WHERE scope_type = 'TEAM' AND scope_id = :id", teamId);
                executeDelete("DELETE FROM audit_logs WHERE team_id = :id", teamId);
                executeDelete("DELETE FROM user_calendar_layer_settings WHERE scope_type = 'TEAM' AND scope_id = :id", teamId);
                executeDelete("DELETE FROM schedules WHERE team_id = :id", teamId);
                executeDelete("DELETE FROM user_roles WHERE team_id = :id", teamId);
                executeDelete("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :id", teamId);
                executeDelete("DELETE FROM teams WHERE id = :id", teamId);
            }
            for (Long userId : ownedUserIds) {
                executeDelete("DELETE FROM audit_logs WHERE user_id = :id OR target_user_id = :id", userId);
                executeDelete("DELETE FROM user_calendar_layer_settings WHERE user_id = :id", userId);
                executeDelete("DELETE FROM user_roles WHERE user_id = :id", userId);
                executeDelete("DELETE FROM memberships WHERE user_id = :id", userId);
                executeDelete("DELETE FROM users WHERE id = :id", userId);
            }
            return null;
        });
        ownedTeamIds.clear();
        ownedUserIds.clear();
    }

    @Test
    void 実JWTで削除済チームを復元して予定を掃除すると元利用者のcalendarから消える() throws Exception {
        LocalDateTime from = LocalDateTime.of(2030, 1, 1, 0, 0);
        LocalDateTime to = from.plusDays(1);
        Long scheduleId = inTransaction(() -> scheduleRepository.saveAndFlush(
                TeamRestoreTestFixture.schedule(teamAId, adminAId, from.plusHours(9))).getId());
        long roleCount = inTransaction(() -> userRoleRepository.countByTeamId(teamAId));
        mockMvc.perform(get("/api/v1/my/calendar").header("Authorization", bearer(adminAId))
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.scheduleId == " + scheduleId + ")]").isNotEmpty());
        mockMvc.perform(delete("/api/v1/teams/{slug}", teamASlug).header("Authorization", bearer(adminAId)))
                .andExpect(status().isNoContent());
        mockMvc.perform(patch("/api/v1/teams/{slug}/restore", teamASlug).header("Authorization", bearer(systemAdminId)))
                .andExpect(status().isNoContent());
        inTransaction(() -> {
            assertThat(teamRepository.findById(teamAId).orElseThrow().getVisibility())
                    .isEqualTo(TeamEntity.Visibility.PUBLIC);
            assertThat(userRoleRepository.countByTeamId(teamAId)).isEqualTo(roleCount);
            return null;
        });
        mockMvc.perform(delete("/api/v1/teams/{slug}/schedules/{id}", teamASlug, scheduleId)
                        .header("Authorization", bearer(outsiderId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/teams/{slug}/schedules/{id}", teamASlug, scheduleId)
                        .header("Authorization", bearer(systemAdminId)).param("updateScope", "THIS_ONLY"))
                .andExpect(status().isNoContent());
        assertThat(inTransaction(() -> scheduleRepository.findById(scheduleId))).isEmpty();
        mockMvc.perform(delete("/api/v1/teams/{slug}", teamASlug).header("Authorization", bearer(systemAdminId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/my/calendar").header("Authorization", bearer(adminAId))
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.scheduleId == " + scheduleId + ")]").isEmpty());
        mockMvc.perform(delete("/api/v1/teams/{slug}", teamASlug).header("Authorization", bearer(adminAId)))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest(name = "実JWTの{0}は不存在照会より先に403")
    @EnumSource(NonSystemActor.class)
    void 非SYSの実JWTは不存在チームの復元を403で拒否する(NonSystemActor actor) throws Exception {
        Long userId = switch (actor) {
            case TEAM_ADMIN -> adminAId;
            case OTHER_TEAM_ADMIN -> adminBId;
            case MEMBER -> memberAId;
            case OUTSIDER -> outsiderId;
        };
        TargetSnapshot before = snapshot();
        mockMvc.perform(patch("/api/v1/teams/{slug}/restore", absentSlug).header("Authorization", bearer(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void SYSの実JWTで不存在チームの復元は404の既存契約を保つ() throws Exception {
        TargetSnapshot before = snapshot();
        mockMvc.perform(patch("/api/v1/teams/{slug}/restore", absentSlug).header("Authorization", bearer(systemAdminId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TEAM_001"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void SYSの実JWTで未削除チームの復元は409TEAM006の既存契約を保つ() throws Exception {
        TargetSnapshot before = snapshot();
        mockMvc.perform(patch("/api/v1/teams/{slug}/restore", teamASlug).header("Authorization", bearer(systemAdminId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TEAM_006"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 実JWTで復元してもPROVISIONEDと非公開範囲を保ち通常導線から隠す() throws Exception {
        inTransaction(() -> {
            TeamEntity team = teamRepository.findById(teamAId).orElseThrow();
            teamRepository.saveAndFlush(team.toBuilder().lifecycleStatus(TeamEntity.LifecycleStatus.PROVISIONED)
                    .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).deletedAt(LocalDateTime.now()).build());
            return null;
        });
        mockMvc.perform(patch("/api/v1/teams/{slug}/restore", teamASlug).header("Authorization", bearer(systemAdminId)))
                .andExpect(status().isNoContent());
        inTransaction(() -> {
            TeamEntity restored = teamRepository.findById(teamAId).orElseThrow();
            assertThat(restored.getLifecycleStatus()).isEqualTo(TeamEntity.LifecycleStatus.PROVISIONED);
            assertThat(restored.getVisibility()).isEqualTo(TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            return null;
        });
        mockMvc.perform(get("/api/v1/teams/{slug}", teamASlug).header("Authorization", bearer(systemAdminId)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/teams/{slug}/schedules", teamASlug).header("Authorization", bearer(systemAdminId))
                        .param("from", "2030-01-01T00:00:00").param("to", "2030-01-02T00:00:00"))
                .andExpect(status().isNotFound());
    }

    @Test
    void JWT無しの復元は401で対象チームと予定を更新しない() throws Exception {
        inTransaction(() -> {
            scheduleRepository.saveAndFlush(TeamRestoreTestFixture.schedule(teamAId, adminAId,
                    LocalDateTime.of(2030, 1, 1, 9, 0)));
            TeamEntity team = teamRepository.findById(teamAId).orElseThrow();
            teamRepository.saveAndFlush(team.toBuilder().deletedAt(LocalDateTime.now()).build());
            return null;
        });
        TargetSnapshot before = snapshot();
        // 認証context/Authorization/cookieを設定しない。実FilterChainの匿名境界を確認する。
        mockMvc.perform(patch("/api/v1/teams/{slug}/restore", teamASlug)).andExpect(status().isUnauthorized());
        assertThat(snapshot()).isEqualTo(before);
    }

    private Long saveTeam() {
        TeamEntity team = teamRepository.saveAndFlush(TeamRestoreSecurityFilterTestFixture.team());
        ownedTeamIds.add(team.getId());
        return team.getId();
    }

    private Long saveUser() {
        Long userId = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        assertThat(userId).isPositive();
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM users WHERE id = :id")
                .setParameter("id", userId).getSingleResult();
        assertThat(count.longValue()).isZero();
        MembershipTestHelper.insertActiveUser(em, userId);
        ownedUserIds.add(userId);
        return userId;
    }

    private String bearer(Long userId) {
        return "Bearer " + authTokenService.issueAccessToken(userId, roleClaimResolver.resolveRoles(userId),
                statusClaimResolver.isPendingParentalConsent(userId));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(txManager).execute(status -> {
            em.clear();
            return action.get();
        });
    }

    private void executeDelete(String sql, Long id) {
        em.createNativeQuery(sql).setParameter("id", id).executeUpdate();
    }

    private TargetSnapshot snapshot() {
        return inTransaction(() -> new TargetSnapshot(
                rows("SELECT id, deleted_at, lifecycle_status, visibility FROM teams WHERE id = :id", teamAId),
                rows("SELECT id, deleted_at, status, title, start_at, end_at FROM schedules WHERE team_id = :id ORDER BY id", teamAId),
                rows("SELECT id, user_id, role_id, team_id, organization_id FROM user_roles WHERE team_id = :id ORDER BY id", teamAId)));
    }

    @SuppressWarnings("unchecked")
    private List<List<Object>> rows(String sql, Long id) {
        List<Object[]> result = em.createNativeQuery(sql).setParameter("id", id).getResultList();
        return result.stream().map(row -> Arrays.asList(row)).toList();
    }

    private record TargetSnapshot(List<List<Object>> team, List<List<Object>> schedules,
                                  List<List<Object>> roles) { }
}
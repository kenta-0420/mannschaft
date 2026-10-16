package com.mannschaft.app.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.auth.service.StatusClaimResolver;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CMP-260902-0059の復元・孤児予定掃除を実TCPのHTTPクライアントで検証する。
 *
 * <p>HTTP往復が目的なので共通基底のWebEnvironmentだけをRANDOM_PORTへ上書きする。
 * 実JWT・Securityフィルタ・認可・MySQL・commitを通す実HTTP契約試験であり、UI実機・探索の代替ではない。
 * AuthService.loginとValkey実機は検証しない。共通の外部Redis mockのみ継承し、専用contextは終了時に閉じる。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260902-0059: チーム復元と予定掃除の実HTTP契約")
class TeamRestoreHttpContractIT extends AbstractMySqlIntegrationTest {

    @LocalServerPort private int port;
    @Autowired private TeamRepository teamRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private RoleClaimResolver roleClaimResolver;
    @Autowired private StatusClaimResolver statusClaimResolver;
    @Autowired private ObjectMapper objectMapper;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;
    @PersistenceContext private EntityManager em;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1).build();
    private Fixture fixture;

    @Test
    void 実HTTPで復元後の予定を掃除すると元利用者のcalendarから消える() throws Exception {
        fixture = inTransaction(() -> {
            var team = teamRepository.saveAndFlush(TeamRestoreSecurityFilterTestFixture.team());
            Long admin = newUser();
            Long systemAdmin = newUser();
            Long outsider = newUser();
            MembershipTestHelper.insertUserRole(em, admin, "ADMIN", team.getId(), null);
            MembershipTestHelper.insertMembership(em, admin, ScopeType.TEAM, team.getId(), RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, systemAdmin, "SYSTEM_ADMIN", null, null);
            var schedule = scheduleRepository.saveAndFlush(TeamRestoreTestFixture.schedule(team.getId(), admin,
                    LocalDateTime.of(2030, 1, 1, 9, 0)));
            return new Fixture(team.getId(), team.getSlug(), admin, systemAdmin, outsider, schedule.getId());
        });
        String teamUrl = "/api/v1/teams/" + fixture.slug();
        String scheduleUrl = teamUrl + "/schedules/" + fixture.scheduleId();
        String calendarUrl = "/api/v1/my/calendar?from=2030-01-01T00:00:00&to=2030-01-02T00:00:00";
        assertThat(calendarContainsSchedule(request("GET", calendarUrl, fixture.admin()))).isTrue();
        assertThat(request("DELETE", teamUrl, fixture.admin()).statusCode()).isEqualTo(204);
        assertThat(request("PATCH", teamUrl + "/restore", fixture.systemAdmin()).statusCode()).isEqualTo(204);
        assertThat(request("DELETE", scheduleUrl, fixture.outsider()).statusCode()).isEqualTo(403);
        assertThat(request("DELETE", scheduleUrl + "?updateScope=THIS_ONLY", fixture.systemAdmin()).statusCode()).isEqualTo(204);
        assertThat(calendarContainsSchedule(request("GET", calendarUrl, fixture.admin()))).isFalse();
        assertThat(request("DELETE", teamUrl, fixture.systemAdmin()).statusCode()).isEqualTo(403);
        assertThat(request("DELETE", teamUrl, fixture.admin()).statusCode()).isEqualTo(204);
    }

    @AfterEach
    void cleanUp() {
        client.close();
        if (fixture == null) {
            return;
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> eventPool.getActiveCount() == 0
                && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
        inTransaction(() -> {
            for (String table : List.of("activity_feed", "user_calendar_layer_settings")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE scope_type = 'TEAM' AND scope_id = :id")
                        .setParameter("id", fixture.teamId()).executeUpdate();
            }
            for (String table : List.of("audit_logs", "schedules", "user_roles")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE team_id = :id")
                        .setParameter("id", fixture.teamId()).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :id")
                    .setParameter("id", fixture.teamId()).executeUpdate();
            em.createNativeQuery("DELETE FROM teams WHERE id = :id").setParameter("id", fixture.teamId()).executeUpdate();
            for (Long userId : List.of(fixture.admin(), fixture.systemAdmin(), fixture.outsider())) {
                for (String table : List.of("user_calendar_layer_settings", "user_roles", "memberships")) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE user_id = :id")
                            .setParameter("id", userId).executeUpdate();
                }
                em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", userId).executeUpdate();
            }
            return null;
        });
    }

    private HttpResponse<String> request(String method, String path, Long userId) throws Exception {
        String token = authTokenService.issueAccessToken(userId, roleClaimResolver.resolveRoles(userId),
                statusClaimResolver.isPendingParentalConsent(userId));
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private boolean calendarContainsSchedule(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).path("data").findValues("scheduleId").stream()
                .anyMatch(id -> id.longValue() == fixture.scheduleId());
    }

    private Long newUser() {
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        assertThat(id).isPositive();
        MembershipTestHelper.insertActiveUser(em, id);
        return id;
    }

    private <T> T inTransaction(Supplier<T> action) {
        return new TransactionTemplate(txManager).execute(status -> action.get());
    }

    private record Fixture(Long teamId, String slug, Long admin, Long systemAdmin, Long outsider, Long scheduleId) { }
}

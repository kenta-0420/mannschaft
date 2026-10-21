package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.dto.ErrorReportBulkUpdateRequest;
import com.mannschaft.app.errorreport.dto.ErrorReportUpdateRequest;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * AC1h・7・7h・9。実Securityフィルタ、Controller、業務Bean、MySQLを通すHTTP契約。
 * AC9とAC1hは既存green契約の回帰防止柵。Redisの実Lua/TTLは既存
 * ValkeyRateLimiterIntegrationTestに対応付け、本試験の外部stubで証明したとは扱わない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ErrorReportManagementR1ContractIT extends ErrorReportR1TestSupport {
    private static final String BASE = "/api/v1/system-admin/error-reports";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private UserRepository users;
    @Autowired private ValkeyRateLimiter limiter;
    private Long adminId;
    private Long grantId;
    private Long createdRoleId;
    private Clock previousClock;

    @BeforeEach
    void createAdminFixture() {
        previousClock = (Clock) ReflectionTestUtils.getField(limiter, "clock");
        ReflectionTestUtils.setField(limiter, "clock", Clock.fixed(Instant.parse("2026-10-21T03:00:20Z"), ZoneOffset.UTC));
        adminId = users.saveAndFlush(UserEntity.builder().email("r1-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("管理者").displayName("R1管理者").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        var role = roles.findByName("SYSTEM_ADMIN").orElse(null);
        if (role == null) {
            role = roles.saveAndFlush(RoleEntity.builder().name("SYSTEM_ADMIN").displayName("システム管理者")
                    .priority(0).isSystem(true).build());
            createdRoleId = role.getId();
        }
        grantId = userRoles.saveAndFlush(UserRoleEntity.builder().userId(adminId).roleId(role.getId()).build()).getId();
    }

    @AfterEach
    void removeAdminFixture() {
        awaitR1AsyncIdle();
        ReflectionTestUtils.setField(limiter, "clock", previousClock);
        if (adminId != null) jdbc.update("DELETE FROM notifications WHERE user_id = ?", adminId);
        if (grantId != null) userRoles.deleteById(grantId);
        if (createdRoleId != null) roles.deleteById(createdRoleId);
        if (adminId != null) users.deleteById(adminId);
    }

    @Test
    void AC1h_同一IP十件の並行POSTは全201で十一件目だけ429になる() throws Exception {
        var request = request(); String body = mapper.writeValueAsString(request);
        trackedHashes.add(feHash(request));
        String ip = "198.51.100." + (Math.abs(UUID.randomUUID().hashCode()) % 200 + 1);

        var outcomes = parallel(10, () -> {
            var result = mvc.perform(post("/api/v1/error-reports").servletPath("/api/v1/error-reports").header("X-Request-Id", requestId())
                    .with(req -> { req.setRemoteAddr(ip); return req; })
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
            return result.getResponse().getStatus();
        });

        assertThat(outcomes).allSatisfy(result -> {
            assertThat(result.failure()).isNull(); assertThat(result.value()).isEqualTo(201);
        });
        mvc.perform(post("/api/v1/error-reports").servletPath("/api/v1/error-reports")
                .with(req -> { req.setRemoteAddr(ip); return req; })
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
        assertThat(count(feHash(request))).isEqualTo(1);
        assertThat(occurrences(feHash(request))).isEqualTo(10);
        jdbc.queryForList("SELECT id FROM error_reports WHERE error_hash = ?", Long.class, feHash(request)).forEach(owned::add);
    }

    @Test
    void AC7_単票の再活性化衝突は409でbulk全体もロールバックする() throws Exception {
        String hash = sha256(UUID.randomUUID().toString());
        fixture(hash, ErrorReportStatus.NEW);
        var ignored = fixture(hash, ErrorReportStatus.IGNORED);
        var unrelated = fixture(sha256(UUID.randomUUID().toString()), ErrorReportStatus.IGNORED);

        mvc.perform(admin(patch(BASE + "/{id}", ignored.getId()))
                .content(mapper.writeValueAsString(new ErrorReportUpdateRequest("RESOLVED", null, null))))
                .andExpect(status().isConflict());
        mvc.perform(admin(patch(BASE + "/bulk"))
                .content(mapper.writeValueAsString(new ErrorReportBulkUpdateRequest(
                        List.of(unrelated.getId(), ignored.getId()), "RESOLVED"))))
                .andExpect(status().isConflict());

        assertThat(reload(ignored.getId()).getStatus()).isEqualTo(ErrorReportStatus.IGNORED);
        assertThat(reload(unrelated.getId()).getStatus()).isEqualTo(ErrorReportStatus.IGNORED);
    }

    @Test
    void AC7_同hash二行の同時単票再活性化は成功一件と409一件になる() throws Exception {
        concurrentReactivation(false);
    }

    @Test
    void AC7_同hash二行の交差bulk再活性化は成功一件と409一件になる() throws Exception {
        concurrentReactivation(true);
    }

    @Test
    void AC7h_全三回でロック失敗なら単票は503でDB更新なし() throws Exception { contention(false); }

    @Test
    void AC7h_全三回でロック失敗ならbulkは503でDB更新なし() throws Exception { contention(true); }

    @Test
    void AC9_SYSTEM_ADMIN正と通常ロール403と未認証401を維持する() throws Exception {
        mvc.perform(admin(get(BASE + "/stats"))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/stats").with(authentication(new UsernamePasswordAuthenticationToken(
                adminId.toString(), "unused", List.of(new SimpleGrantedAuthority("ROLE_MEMBER"))))))
                .andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/stats")).andExpect(status().isUnauthorized());
    }


    @Test
    void AC8_集約とTimeline工程担当の交差で件数と各変更が失われない() throws Exception {
        var request = request(); var saved = fe(request);
        var outcomes = parallel(12, () -> {
            fe(request);
            mvc.perform(admin(patch(BASE + "/{id}/workflow-stage", saved.getId()))
                    .content("{\"workflowStage\":\"FIX_IN_PROGRESS\"}" )).andExpect(status().isOk());
            mvc.perform(admin(patch(BASE + "/{id}/assignee", saved.getId()))
                    .content("{\"assigneeId\":" + adminId + "}" )).andExpect(status().isOk());
            return null;
        });

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        var report = reload(saved.getId());
        assertThat(report.getOccurrenceCount()).isEqualTo(13);
        assertThat(report.getStatus()).isEqualTo(ErrorReportStatus.INVESTIGATING);
        assertThat(report.getWorkflowStage()).isEqualTo(com.mannschaft.app.errorreport.ErrorReportWorkflowStage.FIX_IN_PROGRESS);
        assertThat(report.getAssigneeId()).isEqualTo(adminId);
    }

    @Test
    void AC8_集約とHealth復旧の交差でも件数が失われない() throws Exception {
        var request = request(); var saved = fe(request);
        var outcomes = parallel(12, () -> { fe(request); service.resolveHealthReport(saved.getId()); return null; });

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(reload(saved.getId()).getOccurrenceCount()).isEqualTo(13);
        assertThat(reload(saved.getId()).getStatus()).isEqualTo(ErrorReportStatus.RESOLVED);
    }


    @Test
    void AC7_複数hashを逆順に指定した交差bulkでも一方だけ成功し他方は全件rollbackする() throws Exception {
        String hashA = sha256(UUID.randomUUID().toString()); String hashB = sha256(UUID.randomUUID().toString());
        var a1 = fixture(hashA, ErrorReportStatus.IGNORED); var a2 = fixture(hashA, ErrorReportStatus.IGNORED);
        var b1 = fixture(hashB, ErrorReportStatus.IGNORED); var b2 = fixture(hashB, ErrorReportStatus.IGNORED);
        AtomicInteger target = new AtomicInteger();

        var outcomes = parallel(2, () -> {
            List<Long> ids = target.getAndIncrement() == 0 ? List.of(a1.getId(), b1.getId()) : List.of(b2.getId(), a2.getId());
            return mvc.perform(admin(patch(BASE + "/bulk")).content(mapper.writeValueAsString(
                    new ErrorReportBulkUpdateRequest(ids, "RESOLVED")))).andReturn().getResponse().getStatus();
        });

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(outcomes.stream().map(Outcome::value)).containsExactlyInAnyOrder(200, 409);
        assertThat(count(hashA)).isEqualTo(1); assertThat(count(hashB)).isEqualTo(1);
        assertThat(reload(a1.getId()).getStatus()).isEqualTo(reload(b1.getId()).getStatus());
        assertThat(reload(a2.getId()).getStatus()).isEqualTo(reload(b2.getId()).getStatus());
    }

    @Test
    void AC7h_通常read中のlock例外は単票TX全体を三回試行し503で更新しない() throws Exception {
        contentionAtRead(false);
    }

    @Test
    void AC7h_通常read中のlock例外はbulkTX全体を三回試行し503で更新しない() throws Exception {
        contentionAtRead(true);
    }

    /** 通常readへの失敗注入であり、hashロック取得の証明ではない。 */
    private void contentionAtRead(boolean bulk) throws Exception {
        String hash = sha256(UUID.randomUUID().toString());
        var report = fixture(hash, ErrorReportStatus.IGNORED);
        AtomicInteger attempts = new AtomicInteger();
        hook.onManagementRead = key -> {
            if (hash.equals(key)) { attempts.incrementAndGet(); throw new CannotAcquireLockException("R1 management read"); }
        };
        var request = bulk ? patch(BASE + "/bulk") : patch(BASE + "/{id}", report.getId());
        Object body = bulk ? new ErrorReportBulkUpdateRequest(List.of(report.getId()), "RESOLVED")
                : new ErrorReportUpdateRequest("RESOLVED", null, null);
        AtomicInteger httpStatus = new AtomicInteger(-1);
        Throwable requestFailure = org.assertj.core.api.Assertions.catchThrowable(() ->
                httpStatus.set(mvc.perform(admin(request).content(mapper.writeValueAsString(body)))
                        .andReturn().getResponse().getStatus()));

        assertThat(reload(report.getId()).getStatus()).isEqualTo(ErrorReportStatus.IGNORED);
        assertThat(reload(report.getId()).getResolvedAt()).isNull();
        assertThat(attempts.get()).as("通常readのlock例外後はTX全体を三回まで再試行する").isEqualTo(3);
        assertThat(requestFailure).isNull();
        assertThat(httpStatus.get()).isEqualTo(503);
    }

    private void contention(boolean bulk) throws Exception {
        String hash = sha256(UUID.randomUUID().toString());
        var report = fixture(hash, ErrorReportStatus.IGNORED);
        AtomicInteger attempts = new AtomicInteger();
        hook.onManagement = key -> {
            if (hash.equals(key)) { attempts.incrementAndGet(); throw new CannotAcquireLockException("R1 management lock"); }
        };
        var request = bulk ? patch(BASE + "/bulk") : patch(BASE + "/{id}", report.getId());
        Object body = bulk ? new ErrorReportBulkUpdateRequest(List.of(report.getId()), "RESOLVED")
                : new ErrorReportUpdateRequest("RESOLVED", null, null);

        mvc.perform(admin(request).content(mapper.writeValueAsString(body)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("ERROR_REPORT_014"));

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(reload(report.getId()).getStatus()).isEqualTo(ErrorReportStatus.IGNORED);
        assertThat(reload(report.getId()).getResolvedAt()).isNull();
    }

    private void concurrentReactivation(boolean bulk) throws Exception {
        String hash = sha256(UUID.randomUUID().toString());
        var first = fixture(hash, ErrorReportStatus.IGNORED);
        var second = fixture(hash, ErrorReportStatus.IGNORED);
        AtomicInteger target = new AtomicInteger();

        var outcomes = parallel(2, () -> {
            Long id = target.getAndIncrement() == 0 ? first.getId() : second.getId();
            var request = bulk ? patch(BASE + "/bulk") : patch(BASE + "/{id}", id);
            Object body = bulk ? new ErrorReportBulkUpdateRequest(List.of(id), "RESOLVED")
                    : new ErrorReportUpdateRequest("RESOLVED", null, null);
            return mvc.perform(admin(request).content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus();
        });

        assertThat(outcomes).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(outcomes.stream().map(Outcome::value)).containsExactlyInAnyOrder(200, 409);
        assertThat(count(hash)).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-Request-Id", requestId()).with(authentication(new UsernamePasswordAuthenticationToken(adminId.toString(), "unused",
                List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))))).contentType(MediaType.APPLICATION_JSON);
    }
}

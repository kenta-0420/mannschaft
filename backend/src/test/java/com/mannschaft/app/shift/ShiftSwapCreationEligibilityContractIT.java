package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261021-0647: ShiftSwapController#createSwapRequest の作成資格を実JWT・実Securityフィルタ・実MySQLで検査する。
 *
 * <p>公開済み本人担当だけを許す期待値と、状態・割当変更との競合は是正前にredとなる。
 * スコープ秘匿・未認証・SUPPORTER拒否は既存契約の回帰固定である。
 * fixtureは明示commitし、拒否後に独立TXで全列と件数を読み直す。HTTPを自動rollbackで包まない。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261021-0647 交代依頼作成資格の実フィルタ契約")
class ShiftSwapCreationEligibilityContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private TeamOrgMembershipRepository affiliations;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> scheduleIds = new ArrayList<>();
    private final List<Long> organizationIds = new ArrayList<>();
    private final Map<String, Long> actors = new LinkedHashMap<>();
    private Long teamId;

    @BeforeEach
    void fixtureをcommitする() {
        inTx(() -> {
            teamId = team("対象");
            Long otherTeam = team("別チーム");
            Long otherTenantTeam = team("別組織のチーム");
            Long ownOrg = organization("対象組織");
            Long otherOrg = organization("別組織");
            for (Long sameOrgTeam : List.of(teamId, otherTeam)) {
                affiliate(sameOrgTeam, ownOrg);
            }
            affiliate(otherTenantTeam, otherOrg);
            for (String role : List.of("MEMBER", "ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN", "SUPPORTER")) {
                Long actor = user(role);
                actors.put(role, actor);
                if (!role.equals("SYSTEM_ADMIN")) {
                    MembershipTestHelper.insertMembership(em, actor, ScopeType.TEAM, teamId,
                            role.equals("SUPPORTER") ? RoleKind.SUPPORTER : RoleKind.MEMBER);
                }
                if (List.of("ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN").contains(role)) {
                    MembershipTestHelper.insertUserRole(em, actor, role,
                            role.equals("SYSTEM_ADMIN") ? null : teamId, null);
                }
            }
            Long outsider = user("OTHER_TEAM_ADMIN");
            actors.put("OTHER_TEAM_ADMIN", outsider);
            MembershipTestHelper.insertMembership(em, outsider, ScopeType.TEAM, otherTeam, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, outsider, "ADMIN", otherTeam, null);
            Long tenantOutsider = user("OTHER_TENANT_ADMIN");
            actors.put("OTHER_TENANT_ADMIN", tenantOutsider);
            MembershipTestHelper.insertMembership(em, tenantOutsider, ScopeType.TEAM, otherTenantTeam, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, tenantOutsider, "ADMIN", otherTenantTeam, null);
            return null;
        });
    }

    @AfterEach
    void 所有fixtureだけを削除する() {
        inTx(() -> {
            if (!scheduleIds.isEmpty()) {
                em.createNativeQuery("DELETE FROM shift_swap_requests WHERE slot_id IN "
                                + "(SELECT id FROM shift_slots WHERE schedule_id IN (:ids))")
                        .setParameter("ids", scheduleIds).executeUpdate();
                em.createNativeQuery("DELETE FROM shift_slots WHERE schedule_id IN (:ids)")
                        .setParameter("ids", scheduleIds).executeUpdate();
                em.createNativeQuery("DELETE FROM shift_schedules WHERE id IN (:ids)")
                        .setParameter("ids", scheduleIds).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)")
                    .setParameter("ids", userIds).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)")
                    .setParameter("ids", userIds).executeUpdate();
            users.deleteAllById(userIds);
            em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id IN (:ids)")
                    .setParameter("ids", teamIds).executeUpdate();
            teams.deleteAllById(teamIds);
            organizations.deleteAllById(organizationIds);
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 全許可ロールの本人担当は201で本人を申請者として保存する(boolean openCall) throws Exception {
        for (String role : List.of("MEMBER", "ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN")) {
            Long actor = actors.get(role);
            Long other = actor.equals(actors.get("MEMBER")) ? actors.get("ADMIN") : actors.get("MEMBER");
            String assigned = openCall ? "[" + other + "," + actor + "]" : "[" + actor + "]";
            Long slot = slot(ShiftScheduleStatus.PUBLISHED, assigned);
            create(actor, slot, openCall).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.requesterId").value(actor))
                    .andExpect(jsonPath("$.data.slotId").value(slot))
                    .andExpect(jsonPath("$.data.recipientMode").value(openCall ? "OPEN_CALL" : "SPECIFIC"));
            assertThat(inTx(() -> ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM shift_swap_requests WHERE slot_id = :slot "
                                    + "AND requester_id = :actor AND is_open_call = :mode")
                    .setParameter("slot", slot).setParameter("actor", actor).setParameter("mode", openCall)
                    .getSingleResult()).longValue()))
                    .isEqualTo(1L);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 管理者を含む本人以外の担当は403で全列件数不変(boolean openCall) throws Exception {
        Long slot = slot(ShiftScheduleStatus.PUBLISHED, "[" + actors.get("MEMBER") + "]");
        for (String role : List.of("ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN")) {
            assertDeniedWithoutMutation(actors.get(role), slot, openCall, 403, "SHIFT_019");
        }
        Long someoneElse = slot(ShiftScheduleStatus.PUBLISHED, "[" + actors.get("ADMIN") + "]");
        assertDeniedWithoutMutation(actors.get("MEMBER"), someoneElse, openCall, 403, "SHIFT_019");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"[]", "null"})
    void 未割当は403で全列件数不変(String assigned) throws Exception {
        assertDeniedWithoutMutation(actors.get("MEMBER"), slot(ShiftScheduleStatus.PUBLISHED, assigned),
                403, "SHIFT_019");
    }

    @ParameterizedTest
    @EnumSource(value = ShiftScheduleStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void 非PUBLISHEDは本人担当でも409で全列件数不変(ShiftScheduleStatus scheduleStatus) throws Exception {
        for (String role : List.of("MEMBER", "ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN")) {
            Long actor = actors.get(role);
            assertDeniedWithoutMutation(actor, slot(scheduleStatus, "[" + actor + "]"), 409, "SHIFT_012");
        }
    }

    @Test
    void 別チームの管理者と不存在の404本文は公開状態や担当に依存せず完全一致() throws Exception {
        for (String outsiderRole : List.of("OTHER_TEAM_ADMIN", "OTHER_TENANT_ADMIN")) {
            Long outsider = actors.get(outsiderRole);
            String absent = create(outsider, Long.MAX_VALUE).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_002"))
                    .andReturn().getResponse().getContentAsString();
            for (ShiftScheduleStatus scheduleStatus : ShiftScheduleStatus.values()) {
                Long slot = slot(scheduleStatus, "[" + outsider + "]");
                Map<String, String> before = snapshot();
                String denied = create(outsider, slot).andExpect(status().isNotFound())
                        .andReturn().getResponse().getContentAsString();
                assertThat(denied).isEqualTo(absent);
                assertThat(snapshot()).isEqualTo(before);
            }
        }
    }

    @Test
    void 未認証401とSUPPORTER403は本人担当でも維持する() throws Exception {
        Long supporter = actors.get("SUPPORTER");
        Long slot = slot(ShiftScheduleStatus.PUBLISHED, "[" + supporter + "]");
        Map<String, String> before = snapshot();
        create(null, slot).andExpect(status().isUnauthorized());
        assertThat(snapshot()).isEqualTo(before);
        assertDeniedWithoutMutation(supporter, slot, 403, "COMMON_002");
    }

    @Test
    void 担当変更が親ロックを先取したらcommit後の担当で拒否する() throws Exception {
        assertConcurrentChangeRejected(ConcurrentChange.ASSIGNMENT, 403, "SHIFT_019");
    }

    @Test
    void 公開取消が親行を先取したらcommit後の状態で拒否する() throws Exception {
        assertConcurrentChangeRejected(ConcurrentChange.UNPUBLISH, 409, "SHIFT_012");
    }

    @Test
    void 枠削除が親行を先取したらcommit後は404で申請を保存しない() throws Exception {
        assertConcurrentChangeRejected(ConcurrentChange.DELETE_SLOT, 404, "SHIFT_002");
    }

    /** 親行を先に確保する既存更新経路との競合の種類。 */
    private enum ConcurrentChange { ASSIGNMENT, UNPUBLISH, DELETE_SLOT }

    private void assertConcurrentChangeRejected(ConcurrentChange change, int expectedStatus, String code) throws Exception {
        Long actor = actors.get("MEMBER");
        Long slotId = slot(ShiftScheduleStatus.PUBLISHED, "[" + actor + "]");
        Long scheduleId = inTx(() -> slots.findById(slotId).orElseThrow().getScheduleId());
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> inTx(() -> {
                ShiftScheduleEntity schedule = schedules.findByIdForUpdate(scheduleId).orElseThrow();
                if (change == ConcurrentChange.UNPUBLISH) {
                    schedule.startAdjusting();
                } else if (change == ConcurrentChange.DELETE_SLOT) {
                    slots.softDeleteById(slotId);
                } else {
                    slots.findById(slotId).orElseThrow().updateAssignedUserIds("[]");
                }
                em.flush();
                changed.countDown();
                await(release);
                return null;
            }));
            try {
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                SqlIntentCounter.reset();
                AtomicReference<Thread> requestThread = new AtomicReference<>();
                var request = executor.submit(() -> {
                    requestThread.set(Thread.currentThread());
                    return create(actor, slotId).andReturn();
                });
                // 既存ShiftScheduleSlotFacadeRaceAndQueryITと同じく、実要求の親ロック内到達を観測する。
                Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(20))
                        .until(() -> request.isDone() || (isInsideScheduleLock(requestThread.get()) && hasScheduleLockSql()));
                assertThat(request.isDone()).as("親ロック保持中に作成要求が完了してはならない").isFalse();
                assertThat(isInsideScheduleLock(requestThread.get())).isTrue();
                List<String> sqls = SqlIntentCounter.capturedSqls();
                assertThat(sqls.stream().filter(sql -> sql.contains("from shift_slots ")).count())
                        .as("Facade解決と作成TXの初回枠読取が完了済み").isGreaterThanOrEqualTo(2L);
                assertThat(hasScheduleLockSql()).isTrue();
                release.countDown();
                writer.get(10, TimeUnit.SECONDS);
                var response = request.get(10, TimeUnit.SECONDS).getResponse();
                assertThat(response.getStatus()).isEqualTo(expectedStatus);
                assertThat(mapper.readTree(response.getContentAsString()).at("/error/code").asText()).isEqualTo(code);
                assertThat(inTx(() -> ((Number) em.createNativeQuery(
                                "SELECT COUNT(*) FROM shift_swap_requests WHERE slot_id = :id")
                        .setParameter("id", slotId).getSingleResult()).longValue())).isZero();
            } finally {
                release.countDown();
                writer.get(10, TimeUnit.SECONDS);
            }
        }
    }

    private static boolean isInsideScheduleLock(Thread thread) {
        if (thread == null) {
            return false;
        }
        StackTraceElement[] stack = thread.getStackTrace();
        return Arrays.stream(stack).anyMatch(frame -> frame.getMethodName().equals("findByIdForUpdate"))
                && Arrays.stream(stack).anyMatch(frame -> frame.getClassName().equals(
                        "com.mannschaft.app.shift.service.ShiftSwapService")
                        && frame.getMethodName().equals("createSwapRequest"));
    }

    private static boolean hasScheduleLockSql() {
        return SqlIntentCounter.capturedSqls().stream().anyMatch(sql -> sql.contains("from shift_schedules ")
                && sql.toLowerCase(java.util.Locale.ROOT).contains("for update"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("競合試験の解放待ちが期限を超えました");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void assertDeniedWithoutMutation(Long actor, Long slotId, int expectedStatus, String code) throws Exception {
        assertDeniedWithoutMutation(actor, slotId, false, expectedStatus, code);
    }

    private void assertDeniedWithoutMutation(Long actor, Long slotId, boolean openCall,
                                            int expectedStatus, String code) throws Exception {
        Map<String, String> before = snapshot();
        create(actor, slotId, openCall).andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.error.code").value(code));
        assertThat(snapshot()).isEqualTo(before);
    }

    private Map<String, String> snapshot() {
        return inTx(() -> {
            Map<String, String> result = new LinkedHashMap<>();
            for (String table : List.of("shift_schedules", "shift_slots", "shift_swap_requests", "shift_assignments")) {
                result.put(table, Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " ORDER BY id")
                        .getResultList().toArray()));
            }
            return result;
        });
    }

    private ResultActions create(Long actor, Long slotId) throws Exception {
        return create(actor, slotId, false);
    }

    private ResultActions create(Long actor, Long slotId, boolean openCall) throws Exception {
        var request = post("/api/v1/shifts/swap-requests").header("Accept-Language", "ja")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("slotId", slotId, "reason", "作成資格試験", "openCall", openCall)));
        if (actor != null) {
            List<String> roles = actor.equals(actors.get("SYSTEM_ADMIN"))
                    ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER");
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, roles));
        }
        return mockMvc.perform(request);
    }

    private Long slot(ShiftScheduleStatus scheduleStatus, String assigned) {
        return inTx(() -> {
            ShiftScheduleEntity schedule = schedules.save(ShiftScheduleEntity.builder().teamId(teamId)
                    .title("作成資格試験").periodType(ShiftPeriodType.WEEKLY).startDate(LocalDate.now())
                    .endDate(LocalDate.now().plusDays(7)).status(scheduleStatus)
                    // 過去に公開済みのARCHIVEDも作成不可。閲覧可否と作成資格を混同しない。
                    .publishedAt(scheduleStatus == ShiftScheduleStatus.ARCHIVED ? LocalDateTime.now() : null)
                    .build());
            scheduleIds.add(schedule.getId());
            return slots.save(ShiftSlotEntity.builder().scheduleId(schedule.getId()).slotDate(LocalDate.now().plusDays(1))
                    .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).requiredCount(2)
                    .assignedUserIds(assigned).build()).getId();
        });
    }

    private Long user(String label) {
        Long id = users.save(UserEntity.builder().email("swap-eligibility-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long team(String label) {
        Long id = teams.save(TeamEntity.builder().slug("swap-" + UUID.randomUUID().toString().substring(0, 20))
                .name(label).visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(true).build()).getId();
        teamIds.add(id);
        return id;
    }

    private Long organization(String label) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug("swap-" + UUID.randomUUID().toString().substring(0, 20)).name(label)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        organizationIds.add(id);
        return id;
    }

    private void affiliate(Long team, Long organization) {
        affiliations.save(TeamOrgMembershipEntity.builder().teamId(team).organizationId(organization)
                .status(TeamOrgMembershipEntity.Status.ACTIVE).invitedAt(LocalDateTime.now()).build());
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }
}

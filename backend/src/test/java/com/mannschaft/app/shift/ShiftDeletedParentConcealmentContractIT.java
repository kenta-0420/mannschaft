package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.shift.dto.ConfirmAutoAssignRequest;
import com.mannschaft.app.shift.dto.ResolveSwapRequestRequest;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftAssignmentRunRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import com.mannschaft.app.shift.service.ShiftAutoAssignService;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import com.mannschaft.app.shift.service.ShiftSlotService;
import com.mannschaft.app.shift.service.ShiftSwapService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260903-0657: 親削除後の子ID存在秘匿を実JWT・実Security・実MySQLで固定する。
 * 親削除の不在コード統一は先行red、正常親の許可・拒否と本人取消例外は既存契約の回帰固定である。
 * fixtureはcommitし、HTTP拒否後の全列を独立TXで比較する。内部Bean・DB・認可のモックは使わない。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("親削除後のswap/run存在秘匿契約")
class ShiftDeletedParentConcealmentContractIT extends AbstractMySqlIntegrationTest {
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
    @Autowired private ShiftSwapRequestRepository swaps;
    @Autowired private ShiftAssignmentRunRepository runs;
    @Autowired private ShiftScheduleService scheduleService;
    @Autowired private ShiftSlotService slotService;
    @Autowired private ShiftSwapService swapService;
    @Autowired private ShiftAutoAssignService autoAssignService;
    @Autowired private FeatureFlagRepository flags;
    @Autowired private CacheManager caches;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> scheduleIds = new ArrayList<>();
    private final List<Long> organizationIds = new ArrayList<>();
    private final Map<String, Long> actors = new LinkedHashMap<>();
    private Long teamId;
    private Boolean previousFlagEnabled;
    private Long previousFlagUpdatedBy;

    @BeforeEach
    void fixtureをcommitする() {
        flags.findByFlagKey("FEATURE_SHIFT_AUTO_ASSIGN_ENABLED").ifPresent(flag -> {
            previousFlagEnabled = flag.getIsEnabled();
            previousFlagUpdatedBy = flag.getUpdatedBy();
        });
        FeatureFlagTestSupport.enable(flags, caches, "FEATURE_SHIFT_AUTO_ASSIGN_ENABLED");
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
            Long ghostAdmin = user("GHOST_ADMIN");
            actors.put("GHOST_ADMIN", ghostAdmin);
            MembershipTestHelper.insertUserRole(em, ghostAdmin, "ADMIN", teamId, null);
            return null;
        });
    }

    @AfterEach
    void 所有fixtureだけを削除する() {
        inTx(() -> {
            flags.findByFlagKey("FEATURE_SHIFT_AUTO_ASSIGN_ENABLED").ifPresent(flag -> {
                if (previousFlagEnabled == null) {
                    flags.delete(flag);
                } else {
                    flag.updateFlag(previousFlagEnabled, previousFlagUpdatedBy);
                }
            });
            return null;
        });
        FeatureFlagTestSupport.clearFlagCaches(caches);
        inTx(() -> {
            if (!scheduleIds.isEmpty()) {
                em.createNativeQuery("DELETE FROM shift_swap_requests WHERE slot_id IN "
                                + "(SELECT id FROM shift_slots WHERE schedule_id IN (:ids))")
                        .setParameter("ids", scheduleIds).executeUpdate();
                em.createNativeQuery("DELETE FROM shift_assignment_runs WHERE schedule_id IN (:ids)")
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
    @ValueSource(strings = {"SCHEDULE", "SLOT", "SCHEDULE_ONLY"})
    void 削除親の残存swapは不存在と同じ404で全列不変(String deletion) throws Exception {
        Long slotId = slot(ShiftScheduleStatus.PUBLISHED, "[" + actors.get("MEMBER") + "]");
        Long swapId = swap(slotId);
        deleteParent(slotId, deletion);
        for (String role : List.of("OTHER_TEAM_ADMIN", "OTHER_TENANT_ADMIN", "ADMIN", "SYSTEM_ADMIN")) {
            for (String operation : List.of("accept", "APPROVE", "REJECT", "cancel")) {
                Map<String, String> before = snapshot();
                assertSameNotFound(callSwap(role, operation, swapId),
                        callSwap(role, operation, Long.MAX_VALUE), "SHIFT_005");
                assertThat(snapshot()).isEqualTo(before);
            }
        }
    }

    @Test
    void 親削除後も本人のPENDING取消例外だけは維持する() throws Exception {
        Long slotId = slot(ShiftScheduleStatus.PUBLISHED, "[]");
        Long swapId = swap(slotId);
        deleteParent(slotId, "SCHEDULE");
        callSwap("MEMBER", "cancel", swapId).andExpect(status().isNoContent());
        assertThat(inTx(() -> swaps.findById(swapId).orElseThrow().getStatus()))
                .isEqualTo(SwapRequestStatus.CANCELLED);
    }

    @Test
    void runの親削除と不存在とパス不一致は確定破棄詳細目視で同じ404かつ全列不変() throws Exception {
        Long slotId = slot(ShiftScheduleStatus.PUBLISHED, "[]");
        Long scheduleId = inTx(() -> slots.findById(slotId).orElseThrow().getScheduleId());
        Long runId = run(scheduleId);
        deleteParent(slotId, "SCHEDULE");
        for (String role : List.of("OTHER_TEAM_ADMIN", "ADMIN", "SYSTEM_ADMIN")) {
            for (String operation : List.of("confirm", "revoke", "detail", "visual")) {
                Map<String, String> before = snapshot();
                assertSameNotFound(callRun(role, operation, scheduleId, runId),
                        callRun(role, operation, scheduleId, Long.MAX_VALUE), "SHIFT_024");
                if (List.of("confirm", "revoke").contains(operation)) {
                    assertSameNotFound(callRun(role, operation, Long.MAX_VALUE, runId),
                            callRun(role, operation, scheduleId, Long.MAX_VALUE), "SHIFT_024");
                }
                assertThat(snapshot()).isEqualTo(before);
            }
        }
    }

    @Test
    void 認可前scope解決後に親を削除しても業務TXは子の404で全列不変() {
        Long slotId = slot(ShiftScheduleStatus.PUBLISHED, "[]");
        Long swapId = swap(slotId);
        Long scheduleId = inTx(() -> slots.findById(slotId).orElseThrow().getScheduleId());
        Long runId = run(scheduleId);
        assertThat(swapService.resolveSwapScope(swapId).teamId()).isEqualTo(teamId);
        assertThat(autoAssignService.resolveRunTeamId(runId, scheduleId)).isEqualTo(teamId);
        deleteParent(slotId, "SCHEDULE");
        Map<String, String> before = snapshot();
        assertCode(() -> swapService.acceptSwapRequest(swapId, actors.get("ADMIN")), ShiftErrorCode.SWAP_REQUEST_NOT_FOUND);
        assertCode(() -> swapService.resolveSwapRequest(swapId,
                new ResolveSwapRequestRequest("APPROVE", null), actors.get("ADMIN"), null), ShiftErrorCode.SWAP_REQUEST_NOT_FOUND);
        assertCode(() -> swapService.resolveSwapRequest(swapId,
                new ResolveSwapRequestRequest("REJECT", null), actors.get("ADMIN"), null), ShiftErrorCode.SWAP_REQUEST_NOT_FOUND);
        assertCode(() -> swapService.cancelSwapRequest(swapId, actors.get("ADMIN")), ShiftErrorCode.SWAP_REQUEST_NOT_FOUND);
        assertCode(() -> autoAssignService.confirmAutoAssign(scheduleId,
                new ConfirmAutoAssignRequest(runId, List.of(Long.MAX_VALUE), 0)), ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        assertCode(() -> autoAssignService.revokeAutoAssign(scheduleId, runId), ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 未認証は削除親と不在の両方で401かつ全列不変() throws Exception {
        Long slotId = slot(ShiftScheduleStatus.PUBLISHED, "[]");
        Long swapId = swap(slotId);
        Long scheduleId = inTx(() -> slots.findById(slotId).orElseThrow().getScheduleId());
        Long runId = run(scheduleId);
        deleteParent(slotId, "SCHEDULE");
        Map<String, String> before = snapshot();
        for (Long id : List.of(swapId, Long.MAX_VALUE)) {
            for (String operation : List.of("accept", "APPROVE", "REJECT", "cancel")) {
                callSwap(null, operation, id).andExpect(status().isUnauthorized());
            }
        }
        for (String operation : List.of("confirm", "revoke", "detail", "visual")) {
            callRun(null, operation, scheduleId, runId).andExpect(status().isUnauthorized());
            callRun(null, operation, scheduleId, Long.MAX_VALUE).andExpect(status().isUnauthorized());
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 生存親の許可主体と所属不足403と一覧403を維持する() throws Exception {
        Long slotId = slot(ShiftScheduleStatus.PUBLISHED, "[]");
        Long swapId = swap(slotId);
        for (String role : List.of("ADMIN", "DEPUTY_ADMIN", "GHOST_ADMIN", "SYSTEM_ADMIN")) {
            Long cancellable = swap(slotId);
            callSwap(role, "cancel", cancellable).andExpect(status().isNoContent());
            Long resolvable = swap(slotId);
            inTx(() -> { swaps.findById(resolvable).orElseThrow().accept(actors.get("ADMIN")); return null; });
            callSwap(role, "REJECT", resolvable).andExpect(status().isOk());
        }
        for (String role : List.of("ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN")) {
            Long acceptable = swap(slotId);
            SqlIntentCounter.reset();
            callSwap(role, "accept", acceptable).andExpect(status().isOk());
            assertParentSelectCount("shift_slots", 2);
            assertParentSelectCount("shift_schedules", 2);
        }
        for (String role : List.of("MEMBER", "SUPPORTER")) {
            callSwap(role, "REJECT", swapId).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
        callSwap("GHOST_ADMIN", "accept", swapId).andExpect(status().isForbidden());
        for (Long requestedTeam : List.of(teamId, Long.MAX_VALUE)) {
            perform("OTHER_TEAM_ADMIN", get("/api/v1/shifts/swap-requests").param("teamId", requestedTeam.toString()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    @Test
    void runの生存親と空提案の許可主体と同scopeの既存拒否コードを維持する() throws Exception {
        Long slotId = slot(ShiftScheduleStatus.PUBLISHED, "[]");
        Long scheduleId = inTx(() -> slots.findById(slotId).orElseThrow().getScheduleId());
        for (String role : List.of("ADMIN", "DEPUTY_ADMIN", "GHOST_ADMIN", "SYSTEM_ADMIN")) {
            Long runId = run(scheduleId);
            callRun(role, "detail", scheduleId, runId).andExpect(status().isOk());
            SqlIntentCounter.reset();
            callRun(role, "confirm", scheduleId, runId).andExpect(status().isOk());
            assertParentSelectCount("shift_schedules", 2);
            callRun(role, "revoke", scheduleId, runId).andExpect(status().isNoContent());
        }
        Long runId = run(scheduleId);
        for (String operation : List.of("confirm", "revoke", "detail", "visual")) {
            Map<String, String> before = snapshot();
            boolean concealedDetail = List.of("detail", "visual").contains(operation);
            callRun("MEMBER", operation, scheduleId, runId).andExpect(status().is(concealedDetail ? 404 : 403))
                    .andExpect(jsonPath("$.error.code").value(concealedDetail ? "SHIFT_024" : "COMMON_002"));
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    private static void assertParentSelectCount(String table, long count) {
        assertThat(SqlIntentCounter.capturedSqls().stream()
                .filter(sql -> sql.contains("from " + table + " ")).count()).isEqualTo(count);
    }

    private static void assertCode(Runnable operation, ShiftErrorCode code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }

    private void assertSameNotFound(ResultActions deleted, ResultActions absent, String code) throws Exception {
        var actual = deleted.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(code)).andReturn().getResponse();
        var expected = absent.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(code)).andReturn().getResponse();
        assertThat(mapper.readTree(actual.getContentAsString()).get("error"))
                .isEqualTo(mapper.readTree(expected.getContentAsString()).get("error"));
    }

    private ResultActions callSwap(String role, String operation, Long swapId) throws Exception {
        var request = operation.equals("cancel") ? delete("/api/v1/shifts/swap-requests/{id}", swapId)
                : post("/api/v1/shifts/swap-requests/{id}/{operation}", swapId,
                        operation.equals("accept") ? "accept" : "resolve");
        if (List.of("APPROVE", "REJECT").contains(operation)) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("action", operation)));
        }
        return perform(role, request);
    }

    private ResultActions callRun(String role, String operation, Long scheduleId, Long runId) throws Exception {
        var request = switch (operation) {
            case "confirm" -> post("/api/v1/shifts/schedules/{id}/auto-assign/confirm", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                            Map.of("runId", runId, "assignmentIds", List.of(Long.MAX_VALUE), "scheduleVersion", 0)));
            case "revoke" -> delete("/api/v1/shifts/schedules/{id}/auto-assign", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON).content(runId.toString());
            case "detail" -> get("/api/v1/shifts/assignment-runs/{id}", runId);
            case "visual" -> post("/api/v1/shifts/assignment-runs/{id}/confirm-visual-review", runId)
                    .contentType(MediaType.APPLICATION_JSON).content("{}");
            default -> throw new IllegalArgumentException(operation);
        };
        return perform(role, request);
    }

    private ResultActions perform(String role, MockHttpServletRequestBuilder request) throws Exception {
        request.header("Accept-Language", "ja");
        if (role != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actors.get(role),
                    role.equals("SYSTEM_ADMIN") ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER")));
        }
        return mockMvc.perform(request);
    }

    private void deleteParent(Long slotId, String deletion) {
        Long scheduleId = inTx(() -> slots.findById(slotId).orElseThrow().getScheduleId());
        if (deletion.equals("SCHEDULE")) {
            scheduleService.deleteSchedule(scheduleId, actors.get("ADMIN"));
        } else if (deletion.equals("SLOT")) {
            slotService.deleteSlot(slotId);
        } else {
            inTx(() -> { schedules.findById(scheduleId).orElseThrow().softDelete(LocalDateTime.now()); return null; });
        }
    }

    private Long swap(Long slotId) {
        return inTx(() -> swaps.save(ShiftSwapRequestEntity.builder().slotId(slotId)
                .requesterId(actors.get("MEMBER")).reason("親削除秘匿試験")
                .isOpenCall(true).recipientMode("OPEN_CALL").build()).getId());
    }

    private Long run(Long scheduleId) {
        return inTx(() -> runs.save(ShiftAssignmentRunEntity.builder().scheduleId(scheduleId)
                .triggeredBy(actors.get("ADMIN")).status(ShiftAssignmentRunStatus.CONFIRMED).build()).getId());
    }

    private Map<String, String> snapshot() {
        return inTx(() -> {
            Map<String, String> result = new LinkedHashMap<>();
            for (String table : List.of("shift_schedules", "shift_slots", "shift_swap_requests",
                    "shift_assignment_runs", "shift_assignments")) {
                result.put(table, Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " ORDER BY id")
                        .getResultList().toArray()));
            }
            return result;
        });
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

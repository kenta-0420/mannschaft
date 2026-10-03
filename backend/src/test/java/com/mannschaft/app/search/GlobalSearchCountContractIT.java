package com.mannschaft.app.search;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.facility.FacilityType;
import com.mannschaft.app.facility.entity.FacilityBookingEntity;
import com.mannschaft.app.facility.entity.SharedFacilityEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.TicketSource;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.SafetyCheckStatus;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.search.dto.SearchResultResponse;
import com.mannschaft.app.search.service.GlobalSearchService;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260903-0700: 実 MySQL・実 Repository・実 F00 で総件数と返却上限を検証する。
 *
 * <p>11件の試練は残7種で red、既に Page の teams / organizations は回帰柵として green が正常。
 * API は有効なSecurityフィルタと実 AuthTokenService の発行・検証を通す。
 * 通常の試験は rollback する。スナップショット試験一件だけは自身fixtureをcommitし、
 * 捕捉した自身IDの範囲でfinally cleanupする。一意な検索語で既存データを母集団に含めない。</p>
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP0700 横断検索の総件数契約")
class GlobalSearchCountContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private GlobalSearchService searchService;
    @Autowired private EntityManager em;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ContentVisibilityChecker visibilityChecker;

    private String keyword;
    private Long viewerId;
    private Long teamId;
    private Long orgId;

    @BeforeEach
    void setUp() {
        keyword = "cmp0700" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        viewerId = saveUser("閲覧者", false).getId();
        teamId = saveTeam("所属先").getId();
        orgId = saveOrganization("所属先").getId();
        join(viewerId, ScopeType.TEAM, teamId);
        join(viewerId, ScopeType.ORGANIZATION, orgId);
    }

    @ParameterizedTest(name = "AC1 {0}: 可視11件は総数11・結果10")
    @ValueSource(strings = {"schedules", "events", "reservations", "shifts", "safetyChecks", "queues", "teams", "organizations", "users"})
    void 上限を超えても各種別の総件数を返す(String type) {
        populate(type, 11);
        assertCount(type, 11, 10);
    }

    @ParameterizedTest(name = "AC2 {0}: {1}件境界")
    @CsvSource({"schedules,0", "events,0", "reservations,0", "shifts,0", "safetyChecks,0", "queues,0", "teams,0", "organizations,0", "users,0",
            "schedules,10", "events,10", "reservations,10", "shifts,10", "safetyChecks,10", "queues,10", "teams,10", "organizations,10", "users,10"})
    void 空と上限ちょうどは総数と結果が一致する(String type, int count) {
        populate(type, count);
        assertCount(type, count, count);
    }

    @ParameterizedTest(name = "AC3 {0}: 検索語不一致は総数に含めない")
    @ValueSource(strings = {"schedules", "events", "reservations", "shifts", "safetyChecks", "queues", "teams", "organizations", "users"})
    void 検索条件を外れた行は件数に含めない(String type) {
        populate(type, 11);
        flushClear();
        SearchResultResponse response = searchService.search(keyword + "no-match", viewerId);
        assertThat(response.getCounts().get(type)).isZero();
        assertThat(response.getResults().get(type)).isEmpty();
    }

    @ParameterizedTest(name = "AC3 {0}: 非所属者の検索母集団")
    @ValueSource(strings = {"schedules", "events", "reservations", "shifts", "safetyChecks", "queues", "teams", "organizations", "users"})
    void 非所属者へ非公開の件数を漏らさず公開団体の検索を維持する(String type) {
        populate(type, 11);
        Long outsiderId = saveUser("非所属者", false).getId();
        flushClear();
        SearchResultResponse response = searchService.search(keyword, outsiderId);
        // 公開団体検索だけは所属に依存しない。それ以外のfixtureは本人作成/本人予約ではない。
        boolean publicScope = type.equals("teams") || type.equals("organizations");
        assertThat(response.getCounts().get(type)).isEqualTo(publicScope ? 11L : 0L);
        assertThat(response.getResults().get(type)).hasSize(publicScope ? 10 : 0);
    }

    @ParameterizedTest(name = "AC5 候補{0}件: keyset境界で欠落しない")
    @ValueSource(ints = {255, 256, 257})
    void 予定のバッチ境界を超えて全可視数を数える(int count) {
        List<Long> visibleIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            visibleIds.add(saveSchedule(teamId, null, MinViewRole.MEMBER_PLUS).getId());
        }
        flushClear();
        SearchResultResponse response = searchService.search(keyword, viewerId);
        assertThat(response.getCounts().get("schedules")).isEqualTo((long) count);
        assertThat(ids(response, "schedules")).containsExactlyElementsOf(visibleIds.subList(0, 10));
    }

    @Test
    @DisplayName("AC4/5 不可視の先頭256候補を越えて可視11件を数える")
    void 不可視候補を総数に足さず後続の可視予定を補充する() {
        for (int i = 0; i < 256; i++) {
            saveSchedule(teamId, null, MinViewRole.ADMIN_ONLY);
        }
        List<Long> visibleIds = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            visibleIds.add(saveSchedule(teamId, null, MinViewRole.MEMBER_PLUS).getId());
        }
        flushClear();
        SearchResultResponse response = searchService.search(keyword, viewerId);
        assertThat(response.getCounts().get("schedules")).isEqualTo(11L);
        assertThat(ids(response, "schedules")).containsExactlyElementsOf(visibleIds.subList(0, 10));
    }

    @Test
    @DisplayName("AC4 予定の母集団外・CUSTOM_TEMPLATE・削除済みを数えない")
    void 予定の既存母集団とF00の拒否条件を維持する() {
        populate("schedules", 11);
        Long otherTeam = saveTeam("他団体").getId();
        saveSchedule(otherTeam, null, MinViewRole.ANYONE);
        Long otherUser = saveUser("他人", false).getId();
        saveSchedule(null, otherUser, MinViewRole.ANYONE);
        ScheduleEntity custom = saveSchedule(teamId, null, MinViewRole.ANYONE);
        ScheduleEntity deleted = saveSchedule(teamId, null, MinViewRole.MEMBER_PLUS);
        em.flush();
        // 実DB上の既存状態を作る。CUSTOM_TEMPLATEを認可SQLへ展開する変更を許さない。
        jdbc.update("UPDATE schedules SET visibility = 'CUSTOM_TEMPLATE' WHERE id = ?", custom.getId());
        jdbc.update("UPDATE schedules SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleted.getId());
        assertCount("schedules", 11, 10);
    }

    @Test
    @DisplayName("AC6 実MySQLの読取TxはREPEATABLE_READである")
    void 実接続の隔離レベルを確認する() {
        Integer isolation = jdbc.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
        assertThat(isolation).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
    }

    @Test
    @DisplayName("AC4 SYSTEM_ADMINでも検索母集団を未所属団体へ広げない")
    void システム管理者のF00許可と検索母集団を区別する() {
        populate("schedules", 11);
        Long sysId = saveUser("システム管理者", false).getId();
        MembershipTestHelper.insertUserRole(em, sysId, "SYSTEM_ADMIN", null, null);
        flushClear();
        assertThat(searchService.search(keyword, sysId).getCounts().get("schedules")).isZero();
        join(sysId, ScopeType.TEAM, teamId);
        Long otherTeam = saveTeam("他団体").getId();
        for (int i = 0; i < 11; i++) {
            saveSchedule(otherTeam, null, MinViewRole.ADMIN_ONLY);
        }
        flushClear();
        SearchResultResponse response = searchService.search(keyword, sysId);
        assertThat(response.getCounts().get("schedules")).isEqualTo(11L);
        assertThat(response.getResults().get("schedules")).hasSize(10);
    }

    @Test
    @DisplayName("AC4 個人予定は本人の11件だけを検索母集団に含める")
    void 個人予定の本人境界を保持する() {
        Long otherUser = saveUser("他人", false).getId();
        for (int i = 0; i < 11; i++) {
            saveSchedule(null, viewerId, MinViewRole.ANYONE);
            saveSchedule(null, otherUser, MinViewRole.ANYONE);
        }
        assertCount("schedules", 11, 10);
    }

    @Test
    @DisplayName("AC4 組織全体公開の閾値は親ORGの直接ロールで評価する")
    void チームMEMBERでも親ORGがSUPPORTERなら総数へ含めない() {
        em.createNativeQuery("INSERT INTO team_org_memberships(team_id, organization_id, status, invited_at, created_at) VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId).setParameter("oid", orgId).executeUpdate();
        Long supporterId = saveUser("親組織応援者", false).getId();
        join(supporterId, ScopeType.TEAM, teamId);
        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.ORGANIZATION, orgId, RoleKind.SUPPORTER);
        for (int i = 0; i < 11; i++) {
            save(ScheduleEntity.builder().teamId(teamId).title(keyword)
                    .startAt(LocalDateTime.of(2026, 5, 1, 10, 0)).eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.ORGANIZATION).minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED).build());
        }
        flushClear();
        SearchResultResponse denied = searchService.search(keyword, supporterId);
        assertThat(denied.getCounts().get("schedules")).isZero();
        assertThat(denied.getResults().get("schedules")).isEmpty();
        SearchResultResponse allowed = searchService.search(keyword, viewerId);
        assertThat(allowed.getCounts().get("schedules")).isEqualTo(11L);
        assertThat(allowed.getResults().get("schedules")).hasSize(10);
    }

    @Test
    @DisplayName("AC4 F00で許可された親ORG予定も既存検索母集団を拡張しない")
    void 子孫からのF00許可だけでは未所属ORGを検索しない() {
        em.createNativeQuery("INSERT INTO team_org_memberships(team_id, organization_id, status, invited_at, created_at) VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId).setParameter("oid", orgId).executeUpdate();
        Long childMember = saveUser("子TEAMメンバー", false).getId();
        join(childMember, ScopeType.TEAM, teamId);
        List<Long> parentIds = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            parentIds.add(save(ScheduleEntity.builder().organizationId(orgId).title(keyword)
                    .startAt(LocalDateTime.of(2026, 5, 1, 10, 0)).eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.ORGANIZATION).minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED).build()).getId());
        }
        flushClear();
        assertThat(visibilityChecker.filterAccessible(ReferenceType.SCHEDULE, parentIds, childMember))
                .containsExactlyInAnyOrderElementsOf(parentIds);
        SearchResultResponse response = searchService.search(keyword, childMember);
        assertThat(response.getCounts().get("schedules")).isZero();
        assertThat(response.getResults().get("schedules")).isEmpty();
    }

    @Test
    @DisplayName("AC6 別Txの途中削除後も検索総数と結果は同じ実MySQLスナップショット")
    void 別Tx削除を挟んでも一貫したスナップショットを読む() {
        populate("schedules", 11);
        flushClear();
        Long deletedId = jdbc.queryForObject("SELECT MIN(id) FROM schedules WHERE team_id = ? AND title = ?", Long.class, teamId, keyword);
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setReadOnly(true);
        TransactionTemplate independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            read.executeWithoutResult(tx -> {
                assertThat(jdbc.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation))
                        .isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedules WHERE team_id = ? AND title = ?", Long.class, teamId, keyword))
                        .isEqualTo(11L);
                independent.executeWithoutResult(other -> assertThat(jdbc.update(
                        "DELETE FROM schedules WHERE id = ? AND team_id = ? AND title = ?", deletedId, teamId, keyword)).isEqualTo(1));
                SearchResultResponse response = searchService.search(keyword, viewerId);
                assertThat(response.getCounts().get("schedules")).isEqualTo(11L);
                assertThat(response.getResults().get("schedules")).hasSize(10);
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schedules WHERE team_id = ? AND title = ?", Long.class, teamId, keyword))
                    .isEqualTo(10L);
        } finally {
            // この一試験だけ独立Txのためfixtureをcommitする。捕捉した自身ID/検索語だけを回収する。
            try {
                independent.executeWithoutResult(tx -> {
                    jdbc.update("DELETE FROM schedules WHERE team_id = ? AND title = ?", teamId, keyword);
                    jdbc.update("DELETE FROM memberships WHERE user_id = ? AND ((scope_type = 'TEAM' AND scope_id = ?) OR (scope_type = 'ORGANIZATION' AND scope_id = ?))", viewerId, teamId, orgId);
                    jdbc.update("DELETE FROM teams WHERE id = ?", teamId);
                    jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
                    jdbc.update("DELETE FROM users WHERE id = ?", viewerId);
                });
            } finally {
                TestTransaction.start();
                TestTransaction.flagForRollback();
            }
        }
    }

    @Test
    @DisplayName("AC7 未認証の検索APIは有効なSecurityフィルタで401")
    void 未認証の検索APIは拒否する() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("q", keyword))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC7 実検索APIはcounts9種と上限を超えた件数を返す")
    void APIエンベロープと可視総数を返す() throws Exception {
        populate("schedules", 11);
        flushClear();
        mockMvc.perform(get("/api/v1/search").param("q", keyword)
                        .header("Authorization", "Bearer " + tokens.issueAccessToken(viewerId, List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.counts.schedules").value(11))
                .andExpect(jsonPath("$.data.counts.length()").value(9))
                .andExpect(jsonPath("$.data.results.schedules.length()").value(10));
    }

    @Test
    @DisplayName("AC8 257候補でも予定Entityは10件だけ取得し候補ごとのEntity取得/N+1を避ける")
    void 予定はscalarバッチと最大10Entityで集計する() {
        populate("schedules", 257);
        flushClear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean enabledBefore = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        SqlIntentCounter.reset();
        try {
            SearchResultResponse response = searchService.search(keyword, viewerId);
            assertThat(response.getCounts().get("schedules")).isEqualTo(257L);
            assertThat(ids(response, "schedules")).hasSize(10);
            assertThat(statistics.getEntityStatistics(ScheduleEntity.class.getName()).getLoadCount()).isEqualTo(10L);
            // 2候補バッチ＋2 F00射影＋最終Entity取得。実SQL捕捉0の偽greenも拒否する。
            // intentCount("schedules") は shift_schedules も拾うため、予定テーブルのFROMだけを数える。
            long scheduleQueries = SqlIntentCounter.capturedSqls().stream()
                    .filter(sql -> sql.toLowerCase(Locale.ROOT).contains(" from schedules "))
                    .count();
            assertThat(scheduleQueries).isBetween(1L, 5L);
            assertThat(SqlIntentCounter.totalCount()).isPositive();
        } finally {
            statistics.setStatisticsEnabled(enabledBefore);
            SqlIntentCounter.reset();
        }
    }

    private void assertCount(String type, long total, int returned) {
        flushClear();
        SearchResultResponse response = searchService.search(keyword, viewerId);
        assertThat(response.getCounts()).hasSize(9);
        assertThat(response.getCounts().get(type)).isEqualTo(total);
        assertThat(response.getResults().get(type)).hasSize(returned);
    }

    private List<Long> ids(SearchResultResponse response, String type) {
        return response.getResults().get(type).stream().map(row -> (Long) row.get("id")).toList();
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    private <T> T save(T entity) {
        em.persist(entity);
        return entity;
    }

    private UserEntity saveUser(String displayName, boolean searchable) {
        return save(UserEntity.builder().email(UUID.randomUUID() + "@example.invalid").passwordHash("fixture-hash")
                .lastName("試験").firstName("対象").displayName(displayName).locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE).isSearchable(searchable).build());
    }

    private TeamEntity saveTeam(String name) {
        return save(TeamEntity.builder().slug("c0700-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
    }

    private OrganizationEntity saveOrganization(String name) {
        return save(OrganizationEntity.builder().slug("c0700-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build());
    }

    private void join(Long userId, ScopeType type, Long scopeId) {
        MembershipTestHelper.insertMembership(em, userId, type, scopeId, RoleKind.MEMBER);
    }

    private ScheduleEntity saveSchedule(Long scopeTeamId, Long personalUserId, MinViewRole threshold) {
        return save(ScheduleEntity.builder().teamId(scopeTeamId).userId(personalUserId).title(keyword)
                .startAt(LocalDateTime.of(2026, 5, 1, 10, 0)).eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(threshold).status(ScheduleStatus.SCHEDULED).build());
    }

    private void populate(String type, int count) {
        for (int i = 0; i < count; i++) {
            switch (type) {
                case "schedules" -> saveSchedule(teamId, null, MinViewRole.MEMBER_PLUS);
                case "events" -> save(EventEntity.builder().scopeType(EventScopeType.ORGANIZATION).scopeId(orgId)
                        .slug("c0700-" + UUID.randomUUID().toString().substring(0, 8)).subtitle(keyword)
                        .visibility(EventVisibility.MEMBERS_ONLY).status(EventStatus.PUBLISHED).createdBy(viewerId).build());
                case "reservations" -> {
                    Long facilityId = save(SharedFacilityEntity.builder().scopeType("TEAM").scopeId(teamId)
                            .name(keyword).facilityType(FacilityType.MEETING_ROOM).capacity(10).createdBy(viewerId).build()).getId();
                    save(FacilityBookingEntity.builder().facilityId(facilityId).bookedBy(viewerId)
                            .bookingDate(LocalDate.of(2026, 7, 1)).timeFrom(LocalTime.of(9, 0)).timeTo(LocalTime.of(11, 0))
                            .purpose(keyword).slotCount(1).usageFee(BigDecimal.ZERO).equipmentFee(BigDecimal.ZERO)
                            .totalFee(BigDecimal.ZERO).build());
                }
                case "shifts" -> save(ShiftScheduleEntity.builder().teamId(teamId).title(keyword)
                        .periodType(ShiftPeriodType.MONTHLY).startDate(LocalDate.of(2026, 5, 1)).endDate(LocalDate.of(2026, 5, 31))
                        .status(ShiftScheduleStatus.PUBLISHED).publishedAt(LocalDateTime.of(2026, 4, 20, 10, 0)).build());
                case "safetyChecks" -> save(SafetyCheckEntity.builder().scopeType(SafetyCheckScopeType.TEAM).scopeId(teamId)
                        .title(keyword).message(keyword).isDrill(false).status(SafetyCheckStatus.ACTIVE).build());
                case "queues" -> {
                    Long categoryId = save(QueueCategoryEntity.builder().scopeType(QueueScopeType.TEAM).scopeId(teamId).name(keyword).build()).getId();
                    Long counterId = save(QueueCounterEntity.builder().categoryId(categoryId).name(keyword).build()).getId();
                    save(QueueTicketEntity.builder().categoryId(categoryId).counterId(counterId).ticketNumber("T" + i)
                            .position(1).issuedDate(LocalDate.of(2026, 7, 1)).guestName(keyword).source(TicketSource.ONLINE)
                            .userId(viewerId).build());
                }
                case "teams" -> saveTeam(keyword + i);
                case "organizations" -> saveOrganization(keyword + i);
                case "users" -> join(saveUser(keyword + i, true).getId(), ScopeType.TEAM, teamId);
                default -> throw new IllegalArgumentException("未定義の検索種別: " + type);
            }
        }
    }
}

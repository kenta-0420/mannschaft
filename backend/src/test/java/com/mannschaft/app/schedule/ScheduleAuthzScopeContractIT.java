package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.entity.ScheduleTargetEntity;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserIcalTokenRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.schedule.service.ScheduleDelegationNotifier;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * schedule ドメイン 自己スコープ／認可 API 契約テスト（認可根治 Wave4 ロットC）。
 *
 * <p>本テストは次のエンドポイントについて、他利用者のデータへ到達できないことと、
 * 正当な利用者では成功することの双方を固定する。</p>
 *
 * <h2>自己スコープ（リクエストが対象の識別子を受け取らない）</h2>
 * <ul>
 *   <li>{@code IcalController#getToken} / {@code #regenerateToken} / {@code #deleteToken} —
 *       トークンは予定を読む能力そのものであり、対象は常に呼出ユーザーのトークン行である。</li>
 *   <li>{@code GoogleCalendarController#getConnectionStatus} / {@code #connect} /
 *       {@code #disconnect} / {@code #getSyncSettings} / {@code #getPersonalSync} /
 *       {@code #togglePersonalSync} / {@code #manualSync}</li>
 *   <li>{@code PersonalScheduleController#createSchedule} / {@code #listSchedules}</li>
 *   <li>{@code ScheduleCommonController#getMyCalendar} / {@code #getMyAttendanceStats}</li>
 * </ul>
 *
 * <h2>識別子を受け取るため認可判定を要するもの</h2>
 * <ul>
 *   <li>{@code GoogleCalendarController#toggleTeamSync} / {@code #toggleOrgSync} —
 *       スコープのアクティブメンバーのみ。非メンバーは存在秘匿の 404 に畳む。</li>
 *   <li>{@code PersonalScheduleController#getSchedule} / {@code #updateSchedule} /
 *       {@code #deleteSchedule} / {@code #batchDeleteSchedules} — 所有者本人のみ。</li>
 *   <li>{@code ScheduleDelegationController#create} / {@code #withdraw} / {@code #me} —
 *       スケジュール実体由来のスコープを閲覧できる利用者のみ。
 *       {@code #accept} / {@code #reject} — 委任のあて先本人のみ。</li>
 *   <li>{@code TeamScheduleController#listSchedules} /
 *       {@code OrgScheduleController#listSchedules} — 可視なものだけを返す。</li>
 *   <li>{@code TeamScheduleController#bulkUpdateAttendances} —
 *       スケジュール実体由来スコープの ADMIN/DEPUTY_ADMIN のみ。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("schedule ドメイン 自己スコープ・認可 API 契約テスト（認可根治 Wave4 ロットC）")
class ScheduleAuthzScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleKeepRepository scheduleKeepRepository;

    @Autowired
    private ScheduleDelegationRepository delegationRepository;

    @Autowired
    private UserIcalTokenRepository icalTokenRepository;

    @Autowired
    private UserGoogleCalendarConnectionRepository connectionRepository;

    /** 外部 API 呼び出しは本テストの対象外のため遮断する。 */
    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @MockitoBean
    private ScheduleDelegationNotifier scheduleDelegationNotifier;

    @PersistenceContext
    private EntityManager em;

    /** 他人の識別子として使う十分に大きい値（実在しないことを担保する）。 */
    private static final long FOREIGN_USER_ID = 900_000_001L;

    private Long teamId;
    private Long orgId;
    private String teamSlug;
    private String orgSlug;

    /** チーム・組織の一般メンバー。多くのケースで「正当な利用者」を務める。 */
    private Long memberId;
    /** チーム・組織のもう 1 人のメンバー。代理のあて先を務める。 */
    private Long delegateId;
    /** チームの ADMIN。 */
    private Long adminId;
    /** どこにも所属しない利用者。越境を試みる側。 */
    private Long outsiderId;

    private Long teamScheduleId;
    private Long personalScheduleId;
    private java.util.UUID delegationId;

    @BeforeEach
    void setUp() {
        teamSlug = "w4c-team-" + System.nanoTime();
        orgSlug = "w4c-org-" + System.nanoTime();
        teamId = insertTeam("W4C チーム", teamSlug);
        orgId = insertOrganization("W4C 組織", orgSlug);

        memberId = insertUser("w4c-member@example.com");
        delegateId = insertUser("w4c-delegate@example.com");
        adminId = insertUser("w4c-admin@example.com");
        outsiderId = insertUser("w4c-outsider@example.com");

        // memberships（所属）と user_roles（権限ロール）は別系統のため双方に行を張る。
        for (Long userId : List.of(memberId, delegateId, adminId)) {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        }
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        // outsiderId はどこにも所属させない。
        em.flush();

        teamScheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("W4C チーム練習")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(adminId)
                .build()).getId();

        personalScheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .userId(memberId)
                .title("W4C 個人予定")
                .startAt(LocalDateTime.of(2026, 4, 3, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 3, 12, 0))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(memberId)
                .build()).getId();

        delegationId = delegationRepository.save(ScheduleDelegationEntity.builder()
                .scheduleId(teamScheduleId)
                .delegatorId(memberId)
                .delegateId(delegateId)
                .teamId(teamId)
                .status(ScheduleDelegationStatus.PENDING)
                .reason("出張のため")
                .build()).getId();

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("CMP-260902-0058: 共有予定詳細の説明文・色")
    class SharedScheduleDetail {

        @ParameterizedTest
        @CsvSource({"false", "true"})
        @DisplayName("チーム・組織の数値IDとslugで保存済み詳細と既存フィールドを返す")
        void 保存済み詳細を取得できる(boolean organization) throws Exception {
            Long scheduleId = createSchedule(organization, "集合は正門\n持ち物：水筒", "#a855f7", MinViewRole.MEMBER_PLUS);
            setAuthentication(memberId);
            for (Object scopeId : List.of(organization ? orgId : teamId, organization ? orgSlug : teamSlug)) {
                mockMvc.perform(get(detailUrl(organization), scopeId, scheduleId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.id").value(scheduleId))
                        .andExpect(jsonPath("$.data.detail.description").value("集合は正門\n持ち物：水筒"))
                        .andExpect(jsonPath("$.data.detail.color").value("#a855f7"))
                        .andExpect(jsonPath("$.data.detail.visibility").value("MEMBERS_ONLY"))
                        .andExpect(jsonPath("$.data.content.title").value("詳細契約テスト"))
                        // addFilters=false のためユーザー TZ は未解決。既存 Jackson 契約で JST の保存日時を UTC へ変換する。
                        .andExpect(jsonPath("$.data.time.startAt").value("2026-04-05T01:00:00Z"))
                        .andExpect(jsonPath("$.data.scope.scopeName").value(organization ? "W4C 組織" : "W4C チーム"))
                        .andExpect(jsonPath("$.data.reminders").isArray())
                        .andExpect(jsonPath("$.data.scheduledTasks").isArray());
            }
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("説明文がnull・空、色がnullでも詳細取得は成功する")
        void 空の詳細を取得できる(String description) throws Exception {
            setAuthentication(memberId);
            for (boolean organization : List.of(false, true)) {
                Long scheduleId = createSchedule(organization, description, null, MinViewRole.MEMBER_PLUS);
                var result = mockMvc.perform(get(detailUrl(organization), organization ? orgId : teamId, scheduleId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.detail").exists())
                        .andExpect(jsonPath("$.data.detail.color").doesNotExist());
                if (description == null) {
                    result.andExpect(jsonPath("$.data.detail.description").doesNotExist());
                } else {
                    result.andExpect(jsonPath("$.data.detail.description").value(description));
                }
            }
        }

        @ParameterizedTest
        @CsvSource({"false", "true"})
        @DisplayName("ANYONEはvisibilityを緩めず、閲覧可能な非所属SYSTEM_ADMINへ対象者名を開示しない")
        void 非所属者の可視性を維持する(boolean organization) throws Exception {
            Long scheduleId = createSchedule(organization, "公開の集合案内", "#a855f7", MinViewRole.ANYONE);
            var schedule = scheduleRepository.findById(scheduleId).orElseThrow();
            schedule.updateTargetMode(ScheduleTargetMode.SELECTED_MEMBERS);
            em.persist(ScheduleTargetEntity.builder().scheduleId(scheduleId).userId(memberId).build());
            em.flush();
            em.clear();
            setAuthentication(memberId);
            mockMvc.perform(get(detailUrl(organization), organization ? orgId : teamId, scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.targets.length()").value(1))
                    .andExpect(jsonPath("$.data.targets[0].userId").value(memberId))
                    .andExpect(jsonPath("$.data.targets[0].displayName").isNotEmpty());
            setAuthentication(outsiderId);
            mockMvc.perform(get(detailUrl(organization), organization ? orgId : teamId, scheduleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data.detail").doesNotExist());

            Long restrictedId = createSchedule(organization, "所属者向けの説明", null, MinViewRole.MEMBER_PLUS);
            mockMvc.perform(get(detailUrl(organization), organization ? orgId : teamId, restrictedId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.data.detail").doesNotExist());

            // 所属を追加せず、F00の既存SYSTEM_ADMIN閲覧経路だけを使う。
            MembershipTestHelper.insertUserRole(em, outsiderId, "SYSTEM_ADMIN", null, null);
            em.flush();
            em.clear();
            mockMvc.perform(get(detailUrl(organization), organization ? orgId : teamId, scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.detail.description").value("公開の集合案内"))
                    .andExpect(jsonPath("$.data.targetCount").value(1))
                    .andExpect(jsonPath("$.data.targets").isEmpty());
        }

        /** 旧実装の実機 red は同じ対象者の更新で unique(schedule_id,user_id) が衝突する。 */
        @ParameterizedTest
        @CsvSource({"false", "true"})
        @DisplayName("同集合・部分重複集合の更新と全員へのクリアは他予定を変更しない")
        void 選択対象者を再更新しても説明_色_他予定を保持する(boolean organization) throws Exception {
            authorizeScopeAdmin(organization);
            Long scheduleId = createSelectedSchedule(organization, "集合は正門\n持ち物：水筒", "#a855f7", List.of(memberId));
            Long otherId = createSelectedSchedule(organization, "別予定の説明", "#22c55e", List.of(delegateId));
            setAuthentication(adminId);

            // 同集合の無変更保存、既存対象を含む追加、部分重複集合を順に実HTTPで更新する。
            for (List<Long> ids : List.of(List.of(memberId), List.of(memberId, delegateId), List.of(delegateId))) {
                mockMvc.perform(patch(detailUrl(organization), organization ? orgId : teamId, scheduleId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "description", "集合は正門\n持ち物：水筒",
                                        "targetMode", "SELECTED_MEMBERS", "targetUserIds", ids))))
                        .andExpect(status().isOk());
                em.flush();
                em.clear();
                assertSelectedDetail(organization, scheduleId, "集合は正門\n持ち物：水筒", "#a855f7", ids);
                assertSelectedDetail(organization, otherId, "別予定の説明", "#22c55e", List.of(delegateId));
                setAuthentication(adminId);
            }

            mockMvc.perform(patch(detailUrl(organization), organization ? orgId : teamId, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "targetMode", "ALL_MEMBERS", "targetUserIds", List.of()))))
                    .andExpect(status().isOk());
            em.flush();
            em.clear();
            setAuthentication(memberId);
            mockMvc.perform(get(detailUrl(organization), organization ? orgId : teamId, scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.detail.description").value("集合は正門\n持ち物：水筒"))
                    .andExpect(jsonPath("$.data.detail.color").value("#a855f7"))
                    .andExpect(jsonPath("$.data.targetMode").value("ALL_MEMBERS"))
                    .andExpect(jsonPath("$.data.targetCount").value(3))
                    .andExpect(jsonPath("$.data.targets").isEmpty());
            assertThat(em.createQuery("SELECT COUNT(t) FROM ScheduleTargetEntity t WHERE t.scheduleId = :id", Long.class)
                    .setParameter("id", scheduleId).getSingleResult()).isZero();
            assertSelectedDetail(organization, otherId, "別予定の説明", "#22c55e", List.of(delegateId));
        }

        @ParameterizedTest
        @CsvSource({"false", "true"})
        @DisplayName("所属外対象者の検証失敗は独立HTTPトランザクションで親と対象者を巻き戻す")
        void 対象者の検証失敗は実トランザクションで巻き戻る(boolean organization) throws Exception {
            authorizeScopeAdmin(organization);
            Long scheduleId = createSelectedSchedule(organization, "保存済み説明", "#a855f7", List.of(memberId));
            Long otherId = createSelectedSchedule(organization, "別予定の説明", "#22c55e", List.of(delegateId));
            // 外側テストTXを終了し、Serviceの実TXが失敗時にrollbackしてから別GETで観測する。
            TestTransaction.flagForCommit();
            TestTransaction.end();
            try {
                setAuthentication(adminId);
                mockMvc.perform(patch(detailUrl(organization), organization ? orgId : teamId, scheduleId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "title", "保存してはいけない題名", "description", "保存してはいけない説明",
                                        "targetMode", "SELECTED_MEMBERS", "targetUserIds", List.of(outsiderId)))))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.error.code").value("SCHEDULE_094"));
                assertSelectedDetail(organization, scheduleId, "保存済み説明", "#a855f7", List.of(memberId));
                assertSelectedDetail(organization, otherId, "別予定の説明", "#22c55e", List.of(delegateId));
            } finally {
                // Testcontainers専用DBで今回commitしたfixtureの既知IDだけを片付ける。
                TestTransaction.start();
                delegationRepository.deleteById(delegationId);
                em.flush();
                scheduleRepository.deleteAllById(List.of(scheduleId, otherId, teamScheduleId, personalScheduleId));
                em.flush();
                List<Long> userIds = List.of(memberId, delegateId, adminId, outsiderId);
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)")
                        .setParameter("ids", userIds).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)")
                        .setParameter("ids", userIds).executeUpdate();
                em.createNativeQuery("DELETE FROM teams WHERE id = :id").setParameter("id", teamId).executeUpdate();
                em.createNativeQuery("DELETE FROM organizations WHERE id = :id").setParameter("id", orgId).executeUpdate();
                em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)").setParameter("ids", userIds).executeUpdate();
                TestTransaction.flagForCommit();
                TestTransaction.end();
            }
        }

        private void authorizeScopeAdmin(boolean organization) {
            if (organization) {
                MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", null, orgId);
                em.flush();
            }
        }

        private Long createSelectedSchedule(boolean organization, String description, String color, List<Long> ids) {
            Long id = createSchedule(organization, description, color, MinViewRole.MEMBER_PLUS);
            scheduleRepository.findById(id).orElseThrow().updateTargetMode(ScheduleTargetMode.SELECTED_MEMBERS);
            for (Long userId : ids) {
                em.persist(ScheduleTargetEntity.builder().scheduleId(id).userId(userId).build());
            }
            em.flush();
            em.clear();
            return id;
        }

        private void assertSelectedDetail(boolean organization, Long id, String description, String color,
                                          List<Long> ids) throws Exception {
            setAuthentication(memberId);
            String response = mockMvc.perform(get(detailUrl(organization), organization ? orgId : teamId, id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.title").value("詳細契約テスト"))
                    .andExpect(jsonPath("$.data.detail.description").value(description))
                    .andExpect(jsonPath("$.data.detail.color").value(color))
                    .andExpect(jsonPath("$.data.targetMode").value("SELECTED_MEMBERS"))
                    .andExpect(jsonPath("$.data.targetCount").value(ids.size()))
                    .andExpect(jsonPath("$.data.targets.length()").value(ids.size()))
                    .andReturn().getResponse().getContentAsString();
            var targets = objectMapper.readTree(response).path("data").path("targets");
            var returnedIds = new java.util.ArrayList<Long>();
            for (var target : targets) {
                returnedIds.add(target.path("userId").asLong());
                assertThat(target.path("displayName").asText()).isEqualTo("W4C テスト");
            }
            assertThat(returnedIds).containsExactlyInAnyOrderElementsOf(ids);
        }

        private String detailUrl(boolean organization) {
            return organization ? "/api/v1/organizations/{scopeId}/schedules/{scheduleId}"
                    : "/api/v1/teams/{scopeId}/schedules/{scheduleId}";
        }

        private Long createSchedule(boolean organization, String description, String color, MinViewRole minViewRole) {
            Long scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                    .teamId(organization ? null : teamId)
                    .organizationId(organization ? orgId : null)
                    .title("詳細契約テスト")
                    .description(description)
                    .color(color)
                    .startAt(LocalDateTime.of(2026, 4, 5, 10, 0))
                    .endAt(LocalDateTime.of(2026, 4, 5, 12, 0))
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(minViewRole)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false)
                    .allowProxyAttendance(false)
                    .isProxyAutoAccept(false)
                    .createdBy(adminId)
                    .build()).getId();
            em.flush();
            em.clear();
            return scheduleId;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // iCal トークン（能力トークン）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("iCal トークン管理")
    class IcalToken {

        @Test
        @DisplayName("IcalController#getToken は呼出ユーザーのトークンだけを返す（他人のトークンは現れない）")
        void getToken_は本人のトークンだけを返す() throws Exception {
            icalTokenRepository.insert(memberId, "w4c-member-token", true);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/ical/token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.token").value(
                            org.hamcrest.Matchers.not("w4c-member-token")));

            em.flush();
            em.clear();
            assertThat(icalTokenRepository.findByUserId(memberId).orElseThrow().getToken())
                    .isEqualTo("w4c-member-token");
        }

        @Test
        @DisplayName("IcalController#regenerateToken は他利用者のトークンを置き換えない")
        void regenerateToken_は他人のトークンを変えない() throws Exception {
            icalTokenRepository.insert(memberId, "w4c-member-token", true);
            icalTokenRepository.insert(outsiderId, "w4c-outsider-token", true);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/me/ical/token/regenerate"))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertThat(icalTokenRepository.findByUserId(memberId).orElseThrow().getToken())
                    .isEqualTo("w4c-member-token");
            assertThat(icalTokenRepository.findByUserId(outsiderId).orElseThrow().getToken())
                    .isNotEqualTo("w4c-outsider-token");
        }

        @Test
        @DisplayName("IcalController#deleteToken は他利用者のトークンを失効させない")
        void deleteToken_は他人のトークンを消さない() throws Exception {
            icalTokenRepository.insert(memberId, "w4c-member-token", true);
            icalTokenRepository.insert(outsiderId, "w4c-outsider-token", true);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/me/ical/token"))
                    .andExpect(status().isNoContent());

            // 派生 delete は EntityManager#remove まで（同一トランザクション内で未確定）のため、
            // clear の前に flush して削除を確定させる。flush 無しの clear は削除を捨ててしまう。
            em.flush();
            em.clear();
            assertThat(icalTokenRepository.findByUserId(memberId)).isPresent();
            assertThat(icalTokenRepository.findByUserId(outsiderId)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Google Calendar 連携
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Google Calendar 連携（自己スコープ）")
    class GoogleCalendarSelfScope {

        @Test
        @DisplayName("GoogleCalendarController#getConnectionStatus / #getPersonalSync / #getSyncSettings は"
                + "他利用者の連携を映さない")
        void 参照系は他人の連携を映さない() throws Exception {
            connectMember();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/google-calendar/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.connected").value(false));
            mockMvc.perform(get("/api/v1/me/google-calendar/personal-sync"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.connected").value(false));
            mockMvc.perform(get("/api/v1/me/calendar-sync-settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.connected").value(false));

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/me/google-calendar/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.connected").value(true));
        }

        @Test
        @DisplayName("GoogleCalendarController#disconnect / #togglePersonalSync / #manualSync は"
                + "他利用者の連携には届かない")
        void 更新系は他人の連携に届かない() throws Exception {
            connectMember();

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/me/google-calendar/disconnect"))
                    .andExpect(jsonPath("$.error.code").value("GCAL_001"));
            mockMvc.perform(put("/api/v1/me/google-calendar/personal-sync")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isEnabled", false))))
                    .andExpect(jsonPath("$.error.code").value("GCAL_001"));
            mockMvc.perform(post("/api/v1/me/google-calendar/sync"))
                    .andExpect(jsonPath("$.error.code").value("GCAL_001"));

            assertThat(connectionRepository.findByUserIdAndIsActiveTrue(memberId)).isPresent();
        }

        @Test
        @DisplayName("GoogleCalendarController#togglePersonalSync / #manualSync は本人の連携に対しては成功する")
        void 更新系は本人の連携では成功する() throws Exception {
            connectMember();

            setAuthentication(memberId);
            mockMvc.perform(put("/api/v1/me/google-calendar/personal-sync")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isEnabled", false))))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/me/google-calendar/sync"))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("GoogleCalendarController#connect は state 検証に失敗しても他利用者の連携を壊さない")
        void connect_は他人の連携を壊さない() throws Exception {
            connectMember();

            // Redis は基底クラスで Mock 化されているため、値操作を明示的に張る。
            // 保存済み state が無い状態＝CSRF 検証に失敗する状態を作る。
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
            given(redisTemplate.opsForValue()).willReturn(valueOperations);

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/me/google-calendar/connect")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "code", "dummy-code",
                                    "state", "invalid-state",
                                    "redirectUri", "https://example.com/callback"))))
                    .andExpect(jsonPath("$.error.code").value("GCAL_003"));

            assertThat(connectionRepository.findByUserId(outsiderId)).isEmpty();
            assertThat(connectionRepository.findByUserIdAndIsActiveTrue(memberId)).isPresent();
        }
    }

    @Nested
    @DisplayName("Google Calendar スコープ同期トグル（所属認可）")
    class CalendarScopeSync {

        @Test
        @DisplayName("GoogleCalendarController#toggleTeamSync: 非メンバーは存在秘匿の GCAL_010")
        void toggleTeamSync_非メンバーは拒否される() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(put("/api/v1/me/teams/{teamId}/calendar-sync", teamId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isEnabled", false))))
                    .andExpect(jsonPath("$.error.code").value("GCAL_010"));
        }

        @Test
        @DisplayName("GoogleCalendarController#toggleTeamSync: メンバーは 200（正常系）")
        void toggleTeamSync_メンバーは成功する() throws Exception {
            connectMember();

            setAuthentication(memberId);
            mockMvc.perform(put("/api/v1/me/teams/{teamId}/calendar-sync", teamId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isEnabled", false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scopeId").value(teamId));
        }

        @Test
        @DisplayName("GoogleCalendarController#toggleOrgSync: 非メンバーは存在秘匿の GCAL_010")
        void toggleOrgSync_非メンバーは拒否される() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(put("/api/v1/me/organizations/{orgId}/calendar-sync", orgId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isEnabled", false))))
                    .andExpect(jsonPath("$.error.code").value("GCAL_010"));
        }

        @Test
        @DisplayName("GoogleCalendarController#toggleOrgSync: メンバーは 200（正常系）")
        void toggleOrgSync_メンバーは成功する() throws Exception {
            connectMember();

            setAuthentication(memberId);
            mockMvc.perform(put("/api/v1/me/organizations/{orgId}/calendar-sync", orgId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isEnabled", false))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.scopeId").value(orgId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 個人スケジュール
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("個人スケジュール")
    class PersonalSchedules {

        @Test
        @DisplayName("PersonalScheduleController#getSchedule: 他人の予定 ID では SCHEDULE_022")
        void getSchedule_他人の予定は取得できない() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/schedules/{id}", personalScheduleId))
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_022"));
        }

        @Test
        @DisplayName("PersonalScheduleController#getSchedule: 所有者は 200（正常系）")
        void getSchedule_所有者は取得できる() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/me/schedules/{id}", personalScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.title").value("W4C 個人予定"));
        }

        @Test
        @DisplayName("PersonalScheduleController#updateSchedule: 他人の予定は書き換えられない")
        void updateSchedule_他人の予定は更新できない() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/me/schedules/{id}", personalScheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "乗っ取り"))))
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_022"));

            assertThat(scheduleRepository.findById(personalScheduleId).orElseThrow().getTitle())
                    .isEqualTo("W4C 個人予定");
        }

        @Test
        @DisplayName("PersonalScheduleController#updateSchedule: 所有者は 200（正常系）")
        void updateSchedule_所有者は更新できる() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/me/schedules/{id}", personalScheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "改題"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.title").value("改題"));
        }

        @Test
        @DisplayName("PersonalScheduleController#deleteSchedule: 他人の予定は削除されない")
        void deleteSchedule_他人の予定は削除できない() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/me/schedules/{id}", personalScheduleId))
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_022"));

            // スケジュールは論理削除（PersonalScheduleService が softDelete + save）のため deleted_at を見る。
            assertThat(scheduleRepository.findById(personalScheduleId).orElseThrow().getDeletedAt())
                    .isNull();
        }

        @Test
        @DisplayName("PersonalScheduleController#deleteSchedule: 所有者は 204（正常系）")
        void deleteSchedule_所有者は削除できる() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(delete("/api/v1/me/schedules/{id}", personalScheduleId))
                    .andExpect(status().isNoContent());

            em.flush();
            em.clear();
            // ScheduleEntity は @SQLRestriction("deleted_at IS NULL") を持つため、
            // 論理削除された行は SQL 経由の検索から見えなくなる（＝取得できないことが削除の証跡）。
            assertThat(scheduleRepository.findById(personalScheduleId)).isEmpty();
        }

        @Test
        @DisplayName("PersonalScheduleController#batchDeleteSchedules: 他人の予定は 1 件ずつ判定されスキップされる")
        void batchDeleteSchedules_他人の予定はスキップされる() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/me/schedules/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("ids", List.of(personalScheduleId)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deletedCount").value(0))
                    .andExpect(jsonPath("$.data.skippedCount").value(1));

            em.flush();
            em.clear();
            assertThat(scheduleRepository.findById(personalScheduleId).orElseThrow().getDeletedAt())
                    .isNull();
        }

        @Test
        @DisplayName("PersonalScheduleController#batchDeleteSchedules: 自分の予定は削除される（正常系）")
        void batchDeleteSchedules_自分の予定は削除される() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(delete("/api/v1/me/schedules/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("ids", List.of(personalScheduleId)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.deletedCount").value(1));

            em.flush();
            em.clear();
            // ScheduleEntity は @SQLRestriction("deleted_at IS NULL") を持つため、
            // 論理削除された行は SQL 経由の検索から見えなくなる（＝取得できないことが削除の証跡）。
            assertThat(scheduleRepository.findById(personalScheduleId)).isEmpty();
        }

        @Test
        @DisplayName("PersonalScheduleController#listSchedules は他利用者の予定を含まない")
        void listSchedules_は他人の予定を含まない() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/schedules")
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/me/schedules")
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("PersonalScheduleController#createSchedule は呼出ユーザーを所有者として作る")
        void createSchedule_は本人所有で作られる() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/me/schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "自分の予定",
                                    "startAt", "2026-05-01T10:00:00+09:00",
                                    "endAt", "2026-05-01T11:00:00+09:00",
                                    "allDay", false,
                                    "eventType", "OTHER"))))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();
            assertThat(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    outsiderId,
                    LocalDateTime.of(2026, 5, 1, 0, 0),
                    LocalDateTime.of(2026, 5, 2, 0, 0)))
                    .hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 横断カレンダー・個人統計
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("横断カレンダー・個人統計")
    class MyViews {

        @Test
        @DisplayName("ScheduleCommonController#getMyCalendar は所属していないスコープの予定を返さない")
        void getMyCalendar_は他人のスコープを返さない() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/my/calendar")
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/my/calendar")
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(
                            org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        }

        @Test
        @DisplayName("ScheduleCommonController#getMyAttendanceStats は呼出ユーザーの出欠だけを集計する")
        void getMyAttendanceStats_は本人の出欠だけを集計する() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/attendance-stats")
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(outsiderId))
                    .andExpect(jsonPath("$.data.totalSchedules").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 代理出席
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("代理出席")
    class Delegation {

        @Test
        @DisplayName("ScheduleDelegationController#accept: あて先でない利用者は SCHEDULE_079")
        void accept_あて先でない利用者は拒否される() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/schedule-delegations/{id}/accept", delegationId))
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_079"));

            assertThat(delegationRepository.findById(delegationId).orElseThrow().getStatus())
                    .isEqualTo(ScheduleDelegationStatus.PENDING);
        }

        @Test
        @DisplayName("ScheduleDelegationController#accept: あて先本人は 200（正常系）")
        void accept_あて先本人は承諾できる() throws Exception {
            setAuthentication(delegateId);
            mockMvc.perform(patch("/api/v1/schedule-delegations/{id}/accept", delegationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        }

        @Test
        @DisplayName("ScheduleDelegationController#reject: あて先でない利用者は SCHEDULE_079")
        void reject_あて先でない利用者は拒否される() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(patch("/api/v1/schedule-delegations/{id}/reject", delegationId))
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_079"));

            assertThat(delegationRepository.findById(delegationId).orElseThrow().getStatus())
                    .isEqualTo(ScheduleDelegationStatus.PENDING);
        }

        @Test
        @DisplayName("ScheduleDelegationController#reject: あて先本人は 200（正常系）")
        void reject_あて先本人は辞退できる() throws Exception {
            setAuthentication(delegateId);
            mockMvc.perform(patch("/api/v1/schedule-delegations/{id}/reject", delegationId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJECTED"));
        }

        @Test
        @DisplayName("ScheduleDelegationController#me: 非メンバーは 403")
        void me_非メンバーは拒否される() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/schedules/{id}/delegations/me", teamScheduleId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ScheduleDelegationController#me: メンバーは 200（正常系）")
        void me_メンバーは取得できる() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/schedules/{id}/delegations/me", teamScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asDelegator.delegateId").value(delegateId));
        }

        @Test
        @DisplayName("ScheduleDelegationController#withdraw: 非メンバーは 403")
        void withdraw_非メンバーは拒否される() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/schedules/{id}/delegations/me", teamScheduleId))
                    .andExpect(status().isForbidden());

            assertThat(delegationRepository.findById(delegationId).orElseThrow().getStatus())
                    .isEqualTo(ScheduleDelegationStatus.PENDING);
        }

        @Test
        @DisplayName("ScheduleDelegationController#withdraw: 委任者本人は 204（正常系）")
        void withdraw_委任者本人は取り消せる() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(delete("/api/v1/schedules/{id}/delegations/me", teamScheduleId))
                    .andExpect(status().isNoContent());

            // 管理エンティティへの更新は同一トランザクション内では未確定のため、
            // clear の前に flush して UPDATE を確定させる。
            em.flush();
            em.clear();
            assertThat(delegationRepository.findById(delegationId).orElseThrow().getStatus())
                    .isEqualTo(ScheduleDelegationStatus.CANCELLED);
        }

        @Test
        @DisplayName("ScheduleDelegationController#create: 非メンバーは 403")
        void create_非メンバーは拒否される() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/schedules/{id}/delegations", teamScheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("delegateId", delegateId, "reason", "越境"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("ScheduleDelegationController#create: メンバーは 201（正常系）")
        void create_メンバーは指定できる() throws Exception {
            setAuthentication(adminId);
            mockMvc.perform(post("/api/v1/schedules/{id}/delegations", teamScheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("delegateId", memberId, "reason", "所用のため"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.delegateId").value(memberId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // スコープ一覧・出欠一括更新
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スコープ一覧・出欠一括更新")
    class ScopeListsAndBulk {

        @Test
        @DisplayName("TeamScheduleController#listSchedules は可視なものだけを返す")
        void listSchedules_チームは可視分だけ返す() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules", teamSlug)
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules", teamSlug)
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("OrgScheduleController#listSchedules は可視なものだけを返す")
        void listSchedules_組織は可視分だけ返す() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules", orgSlug)
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("TeamScheduleController#bulkUpdateAttendances: 一般メンバーは 403")
        void bulkUpdateAttendances_一般メンバーは拒否される() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}/attendances/bulk",
                            teamSlug, teamScheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "attendances", List.of(Map.of(
                                            "userId", delegateId, "status", "ATTENDING"))))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("TeamScheduleController#bulkUpdateAttendances: ADMIN は 204（正常系）")
        void bulkUpdateAttendances_ADMINは成功する() throws Exception {
            setAuthentication(adminId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}/attendances/bulk",
                            teamSlug, teamScheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "attendances", List.of(Map.of(
                                            "userId", delegateId, "status", "ATTENDING"))))))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("実在しないユーザー ID を名乗っても越境できない")
        void 実在しない利用者は越境できない() throws Exception {
            setAuthentication(FOREIGN_USER_ID);
            mockMvc.perform(get("/api/v1/me/schedules/{id}", personalScheduleId))
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_022"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // CMP-054: マイカレンダーのチーム予定 数値ID/slug 両対応（F03.19 Wave2-b2）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * CMP-054: {@code /api/v1/my/calendar} が返す {@code scopeId} は数値の内部 BIGINT ID だが、
     * {@code TeamScheduleController} / {@code OrgScheduleController} は
     * {@code teamService.resolveTeamId} / {@code organizationService.resolveOrgId}（slug 専用）を
     * 直接呼んでいたため、数値 ID を渡すと必ず 404 になっていた（マイカレンダーからチーム予定を
     * 開くと必ず 404 になる不具合）。{@link com.mannschaft.app.config.TeamScopeId} /
     * {@link com.mannschaft.app.config.OrgScopeId} 型のパス変数へ統一し、数値・slug の両方を
     * 受け付けるようにしたことを固定する。
     */
    @Nested
    @DisplayName("CMP-054: チーム/組織スコープ 数値ID・slug 両対応")
    class CalendarScopeIdCompat {

        @Test
        @DisplayName("AC-a: チーム予定詳細は数値IDで200")
        void getSchedule_チームは数値IDで取得できる() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}",
                            teamId, teamScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(teamScheduleId));
        }

        @Test
        @DisplayName("AC-b: チーム予定詳細はslugでも200（回帰なし）")
        void getSchedule_チームはslugでも取得できる() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}",
                            teamSlug, teamScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(teamScheduleId));
        }

        @Test
        @DisplayName("AC-c: 組織予定詳細は数値ID・slugの両方で200")
        void getSchedule_組織は数値ID_slug両方で取得できる() throws Exception {
            Long orgScheduleId = scheduleRepository.save(ScheduleEntity.builder()
                    .organizationId(orgId)
                    .title("W4C 組織総会")
                    .startAt(LocalDateTime.of(2026, 4, 5, 10, 0))
                    .endAt(LocalDateTime.of(2026, 4, 5, 12, 0))
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false)
                    .allowProxyAttendance(false)
                    .isProxyAutoAccept(false)
                    .createdBy(adminId)
                    .build()).getId();
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules/{scheduleId}",
                            orgId, orgScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(orgScheduleId));
            mockMvc.perform(get("/api/v1/organizations/{orgPublicId}/schedules/{scheduleId}",
                            orgSlug, orgScheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(orgScheduleId));
        }

        @Test
        @DisplayName("AC-d: 存在しない予定IDは数値チームID配下でも404（200で空を返さない）")
        void getSchedule_存在しない予定は404() throws Exception {
            long nonExistentScheduleId = 999_888_777L;
            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}",
                            teamId, nonExistentScheduleId))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}",
                            teamSlug, nonExistentScheduleId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("AC-e: 非所属者は数値ID・slugのどちらでも従来と同じ認可結果（403）で弾かれる")
        void getSchedule_非所属者は数値ID_slug問わず拒否される() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}",
                            teamId, teamScheduleId))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}",
                            teamSlug, teamScheduleId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("AC-f: 一覧・作成・更新・削除も数値IDで通る")
        void 一覧_作成_更新_削除も数値IDで通る() throws Exception {
            setAuthentication(memberId);

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedules", teamId)
                            .param("from", "2026-04-01T00:00:00")
                            .param("to", "2026-04-30T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));

            // スケジュール作成はチームADMIN以上のみ（ScheduleService#checkCreateScopeAccess）。
            setAuthentication(adminId);
            String createBody = objectMapper.writeValueAsString(Map.of(
                    "title", "数値ID作成テスト",
                    "startAt", "2026-05-10T10:00:00+09:00",
                    "endAt", "2026-05-10T11:00:00+09:00",
                    "allDay", false,
                    "eventType", "OTHER",
                    "attendanceRequired", false));
            String createResponse = mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedules", teamId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            Long createdId = objectMapper.readTree(createResponse).get("data").get("id").asLong();

            setAuthentication(adminId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}", teamId, createdId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "数値ID更新済み"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.title").value("数値ID更新済み"));

            mockMvc.perform(delete("/api/v1/teams/{teamPublicId}/schedules/{scheduleId}", teamId, createdId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    /**
     * CMP-260826-1920: キープAPIの数値ID対応と既存slug契約を固定する。
     * 数値IDは実装前に404となるred、slugは既存契約の回帰防止柵である。
     * 既存CMP-054の所属fixtureと実Controller・Service・認可・MySQLを共有する。
     */
    @Nested
    @DisplayName("CMP-260826-1920: キープのスコープID互換性")
    class KeepScopeIdCompat {

        @ParameterizedTest(name = "{0} / {1}")
        @CsvSource({"TEAM, ID", "TEAM, SLUG", "ORGANIZATION, ID", "ORGANIZATION, SLUG"})
        @DisplayName("AC-1〜4: キープ詳細を数値ID・slugで取得できる")
        void 詳細取得は数値IDとslugの両方で成功する(String scope, String representation) throws Exception {
            ScheduleKeepEntity keep = saveScopeKeep(scope);
            setAuthentication(memberId);

            mockMvc.perform(get(keepPath(scope, representation) + "/{keepId}", keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(keep.getId().toString()))
                    .andExpect(jsonPath("$.data.title").value("スコープID互換キープ"));
        }

        @ParameterizedTest(name = "{0} / {1}")
        @CsvSource({"TEAM, ID", "TEAM, SLUG", "ORGANIZATION, ID", "ORGANIZATION, SLUG"})
        @DisplayName("AC-1〜4: キープを数値ID・slugで作成して再読込できる")
        void 作成は数値IDとslugの両方で成功する(String scope, String representation) throws Exception {
            setAuthentication(memberId);
            String response = mockMvc.perform(post(keepPath(scope, representation))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "スコープID作成キープ"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("スコープID作成キープ"))
                    .andExpect(jsonPath("$.data.status").value("KEPT"))
                    .andReturn().getResponse().getContentAsString();

            java.util.UUID keepId = java.util.UUID.fromString(
                    objectMapper.readTree(response).path("data").path("id").asText());
            em.flush();
            em.clear();
            ScheduleKeepEntity saved = scheduleKeepRepository.findById(keepId).orElseThrow();
            assertThat(saved.getTitle()).isEqualTo("スコープID作成キープ");
            assertThat(saved.getCreatedBy()).isEqualTo(memberId);
            assertThat(saved.getTeamId()).isEqualTo("TEAM".equals(scope) ? teamId : null);
            assertThat(saved.getOrganizationId()).isEqualTo("ORGANIZATION".equals(scope) ? orgId : null);
        }

        @ParameterizedTest(name = "{0} / {1}")
        @CsvSource({"TEAM, ID", "TEAM, SLUG", "ORGANIZATION, ID", "ORGANIZATION, SLUG"})
        @DisplayName("AC-5: 非所属者の詳細取得は両表現とも404で存在秘匿する")
        void 非所属者の詳細取得は同じ404で拒否する(String scope, String representation) throws Exception {
            ScheduleKeepEntity keep = saveScopeKeep(scope);
            setAuthentication(outsiderId);

            mockMvc.perform(get(keepPath(scope, representation) + "/{keepId}", keep.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @ParameterizedTest(name = "{0} / {1}")
        @CsvSource({"TEAM, ID", "TEAM, SLUG", "ORGANIZATION, ID", "ORGANIZATION, SLUG"})
        @DisplayName("AC-5: 非所属者の作成は両表現とも404で拒否して保存しない")
        void 非所属者の作成は同じ404で拒否する(String scope, String representation) throws Exception {
            long countBefore = scheduleKeepRepository.count();
            setAuthentication(outsiderId);

            mockMvc.perform(post(keepPath(scope, representation))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "拒否されるキープ"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            em.flush();
            em.clear();
            assertThat(scheduleKeepRepository.count()).isEqualTo(countBefore);
        }

        private ScheduleKeepEntity saveScopeKeep(String scope) {
            ScheduleKeepEntity keep = scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                    .teamId("TEAM".equals(scope) ? teamId : null)
                    .organizationId("ORGANIZATION".equals(scope) ? orgId : null)
                    .title("スコープID互換キープ")
                    .status(ScheduleKeepStatus.KEPT)
                    .sortOrder(0)
                    .createdBy(memberId)
                    .build());
            em.flush();
            em.clear();
            return keep;
        }

        private String keepPath(String scope, String representation) {
            boolean team = "TEAM".equals(scope);
            String scopeId = "ID".equals(representation)
                    ? (team ? teamId : orgId).toString()
                    : (team ? teamSlug : orgSlug);
            return "/api/v1/" + (team ? "teams/" : "organizations/") + scopeId + "/schedule-keeps";
        }
    }

    /** memberId の Google Calendar 連携行を有効な状態で作る。 */
    private void connectMember() {
        connectionRepository.upsert(memberId, "w4c-member@gmail.com", "primary", "encrypted-dummy", true);
        em.flush();
        em.clear();
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'W4C', 'テスト', 'W4C テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
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
}

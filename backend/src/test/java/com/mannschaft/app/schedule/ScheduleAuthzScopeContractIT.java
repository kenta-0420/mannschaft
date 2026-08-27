package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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

package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-017b — {@code schedules.min_view_role} を閲覧認可の生きた軸として固定する契約テスト。
 *
 * <p>本テストが固定するのは、設計書 {@code docs/features/F03.1_schedule_shared.md}
 * 「{@code min_view_role} の挙動」節および「{@code min_view_role} の評価スコープ（親子関係）」節が
 * 定める閲覧閾値である。既存実装では {@code ScheduleVisibilityProjection} に当該列が無く、
 * {@code ScheduleVisibilityResolver} が閾値を一切評価できないため、本テストは実装前は red になる。</p>
 *
 * <h2>閾値の対応（設計書より）</h2>
 * <ul>
 *   <li>{@code ANYONE}: 認証済みの全ロール（GUEST 含む）</li>
 *   <li>{@code SUPPORTER_PLUS}: SUPPORTER 以上</li>
 *   <li>{@code MEMBER_PLUS}: MEMBER 以上（既定）</li>
 *   <li>{@code ADMIN_ONLY}: DEPUTY_ADMIN 以上（ADMIN 限定ではない）</li>
 * </ul>
 *
 * <h2>二軸の不変条件（T-2）</h2>
 * <p>{@code include_supporters}（配信）と {@code min_view_role}（閲覧）は独立設定だが、
 * 「応援者に出欠を配るが応援者は見られない」組み合わせは自己矛盾である。よって書込時に
 * {@code includeSupporters=TRUE ⇒ minViewRole ∈ {ANYONE, SUPPORTER_PLUS}} を強制する。</p>
 *
 * <p>拒否は 403（{@code VISIBILITY_001}）で固定する（404 への秘匿化はしない）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-017b min_view_role 閲覧認可 契約テスト")
class ScheduleMinViewRoleContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleRepository scheduleRepository;

    /** 外部 API 呼び出しは本テストの対象外のため遮断する。 */
    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long orgId;
    private String teamSlug;
    private String orgSlug;

    /** チームに直接所属する SUPPORTER。MEMBER_PLUS 予定を見てはならない。 */
    private Long teamSupporterId;
    /** チームに直接所属する MEMBER。 */
    private Long teamMemberId;
    /** チームの DEPUTY_ADMIN。ADMIN_ONLY 予定を見られなければならない。 */
    private Long teamDeputyId;
    /** チームの GUEST（所属はあるが最下位ロール）。 */
    private Long teamGuestId;
    /** 親組織に直接所属する SUPPORTER（チームには所属しない）。 */
    private Long orgSupporterId;
    /** 親組織に直接所属する MEMBER（チームには所属しない）。 */
    private Long orgMemberId;
    /** 親組織の ADMIN。組織予定の書込（create / update）を行える唯一のロール。 */
    private Long orgAdminId;
    /** プラットフォーム SYSTEM_ADMIN。 */
    private Long systemAdminId;

    @BeforeEach
    void setUp() {
        teamSlug = "mvr-team-" + System.nanoTime();
        orgSlug = "mvr-org-" + System.nanoTime();
        teamId = insertTeam("MVR チーム", teamSlug);
        orgId = insertOrganization("MVR 組織", orgSlug);
        linkTeamToOrganization(teamId, orgId);

        teamSupporterId = insertUser("mvr-team-supporter@example.com");
        teamMemberId = insertUser("mvr-team-member@example.com");
        teamDeputyId = insertUser("mvr-team-deputy@example.com");
        teamGuestId = insertUser("mvr-team-guest@example.com");
        orgSupporterId = insertUser("mvr-org-supporter@example.com");
        orgMemberId = insertUser("mvr-org-member@example.com");
        orgAdminId = insertUser("mvr-org-admin@example.com");
        systemAdminId = insertUser("mvr-system-admin@example.com");

        // memberships（所属）と user_roles（権限ロール）は別系統のため双方に行を張る。
        MembershipTestHelper.insertMembership(em, teamSupporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, teamSupporterId, "SUPPORTER", teamId, null);

        MembershipTestHelper.insertMembership(em, teamMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamMemberId, "MEMBER", teamId, null);

        MembershipTestHelper.insertMembership(em, teamDeputyId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamDeputyId, "DEPUTY_ADMIN", teamId, null);

        // GUEST は memberships を持たず user_roles だけで «所属はあるが最下位» を表現する。
        MembershipTestHelper.insertUserRole(em, teamGuestId, "GUEST", teamId, null);

        MembershipTestHelper.insertMembership(em, orgSupporterId, ScopeType.ORGANIZATION, orgId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, orgSupporterId, "SUPPORTER", null, orgId);

        MembershipTestHelper.insertMembership(em, orgMemberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgMemberId, "MEMBER", null, orgId);

        MembershipTestHelper.insertMembership(em, orgAdminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, orgId);

        // SYSTEM_ADMIN はプラットフォームレベル割当（team_id / organization_id ともに null）。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-01〜05, 07: チームスコープ MEMBERS_ONLY の閾値
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チーム予定（visibility=MEMBERS_ONLY）の min_view_role 閾値")
    class TeamMembersOnlyThreshold {

        @Test
        @DisplayName("AC-01 min_view_role=MEMBER_PLUS のチーム予定は同チーム SUPPORTER に 403（VISIBILITY_001）、MEMBER には 200")
        void memberPlus_単体GET_supporterは403_memberは200() throws Exception {
            Long scheduleId = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);

            setAuthentication(teamSupporterId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));

            setAuthentication(teamMemberId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-02 min_view_role=MEMBER_PLUS のチーム予定は SUPPORTER の一覧に現れず、MEMBER の一覧には現れる")
        void memberPlus_一覧_supporterには現れずmemberには現れる() throws Exception {
            Long scheduleId = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);

            setAuthentication(teamSupporterId);
            mockMvc.perform(listTeamSchedules())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + scheduleId + ")]").isEmpty());

            setAuthentication(teamMemberId);
            mockMvc.perform(listTeamSchedules())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + scheduleId + ")]").isNotEmpty());
        }

        @Test
        @DisplayName("AC-03 min_view_role=MEMBER_PLUS のチーム予定は SUPPORTER の横断カレンダーに現れず、MEMBER には現れる")
        void memberPlus_横断カレンダー_supporterには現れずmemberには現れる() throws Exception {
            Long scheduleId = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.MEMBER_PLUS);

            setAuthentication(teamSupporterId);
            mockMvc.perform(myCalendar())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + scheduleId + ")]").isEmpty());

            setAuthentication(teamMemberId);
            mockMvc.perform(myCalendar())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + scheduleId + ")]").isNotEmpty());
        }

        @Test
        @DisplayName("AC-04 min_view_role=SUPPORTER_PLUS のチーム予定は SUPPORTER に 200、GUEST には 403")
        void supporterPlus_supporterは200_guestは403() throws Exception {
            Long scheduleId = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.SUPPORTER_PLUS);

            setAuthentication(teamSupporterId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isOk());

            setAuthentication(teamGuestId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));
        }

        @Test
        @DisplayName("AC-05 min_view_role=ADMIN_ONLY のチーム予定は DEPUTY_ADMIN に 200、MEMBER には 403")
        void adminOnly_deputyAdminは200_memberは403() throws Exception {
            // 設計書 F03.1「ADMIN_ONLY: DEPUTY_ADMIN・ADMIN のみ閲覧可」が正。
            // 既存の GoogleCalendarService#satisfiesMinViewRole は ADMIN_ONLY を "ADMIN" 閾値へ
            // 写像しており DEPUTY_ADMIN を誤って弾く。本テストはその is-bug を固定する。
            Long scheduleId = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.ADMIN_ONLY);

            setAuthentication(teamDeputyId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isOk());

            setAuthentication(teamMemberId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));
        }

        @Test
        @DisplayName("AC-07 SystemAdmin は min_view_role に関わらず 200")
        void systemAdminは閾値に関わらず200() throws Exception {
            Long scheduleId = saveTeamSchedule(ScheduleVisibility.MEMBERS_ONLY, MinViewRole.ADMIN_ONLY);

            setAuthentication(systemAdminId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-06: visibility=ORGANIZATION は親組織への直接所属ロールで閾値評価
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("組織共有チーム予定（visibility=ORGANIZATION）の閾値評価スコープ")
    class OrganizationSharedThreshold {

        @Test
        @DisplayName("AC-06 visibility=ORGANIZATION・min_view_role=MEMBER_PLUS は親組織 SUPPORTER に 403、親組織 MEMBER に 200")
        void organization共有_親組織ロールで閾値評価される() throws Exception {
            // 設計書 F03.1: visibility=ORGANIZATION の閾値は «親組織への直接所属ロール» で評価する。
            Long scheduleId = saveTeamSchedule(ScheduleVisibility.ORGANIZATION, MinViewRole.MEMBER_PLUS);

            setAuthentication(orgSupporterId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));

            setAuthentication(orgMemberId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-06 visibility=ORGANIZATION・min_view_role=SUPPORTER_PLUS なら親組織 SUPPORTER も 200")
        void organization共有_supporterPlusなら親組織supporterも見える() throws Exception {
            Long scheduleId = saveTeamSchedule(ScheduleVisibility.ORGANIZATION, MinViewRole.SUPPORTER_PLUS);

            setAuthentication(orgSupporterId);
            mockMvc.perform(get("/api/v1/teams/{slug}/schedules/{id}", teamSlug, scheduleId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-06d（三b）: 組織スコープ × visibility=ORGANIZATION（下向き再帰・フェーズ M2）の閾値
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("組織予定（visibility=ORGANIZATION・下向き再帰）の閾値評価")
    class OrganizationAndDescendantsThreshold {

        // 組織スコープ × visibility=ORGANIZATION は ORGANIZATION_AND_DESCENDANTS へ昇格し、
        // 配下 ACTIVE チームのみに所属するユーザーへ開かれる（欠陥 Z の根治 / フェーズ M2）。
        // 閾値が掛かっていなかったため、配下チームの SUPPORTER に MEMBER_PLUS 予定が見えていた。

        @Test
        @DisplayName("AC-06d 単体GET: MEMBER_PLUS の組織予定は配下チーム SUPPORTER に 403、配下チーム MEMBER には 200")
        void 下向き再帰_memberPlus_配下supporterは403_配下memberは200() throws Exception {
            Long scheduleId = saveOrgWideSchedule(MinViewRole.MEMBER_PLUS);

            setAuthentication(teamSupporterId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/schedules/{id}", orgSlug, scheduleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));

            // M2（欠陥 Z の根治）が閾値の導入で殺されていないことの証明。
            setAuthentication(teamMemberId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/schedules/{id}", orgSlug, scheduleId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-06d 単体GET: SUPPORTER_PLUS の組織予定なら配下チーム SUPPORTER も 200（塞ぎすぎない）")
        void 下向き再帰_supporterPlusなら配下supporterも200() throws Exception {
            Long scheduleId = saveOrgWideSchedule(MinViewRole.SUPPORTER_PLUS);

            setAuthentication(teamSupporterId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/schedules/{id}", orgSlug, scheduleId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-06d 単体GET: MEMBER_PLUS の組織予定は組織直接所属 MEMBER に 200（直接所属を取りこぼさない）")
        void 下向き再帰_組織直接所属memberは200() throws Exception {
            Long scheduleId = saveOrgWideSchedule(MinViewRole.MEMBER_PLUS);

            setAuthentication(orgMemberId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/schedules/{id}", orgSlug, scheduleId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-06d 一覧: MEMBER_PLUS の組織予定は配下チーム SUPPORTER の一覧に現れず、配下チーム MEMBER には現れる")
        void 下向き再帰_一覧_supporterには現れずmemberには現れる() throws Exception {
            Long scheduleId = saveOrgWideSchedule(MinViewRole.MEMBER_PLUS);

            setAuthentication(teamSupporterId);
            mockMvc.perform(listOrgSchedules())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + scheduleId + ")]").isEmpty());

            setAuthentication(teamMemberId);
            mockMvc.perform(listOrgSchedules())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + scheduleId + ")]").isNotEmpty());
        }

        @Test
        @DisplayName("AC-06d 一覧: SUPPORTER_PLUS の組織予定は配下チーム SUPPORTER の一覧に現れる（塞ぎすぎない）")
        void 下向き再帰_一覧_supporterPlusなら現れる() throws Exception {
            Long scheduleId = saveOrgWideSchedule(MinViewRole.SUPPORTER_PLUS);

            setAuthentication(teamSupporterId);
            mockMvc.perform(listOrgSchedules())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.id == " + scheduleId + ")]").isNotEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-08: 個人予定（ADMIN_ONLY 固定）の PRIVATE 経路が閾値で潰されないこと
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-08 個人予定（min_view_role=ADMIN_ONLY 固定）は所有者本人が 200 で取得できる")
    void 個人予定_所有者本人は閾値に潰されず200() throws Exception {
        Long scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .userId(teamMemberId)
                .title("MVR 個人予定")
                .startAt(LocalDateTime.of(2026, 4, 3, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 3, 12, 0))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(teamMemberId)
                .build()).getId();
        em.flush();
        em.clear();

        setAuthentication(teamMemberId);
        mockMvc.perform(get("/api/v1/me/schedules/{id}", scheduleId))
                .andExpect(status().isOk());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-09 / AC-23b: 配信母集団の OR 迂回路と閾値の関係
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("配信母集団（include_supporters）と閲覧閾値の関係")
    class DistributionAudienceInteraction {

        @Test
        @DisplayName("AC-09 include_supporters=TRUE × min_view_role=MEMBER_PLUS の矛盾行でも配信母集団の OR は閾値を迂回しない")
        void OR迂回路は閾値を迂回しない() throws Exception {
            // 不変条件（AC-22）導入後は書込経路で作れなくなる組み合わせだが、
            // 過去データ・直接 INSERT で存在しうる。閲覧側が閾値を守ることを固定する。
            Long scheduleId = saveOrgSchedule(MinViewRole.MEMBER_PLUS, true);

            setAuthentication(orgSupporterId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/schedules/{id}", orgSlug, scheduleId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));
        }

        @Test
        @DisplayName("AC-23b include_supporters=TRUE × min_view_role=SUPPORTER_PLUS の組織予定は配下 SUPPORTER が 200 で取得できる")
        void 出欠を配られた応援者は予定を見られる() throws Exception {
            // OR 迂回路が削除された後も «出欠を求めた相手には予定を見せる» が
            // 閾値そのもの（SUPPORTER_PLUS）で成立しなければならない。
            Long scheduleId = saveOrgSchedule(MinViewRole.SUPPORTER_PLUS, true);

            setAuthentication(orgSupporterId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/schedules/{id}", orgSlug, scheduleId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-22 / AC-23: 二軸の不変条件（書込時に矛盾を禁ずる）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("二軸の不変条件（includeSupporters=TRUE ⇒ minViewRole ∈ {ANYONE, SUPPORTER_PLUS}）")
    class TwoAxisInvariant {

        @Test
        @DisplayName("AC-22 create: includeSupporters=TRUE × minViewRole=MEMBER_PLUS は 400 で拒否される")
        void create_矛盾する組み合わせは400() throws Exception {
            setAuthentication(orgAdminId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/schedules", orgSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createRequest("MVR 矛盾予定", "MEMBER_PLUS", true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("AC-22 create: includeSupporters=TRUE × minViewRole=ADMIN_ONLY は 400 で拒否される")
        void create_矛盾する組み合わせadminOnlyも400() throws Exception {
            setAuthentication(orgAdminId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/schedules", orgSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createRequest("MVR 矛盾予定2", "ADMIN_ONLY", true))))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("AC-22 update: include_supporters=TRUE の予定を minViewRole=MEMBER_PLUS へ更新すると 400 で拒否される")
        void update_矛盾する組み合わせへの変更は400() throws Exception {
            // UpdateScheduleRequest は includeSupporters を持たないため、
            // 更新経路で不変条件を破りうるのは «既存 TRUE の行の minViewRole を上げる» 側だけである。
            Long scheduleId = saveOrgSchedule(MinViewRole.SUPPORTER_PLUS, true);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("minViewRole", "MEMBER_PLUS");

            setAuthentication(orgAdminId);
            mockMvc.perform(patch("/api/v1/organizations/{slug}/schedules/{id}", orgSlug, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("AC-23 minViewRole 未指定 × includeSupporters=TRUE の create は SUPPORTER_PLUS で保存される")
        void create_未指定かつincludeSupporters真はSUPPORTER_PLUS() throws Exception {
            setAuthentication(orgAdminId);
            String response = mockMvc.perform(post("/api/v1/organizations/{slug}/schedules", orgSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createRequest("MVR 既定SUPPORTER", null, true))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            em.flush();
            em.clear();
            assertThat(savedMinViewRole(response))
                    .as("includeSupporters=TRUE で minViewRole 未指定なら SUPPORTER_PLUS が既定でなければ"
                            + "「応援者に出欠を配るが応援者は見られない」自己矛盾が生じる")
                    .isEqualTo(MinViewRole.SUPPORTER_PLUS);
        }

        @Test
        @DisplayName("AC-23 minViewRole 未指定 × includeSupporters=FALSE の create は MEMBER_PLUS で保存される")
        void create_未指定かつincludeSupporters偽はMEMBER_PLUS() throws Exception {
            setAuthentication(orgAdminId);
            String response = mockMvc.perform(post("/api/v1/organizations/{slug}/schedules", orgSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createRequest("MVR 既定MEMBER", null, false))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            em.flush();
            em.clear();
            assertThat(savedMinViewRole(response)).isEqualTo(MinViewRole.MEMBER_PLUS);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private org.springframework.test.web.servlet.RequestBuilder listTeamSchedules() {
        return get("/api/v1/teams/{slug}/schedules", teamSlug)
                .param("from", "2026-04-01T00:00:00")
                .param("to", "2026-04-30T00:00:00");
    }

    private org.springframework.test.web.servlet.RequestBuilder listOrgSchedules() {
        return get("/api/v1/organizations/{slug}/schedules", orgSlug)
                .param("from", "2026-04-01T00:00:00")
                .param("to", "2026-04-30T00:00:00");
    }

    private org.springframework.test.web.servlet.RequestBuilder myCalendar() {
        return get("/api/v1/my/calendar")
                .param("from", "2026-04-01T00:00:00")
                .param("to", "2026-04-30T00:00:00");
    }

    /** 作成レスポンスの id から保存済み minViewRole を読み直す。 */
    private MinViewRole savedMinViewRole(String createResponseJson) throws Exception {
        Long id = objectMapper.readTree(createResponseJson).path("data").path("id").asLong();
        return scheduleRepository.findById(id).orElseThrow().getMinViewRole();
    }

    private Map<String, Object> createRequest(String title, String minViewRole, boolean includeSupporters) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("startAt", "2026-04-10T10:00:00+09:00");
        body.put("endAt", "2026-04-10T12:00:00+09:00");
        body.put("allDay", false);
        body.put("eventType", EventType.OTHER.name());
        body.put("attendanceRequired", true);
        body.put("includeSupporters", includeSupporters);
        if (minViewRole != null) {
            body.put("minViewRole", minViewRole);
        }
        return body;
    }

    private Long saveTeamSchedule(ScheduleVisibility visibility, MinViewRole minViewRole) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("MVR チーム予定 " + minViewRole)
                .startAt(LocalDateTime.of(2026, 4, 10, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 10, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(visibility)
                .minViewRole(minViewRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(teamDeputyId)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    /**
     * 組織スコープ × {@code visibility = ORGANIZATION} の予定を保存する。
     * Resolver 側で {@code ORGANIZATION_AND_DESCENDANTS}（下向き再帰）へ昇格する経路。
     */
    private Long saveOrgWideSchedule(MinViewRole minViewRole) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .organizationId(orgId)
                .title("MVR 組織全体予定 " + minViewRole)
                .startAt(LocalDateTime.of(2026, 4, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 12, 12, 0))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.ORGANIZATION)
                .minViewRole(minViewRole)
                .includeSupporters(false)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(orgAdminId)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    private Long saveOrgSchedule(MinViewRole minViewRole, boolean includeSupporters) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .organizationId(orgId)
                .title("MVR 組織予定 " + minViewRole)
                .startAt(LocalDateTime.of(2026, 4, 11, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 11, 12, 0))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(minViewRole)
                .includeSupporters(includeSupporters)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(orgMemberId)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
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
                                + "VALUES (:email, 'MVR', 'テスト', 'MVR テスト', 'ACTIVE', "
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

    /** ORGANIZATION_WIDE の親 ORG 解決が成立するよう ACTIVE な team_org_memberships を張る。 */
    private void linkTeamToOrganization(Long linkedTeamId, Long linkedOrgId) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships ("
                                + "team_id, organization_id, status, invited_at, created_at) "
                                + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", linkedTeamId)
                .setParameter("oid", linkedOrgId)
                .executeUpdate();
    }
}

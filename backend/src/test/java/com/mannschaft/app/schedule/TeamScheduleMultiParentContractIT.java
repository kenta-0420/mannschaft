package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.service.ScheduleService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2.1 §9.2 #8〜#10（部隊 3-B）— チームの行事カテゴリ（年間行事ビュー・カテゴリ一覧）が
 * 全親組織の分をマージすること、および予約タスクのテナントキーが代表親組織で決定的なことの契約テスト
 * （試練・先行 red）。
 *
 * <ul>
 *   <li>AC-N04: 年間行事ビューとカテゴリ一覧に X・Y 両方の組織カテゴリが、由来
 *       （{@code sourceOrganizationId}）付きで出る。チーム自身のカテゴリの由来は null。</li>
 *   <li>AC-G131: 親組織が 0 件のチームは自チームのカテゴリだけ（他チームのカテゴリが
 *       {@code organization_id IS NULL} 検索で混ざらない）。</li>
 *   <li>AC-N05（代表親組織）: 予約タスクの organization_id は §9.3 の代表親組織（最初に成立した加盟）。</li>
 * </ul>
 *
 * <p>実 Security（@WithMockUser）・実 MySQL。Repository・Service・Controller はモックしない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 3-B チーム行事カテゴリの全親組織マージ・予約タスクの代表親組織の契約")
class TeamScheduleMultiParentContractIT extends AbstractMySqlIntegrationTest {

    private static final long MEMBER_ID = 3811L;
    private static final long ADMIN_ID = 3812L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long orgX;
    private Long orgY;
    private Long teamT;
    private Long teamOrphan;
    private Long otherTeam;
    private Long catX;
    private Long catY;
    private Long catTeamT;
    private Long catOrphan;
    private Long catOther;

    @BeforeEach
    void setUp() {
        String sfx = String.valueOf(System.nanoTime());
        orgX = TeamOrgFixtureHelper.insertOrganization(em, "3B行事組織X " + sfx, "tsm-x-" + sfx);
        orgY = TeamOrgFixtureHelper.insertOrganization(em, "3B行事組織Y " + sfx, "tsm-y-" + sfx);
        teamT = TeamOrgFixtureHelper.insertTeam(em, "3B行事チームT " + sfx, "tsm-t-" + sfx);
        teamOrphan = TeamOrgFixtureHelper.insertTeam(em, "3B行事無所属 " + sfx, "tsm-o-" + sfx);
        otherTeam = TeamOrgFixtureHelper.insertTeam(em, "3B行事他チーム " + sfx, "tsm-h-" + sfx);

        LocalDateTime base = LocalDateTime.of(2026, 4, 1, 9, 0);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamT, orgX, "ACTIVE", base);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamT, orgY, "ACTIVE", base.plusDays(1));

        catX = TeamOrgFixtureHelper.insertOrgEventCategory(em, orgX, "X由来の式典", 1);
        catY = TeamOrgFixtureHelper.insertOrgEventCategory(em, orgY, "Y由来の研修", 1);
        catTeamT = TeamOrgFixtureHelper.insertTeamEventCategory(em, teamT, "T固有の遠征", 1);
        catOrphan = TeamOrgFixtureHelper.insertTeamEventCategory(em, teamOrphan, "無所属固有の合宿", 1);
        // 親組織0件のチームの照会に「organization_id IS NULL」で混ざり得る、他チームのカテゴリ。
        catOther = TeamOrgFixtureHelper.insertTeamEventCategory(em, otherTeam, "他チーム固有の行事", 1);

        MembershipTestHelper.insertActiveUser(em, MEMBER_ID);
        MembershipTestHelper.insertMembership(em, MEMBER_ID, ScopeType.TEAM, teamT, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, MEMBER_ID, ScopeType.TEAM, teamOrphan, RoleKind.MEMBER);
        MembershipTestHelper.insertActiveUser(em, ADMIN_ID);
        MembershipTestHelper.insertUserRole(em, ADMIN_ID, "ADMIN", teamT, null);
        em.flush();
        em.clear();
    }

    // =========================================================================
    // AC-N04: カテゴリ一覧
    // =========================================================================

    @Test
    @WithMockUser(username = "3811")
    @DisplayName("AC-N04 チーム行事カテゴリ一覧に X・Y 両方の組織カテゴリと自チームのカテゴリが出る（片方だけにならない）")
    void カテゴリ一覧は全親組織分をマージする() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + teamT + "/event-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id",
                        contains(catX.intValue(), catY.intValue(), catTeamT.intValue())))
                .andExpect(jsonPath("$.data[?(@.id == " + catX + ")].scope", contains("ORGANIZATION")))
                .andExpect(jsonPath("$.data[?(@.id == " + catY + ")].scope", contains("ORGANIZATION")));
    }

    @Test
    @WithMockUser(username = "3811")
    @DisplayName("AC-N04 一覧の各カテゴリに由来（sourceOrganizationId）が付く。チーム自身のカテゴリは null")
    void カテゴリ一覧に由来の印が付く() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + teamT + "/event-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == " + catX + ")].sourceOrganizationId",
                        contains(orgX.intValue())))
                .andExpect(jsonPath("$.data[?(@.id == " + catY + ")].sourceOrganizationId",
                        contains(orgY.intValue())))
                // チーム固有: キーが null で在る（または省略される）。いずれも「組織由来ではない」を表す。
                .andExpect(jsonPath("$.data[?(@.id == " + catTeamT + ")].sourceOrganizationId",
                        not(hasItem(notNullValue()))));
    }

    @Test
    @WithMockUser(username = "3811")
    @DisplayName("AC-N04 マージ順は決定的（代表親組織の分が先 → 次の親組織 → 自チーム）で、10回呼んでも変わらない")
    void カテゴリ一覧の順序は決定的() throws Exception {
        for (int i = 0; i < 10; i++) {
            em.clear();
            mockMvc.perform(get("/api/v1/teams/" + teamT + "/event-categories"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id",
                            contains(catX.intValue(), catY.intValue(), catTeamT.intValue())));
        }
    }

    @Test
    @WithMockUser(username = "3811")
    @DisplayName("AC-G131 親組織0件のチームのカテゴリ一覧は自チームの分だけ（他チームのカテゴリが混ざらない）")
    void 親組織0件のカテゴリ一覧は自チームだけ() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + teamOrphan + "/event-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", contains(catOrphan.intValue())))
                .andExpect(jsonPath("$.data[*].id", not(hasItem(catOther.intValue()))));
    }

    // =========================================================================
    // AC-N04: 年間行事ビュー
    // =========================================================================

    @Test
    @WithMockUser(username = "3811")
    @DisplayName("AC-N04 年間行事ビューの categories に X・Y 両方の組織カテゴリが由来付きで出る")
    void 年間行事ビューは全親組織分をマージする() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + teamT + "/schedules/annual").param("academic_year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[*].id",
                        contains(catX.intValue(), catY.intValue(), catTeamT.intValue())))
                .andExpect(jsonPath("$.data.categories[?(@.id == " + catX + ")].sourceOrganizationId",
                        contains(orgX.intValue())))
                .andExpect(jsonPath("$.data.categories[?(@.id == " + catY + ")].sourceOrganizationId",
                        contains(orgY.intValue())));
    }

    @Test
    @WithMockUser(username = "3811")
    @DisplayName("AC-G131 親組織0件のチームの年間行事ビューは自チームのカテゴリだけ")
    void 親組織0件の年間行事ビューは自チームだけ() throws Exception {
        mockMvc.perform(get("/api/v1/teams/" + teamOrphan + "/schedules/annual").param("academic_year", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories[*].id", contains(catOrphan.intValue())))
                .andExpect(jsonPath("$.data.categories[*].id", not(hasItem(catOther.intValue()))))
                .andExpect(jsonPath("$.data.categories[*].sourceOrganizationId", everyItem(nullValue())));
    }

    // =========================================================================
    // AC-N05(代表親組織): 予約タスクのテナントキー（ScheduleService #8）
    // =========================================================================

    @Test
    @DisplayName("AC-N05(代表) 予約タスクの organization_id は代表親組織（最初に成立した加盟=X）で、作成のたびに変わらない")
    void 予約タスクのテナントキーは代表親組織() throws Exception {
        for (int i = 0; i < 5; i++) {
            em.clear();
            Long scheduleId = scheduleService
                    .createSchedule(scheduledAttendanceRequest("予約出欠 " + i), teamT, "TEAM", ADMIN_ID)
                    .getId();
            em.flush();
            em.clear();

            @SuppressWarnings("unchecked")
            List<Number> orgIds = em.createNativeQuery(
                            "SELECT organization_id FROM schedule_scheduled_tasks WHERE schedule_id = :sid")
                    .setParameter("sid", scheduleId)
                    .getResultList();
            assertThat(orgIds).hasSize(1);
            assertThat(orgIds.get(0).longValue()).isEqualTo(orgX);
        }
    }

    private CreateScheduleRequest scheduledAttendanceRequest(String title) throws Exception {
        String scheduledAt = OffsetDateTime.now().plusDays(30).toString();
        String startAt = OffsetDateTime.now().plusDays(60).toString();
        String json = "{"
                + "\"title\":\"" + title + "\","
                + "\"startAt\":\"" + startAt + "\","
                + "\"allDay\":false,"
                + "\"eventType\":\"PRACTICE\","
                + "\"attendanceRequired\":false,"
                + "\"scheduledAttendance\":{\"scheduledAt\":\"" + scheduledAt + "\"}"
                + "}";
        return objectMapper.readValue(json, CreateScheduleRequest.class);
    }
}

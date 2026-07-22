package com.mannschaft.app.shift;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6 — shift ドメイン（{@code ShiftChangeRequestController} 参照系）認可契約テスト（試練）。
 *
 * <p>封鎖する 2 つの実穴:</p>
 * <ol>
 *   <li><b>権限昇格</b>: 一覧 API が {@code @RequestParam String role} を認可の判断材料に
 *       していたため、<b>一般メンバーが自己申告するだけで</b>スケジュール全件を取得できた。
 *       本改修で {@code role} を撤廃し、サーバー側のロール判定に置き換えた。
 *       撤廃後もクエリに残骸が付いて送られる可能性があるため、
 *       「{@code ?role=ADMIN} を付けても昇格しない」ことを明示的に検証する。</li>
 *   <li><b>死文だった IDOR チェック</b>: 詳細 API は Javadoc に「IDOR チェック付き」と
 *       書かれながら本体に照合コードが無く、任意 ID の依頼を閲覧できた。</li>
 * </ol>
 *
 * <p>金型: {@code ShiftScheduleScopeContractIT}（Wave3-B6・同ドメイン）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（変更依頼・参照系）認可契約テスト（試練）")
class ShiftChangeRequestScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftChangeRequestRepository changeRequestRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;    // TEAM A の ADMIN（全件見える）
    private Long adminTeamBId;    // TEAM B の ADMIN（越境攻撃者）
    private Long memberTeamAId;   // TEAM A の一般メンバー（自分の分のみ）
    private Long otherMemberId;   // TEAM A の別メンバー（他人の依頼の持ち主）
    private Long outsiderId;      // 非メンバー

    private Long scheduleAId;
    private Long myRequestId;     // memberTeamA の依頼
    private Long othersRequestId; // otherMember の依頼

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE6 変更依頼 チームA");
        teamBId = insertTeam("WAVE6 変更依頼 チームB");

        adminTeamAId = insertUser("wave6-cr-admin-team-a@example.com");
        adminTeamBId = insertUser("wave6-cr-admin-team-b@example.com");
        memberTeamAId = insertUser("wave6-cr-member-team-a@example.com");
        otherMemberId = insertUser("wave6-cr-other-member@example.com");
        outsiderId = insertUser("wave6-cr-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        ShiftScheduleEntity scheduleA = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("WAVE6 変更依頼テスト用スケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.DRAFT)
                .createdBy(adminTeamAId)
                .build());
        scheduleAId = scheduleA.getId();

        myRequestId = changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(scheduleAId)
                .requestType(ChangeRequestType.OPEN_CALL)
                .requestedBy(memberTeamAId)
                .reason("自分の依頼")
                .build()).getId();

        othersRequestId = changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(scheduleAId)
                .requestType(ChangeRequestType.OPEN_CALL)
                .requestedBy(otherMemberId)
                .reason("他人の依頼")
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /shifts/change-requests?scheduleId=（一覧・★権限昇格の本丸★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /shifts/change-requests?scheduleId=（一覧）")
    class ListChangeRequests {

        @Test
        @DisplayName("一般メンバーは自分の依頼のみ（他人の依頼は返らない）")
        void 一般メンバーは自分の依頼のみ() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(myRequestId));
        }

        /**
         * 本戦役の本丸。旧実装では {@code ?role=ADMIN} を付けるだけで全件が返っていた。
         * {@code role} 撤廃後は未知のクエリパラメータとして無視され、昇格しないこと。
         */
        @Test
        @DisplayName("一般メンバーが role=ADMIN を付けても全件は返らない（権限昇格の封鎖）")
        void 一般メンバーがroleADMINを付けても昇格しない() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString())
                            .param("role", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(myRequestId));
        }

        @Test
        @DisplayName("正当ADMINは全件（2件）")
        void 正当ADMINは全件() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN が role=ADMIN を付けても403（BOLA・権限昇格の複合）")
        void 別scopeADMINがroleADMINを付けても403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/change-requests")
                            .param("scheduleId", scheduleAId.toString())
                            .param("role", "ADMIN"))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /shifts/change-requests/{id}（詳細・★死文だったIDORチェック★）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /shifts/change-requests/{id}（詳細）")
    class GetChangeRequest {

        @Test
        @DisplayName("依頼者本人は200")
        void 依頼者本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(myRequestId));
        }

        @Test
        @DisplayName("当該チームADMINは他人の依頼も200")
        void 当該チームADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", othersRequestId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("同じチームの他メンバーは404（存在秘匿）")
        void 同チームの他メンバーは404() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", othersRequestId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別scope ADMINは404（越境・存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーは404（越境・存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/shifts/change-requests/{id}", myRequestId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
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
                                + "VALUES (:email, 'WAVE6', 'テスト', 'WAVE6 テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}

package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave6 追加戦 — shift ドメイン 時給API（{@code ShiftAvailabilityController} の
 * {@code GET/POST /api/v1/shifts/hourly-rate}）の認可契約テスト（試練 / red 先行）。
 *
 * <p>敷設後の仕様（F03.5 設計書 {@code 01_db_design.md} §shift_hourly_rates
 * 「時給の閲覧権限: 本人 + ADMIN / DEPUTY_ADMIN のみ。他メンバーの時給は非公開」に準拠）:</p>
 * <ul>
 *   <li>対象が<b>本人</b>の場合 — 当該チームのメンバーであることを要求</li>
 *   <li>対象が<b>他メンバー</b>の場合 — 呼び出し元が当該チームの ADMIN/DEPUTY_ADMIN であること、
 *       かつ<b>対象ユーザーも当該チームのメンバー</b>であることを要求（BOLA 封鎖）</li>
 *   <li>SYSTEM_ADMIN は短絡的に許可</li>
 *   <li>違反時のステータスは <b>403</b>（shift ドメインの既存規約
 *       {@code ShiftScheduleScopeContractIT} / {@code ShiftSlotScopeContractIT} に揃える）</li>
 * </ul>
 *
 * <p>金型: {@code ShiftScheduleScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>象限</b>: 別scope ADMIN（BOLA）/ 同一チームの非ADMINメンバー / 本人 / 正当ADMIN /
 * 正当ADMINが非メンバーを対象指定（対象側 BOLA）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（時給）認可契約テスト（試練）")
class ShiftHourlyRateScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/shifts/hourly-rate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftHourlyRateRepository hourlyRateRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー（時給の持ち主）
    private Long memberTeamBId;  // TEAM B の非 ADMIN メンバー（TEAM A には非所属）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("WAVE6 時給 チームA");
        teamBId = insertTeam("WAVE6 時給 チームB");

        adminTeamAId = insertUser("wave6-rate-admin-team-a@example.com");
        adminTeamBId = insertUser("wave6-rate-admin-team-b@example.com");
        memberTeamAId = insertUser("wave6-rate-member-team-a@example.com");
        memberTeamBId = insertUser("wave6-rate-member-team-b@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（ShiftScheduleScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        hourlyRateRepository.save(ShiftHourlyRateEntity.builder()
                .userId(memberTeamAId)
                .teamId(teamAId)
                .hourlyRate(new BigDecimal("1200.00"))
                .effectiveFrom(LocalDate.of(2026, 3, 1))
                .build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /shifts/hourly-rate?teamId=&userId=（時給履歴取得）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /shifts/hourly-rate（時給履歴取得）")
    class ListHourlyRates {

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）がteamAメンバーの時給を参照すると403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get(BASE)
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("同一チームの非ADMINメンバーが他メンバーの時給を参照すると403")
        void 同一チーム非ADMINが他人の時給を参照すると403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get(BASE)
                            .param("teamId", teamAId.toString())
                            .param("userId", adminTeamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("date指定でも別scope ADMINは403（BOLA）")
        void date指定でも別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(get(BASE)
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系: 本人が自分の時給を参照すると200")
        void 本人は200() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(get(BASE)
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正常系: 正当ADMINが同一チームメンバーの時給を参照すると200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get(BASE)
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正常系: 正当ADMINがdate指定で同一チームメンバーの時給を参照すると200")
        void 正当ADMINはdate指定でも200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get(BASE)
                            .param("teamId", teamAId.toString())
                            .param("userId", memberTeamAId.toString())
                            .param("date", LocalDate.of(2026, 4, 1).toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /shifts/hourly-rate?teamId=（時給設定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /shifts/hourly-rate（時給設定）")
    class CreateHourlyRate {

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）がteamAへ時給を書き込むと403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("同一チームの非ADMINメンバーが他メンバーの時給を書き込むと403")
        void 同一チーム非ADMINが他人の時給を書き込むと403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(adminTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINでも対象が当該チーム非所属なら403（対象側BOLA）")
        void 対象が非メンバーなら403() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamBId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系: 本人が自分の時給を登録すると201")
        void 本人は201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamAId))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("正常系: 正当ADMINが同一チームメンバーの時給を登録すると201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post(BASE).param("teamId", teamAId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody(memberTeamAId))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody(Long targetUserId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", targetUserId);
            body.put("hourlyRate", new BigDecimal("1500.00"));
            body.put("effectiveFrom", LocalDate.of(2026, 5, 1).toString());
            return body;
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

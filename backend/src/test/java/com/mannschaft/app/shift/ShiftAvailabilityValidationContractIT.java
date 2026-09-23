package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/v1/shifts/availability} の入力検証契約テスト（試練 / red 先行）。
 *
 * <p>CMP-260912-1758: 実機で確認済みの欠陥。
 * <ul>
 *   <li>{@code dayOfWeek: 99} → 200 で保存される（範囲チェック無し）</li>
 *   <li>{@code preference: "BOGUS"} → 500 COMMON_999（{@code ShiftPreference.valueOf()} が素通し）</li>
 *   <li>{@code endTime < startTime} → 200 で保存される</li>
 * </ul>
 * いずれも 400 で拒否すべきであり、{@code preference} 不正は 500 にしてはならない。</p>
 *
 * <p><b>15分グリッドに揃えない理由（設計判断・決定済み）:</b> 枠側の
 * {@code ShiftSlotTimeValidator}（戦役A-1）は15分刻みを要求するが、曜日既定はこれに揃えない。
 * 既存データが全件 {@code 00:00}-{@code 23:59} であり、{@code 23:59} は15分グリッドに乗らないため、
 * 揃えると既存データが全件不正になる。曜日既定側は前後関係のみ検証する。</p>
 *
 * <p>金型: {@code ShiftAvailabilityScopeContractIT}（同一 EP の既存 IT）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("shift ドメイン（勤務可能時間デフォルト）入力検証契約テスト（試練 CMP-260912-1758）")
class ShiftAvailabilityValidationContractIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/shifts/availability";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("試練 勤務可能時間検証 チームA");
        memberId = insertUser("shiren-avail-validation-member@example.com");
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        em.flush();
        em.clear();
        setAuth(memberId);
    }

    @Test
    @DisplayName("AC-1: endTime < startTime は400")
    void endTimeがstartTimeより前は400() throws Exception {
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bulkBodyOf(availability(1,
                                LocalTime.of(23, 59), LocalTime.of(0, 0), "AVAILABLE")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-1b: startTime == endTime は400")
    void startTimeとendTimeが同一は400() throws Exception {
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bulkBodyOf(availability(1,
                                LocalTime.of(9, 0), LocalTime.of(9, 0), "AVAILABLE")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-2: dayOfWeekが99は400")
    void dayOfWeekが99は400() throws Exception {
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bulkBodyOf(availability(99,
                                LocalTime.of(9, 0), LocalTime.of(17, 0), "AVAILABLE")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-2b: dayOfWeekが-1は400")
    void dayOfWeekがマイナスは400() throws Exception {
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bulkBodyOf(availability(-1,
                                LocalTime.of(9, 0), LocalTime.of(17, 0), "AVAILABLE")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-3: 同一dayOfWeekの重複行は400")
    void 同一dayOfWeekの重複行は400() throws Exception {
        Map<String, Object> body = Map.of("availabilities", List.of(
                availability(1, LocalTime.of(9, 0), LocalTime.of(12, 0), "AVAILABLE"),
                availability(1, LocalTime.of(13, 0), LocalTime.of(17, 0), "AVAILABLE")));
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-4: preferenceが不正値は400（500 COMMON_999にしてはならない）")
    void preferenceが不正値は400() throws Exception {
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bulkBodyOf(availability(1,
                                LocalTime.of(9, 0), LocalTime.of(17, 0), "BOGUS")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-5: 既存データ互換（00:00-23:59・全曜日）は200で保存できる（15分グリッドを要求しない）")
    void 全曜日00時から23時59分は200() throws Exception {
        List<Map<String, Object>> avails = List.of(
                availability(0, LocalTime.of(0, 0), LocalTime.of(23, 59), "AVAILABLE"),
                availability(6, LocalTime.of(0, 0), LocalTime.of(23, 59), "AVAILABLE"));
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("availabilities", avails))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-6: 同一内容のPUTを2回連続しても2回とも200（冪等性・CMP-260923）")
    void 同一内容のPUTを2回連続しても200() throws Exception {
        Map<String, Object> body = Map.of("availabilities", List.of(
                availability(5, LocalTime.of(9, 0), LocalTime.of(17, 0), "PREFERRED")));
        String json = objectMapper.writeValueAsString(body);

        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());

        em.clear();
        Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM member_availability_defaults WHERE user_id = :userId AND team_id = :teamId")
                .setParameter("userId", memberId)
                .setParameter("teamId", teamId)
                .getSingleResult();
        org.assertj.core.api.Assertions.assertThat(count.longValue()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-7: 同一内容のPUTを3回以上連続しても常に200（冪等性）")
    void 同一内容のPUTを3回以上連続しても200() throws Exception {
        Map<String, Object> body = Map.of("availabilities", List.of(
                availability(3, LocalTime.of(10, 0), LocalTime.of(15, 0), "AVAILABLE")));
        String json = objectMapper.writeValueAsString(body);

        for (int i = 0; i < 4; i++) {
            mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("AC-8: 内容を変えてPUTすると正しく置き換わる（退行なし）")
    void 内容を変えてPUTすると置き換わる() throws Exception {
        Map<String, Object> first = Map.of("availabilities", List.of(
                availability(2, LocalTime.of(9, 0), LocalTime.of(12, 0), "AVAILABLE")));
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isOk());

        Map<String, Object> second = Map.of("availabilities", List.of(
                availability(2, LocalTime.of(13, 0), LocalTime.of(18, 0), "PREFERRED")));
        mockMvc.perform(put(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(status().isOk());

        em.clear();
        List<?> rows = em.createNativeQuery(
                        "SELECT start_time, end_time FROM member_availability_defaults "
                                + "WHERE user_id = :userId AND team_id = :teamId AND day_of_week = 2")
                .setParameter("userId", memberId)
                .setParameter("teamId", teamId)
                .getResultList();
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
    }

    private Map<String, Object> bulkBodyOf(Map<String, Object> availability) {
        return Map.of("availabilities", List.of(availability));
    }

    private Map<String, Object> availability(int dayOfWeek, LocalTime start, LocalTime end, String preference) {
        return Map.of(
                "dayOfWeek", dayOfWeek,
                "startTime", start.toString(),
                "endTime", end.toString(),
                "preference", preference,
                "note", "試練フィクスチャ");
    }

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
                                + "VALUES (:email, 'シレン', 'テスト', 'シレン テスト', 'ACTIVE', "
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

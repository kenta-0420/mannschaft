package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * シフト枠の時刻バリデーション（設計 F03.5 §11.2.5）の<b>実 DB 契約テスト</b>。
 *
 * <p>単体・mock 版（{@code ShiftSlotTimeValidatorTest} /
 * {@code ShiftSlotTimeValidationServiceTest}）は「save が呼ばれない」ことで永続化到達を測るが、
 * 本 IT は<b>実際にテーブルの行数が増えないこと</b>を測る（mock の嘘を排すため）。
 * Docker 不在時は skip されるが CI では実行される。</p>
 *
 * <p>金型: {@code ShiftSlotScopeContractIT}（同ドメイン・同エンドポイント）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("シフト枠 時刻バリデーション 実DB契約テスト")
class ShiftSlotTimeValidationContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long adminId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("SLOT-TIME シフト枠 チーム");
        adminId = insertUser("slot-time-admin@example.com");
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);

        ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("枠時刻バリデーション用スケジュール")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(java.time.LocalDateTime.of(2026, 2, 20, 10, 0))
                .createdBy(adminId)
                .build());
        scheduleId = schedule.getId();

        em.flush();
        em.clear();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminId.toString(), null, List.of()));
    }

    @Test
    @DisplayName("不正な時刻の枠作成は400で拒否され_実テーブルの行数が増えない")
    void 不正な時刻の枠作成は行数を増やさない() throws Exception {
        long before = slotRepository.count();

        // 終了が開始より前（日跨ぎ指定なし）
        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots", scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                slotBody("17:00:00", "09:00:00", null))))
                .andExpect(status().isBadRequest());

        // 15 分刻みに乗っていない
        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots", scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                slotBody("09:07:00", "17:00:00", null))))
                .andExpect(status().isBadRequest());

        em.flush();
        em.clear();
        assertThat(slotRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("正当な時刻_日跨ぎ明示_の枠作成は成功し_行が1件増える")
    void 正当な日跨ぎ枠は作成できる() throws Exception {
        long before = slotRepository.count();

        mockMvc.perform(post("/api/v1/shifts/schedules/{id}/slots", scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                slotBody("22:00:00", "02:00:00", Boolean.TRUE))))
                .andExpect(status().isCreated());

        em.flush();
        em.clear();
        assertThat(slotRepository.count()).isEqualTo(before + 1);
        ShiftSlotEntity created = slotRepository.findAll().stream()
                .filter(s -> LocalTime.of(22, 0).equals(s.getStartTime()))
                .findFirst()
                .orElseThrow();
        assertThat(created.isEndsNextDay()).isTrue();
    }

    private Map<String, Object> slotBody(String startTime, String endTime, Boolean endsNextDay) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotDate", LocalDate.of(2026, 3, 3).toString());
        body.put("startTime", startTime);
        body.put("endTime", endTime);
        body.put("requiredCount", 1);
        if (endsNextDay != null) {
            body.put("endsNextDay", endsNextDay);
        }
        return body;
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
                                + "VALUES (:email, 'SLOTTIME', 'テスト', 'SLOTTIME テスト', 'ACTIVE', "
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

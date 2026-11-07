package com.mannschaft.app.shift;

import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2151: シフト機能フラグ（FEATURE_SHIFT_ENABLED）OFF のとき、
 * {@code ShiftScheduleController} の全9経路が FEATURE_GATE_001 で拒否されることを固定する。
 *
 * <p>ステータスコードだけでは認可拒否と区別できないため、エラーコードまで断言する。
 * 「無効」は行が無いことの fail-close に頼らず、{@code is_enabled=false} の行を明示的に置く。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("シフト表コントローラの機能フラグ停止契約テスト")
class ShiftScheduleFeatureGateContractIT extends AbstractMySqlIntegrationTest {

    private static final String FLAG = "FEATURE_SHIFT_ENABLED";
    private static final String GATE_CODE = "FEATURE_GATE_001";
    /** バリデーション（@Valid）はゲートより先に走るため、ゲートまで到達できる正常な本文を使う。 */
    private static final String CREATE_BODY =
            "{\"title\":\"t\",\"periodType\":\"WEEKLY\",\"startDate\":\"2026-04-01\",\"endDate\":\"2026-04-07\"}";
    private static final String BASE = "/api/v1/shifts/schedules";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ShiftScheduleRepository scheduleRepository;
    @Autowired
    private FeatureFlagRepository featureFlagRepository;
    @Autowired
    private CacheManager cacheManager;
    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long adminId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("CMP2151 シフト停止 チーム");
        adminId = insertUser("cmp2151-shift-gate-admin@example.com");
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);

        scheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId)
                .title("CMP2151 停止検証")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(LocalDateTime.of(2026, 2, 20, 10, 0))
                .createdBy(adminId)
                .build()).getId();
        em.flush();
        em.clear();

        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminId.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("フラグOFFのとき9経路すべてが FEATURE_GATE_001 で拒否される")
    void フラグOFFで全9経路が拒否される() throws Exception {
        disableFlag();

        List<MockHttpServletRequestBuilder> requests = List.of(
                get(BASE).param("teamId", teamId.toString()),
                get(BASE + "/{id}", scheduleId),
                post(BASE).param("teamId", teamId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY),
                patch(BASE + "/{id}", scheduleId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete(BASE + "/{id}", scheduleId),
                post(BASE + "/{id}/transition", scheduleId).param("status", "PUBLISHED"),
                get(BASE + "/{id}/summary", scheduleId),
                post(BASE + "/{id}/remind", scheduleId),
                post(BASE + "/{id}/duplicate", scheduleId));
        assertThat(requests).hasSize(9);

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(GATE_CODE));
        }
    }

    @Test
    @DisplayName("フラグONのとき一覧はゲートに拒否されない（対照）")
    void フラグONなら通る() throws Exception {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, FLAG);

        mockMvc.perform(get(BASE).param("teamId", teamId.toString()))
                .andExpect(status().isOk());
    }

    private void disableFlag() {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(FLAG)
                .orElseGet(() -> FeatureFlagEntity.builder()
                        .flagKey(FLAG)
                        .description("CMP-260909-2151 試練用: シフト機能の停止フラグ")
                        .build());
        entity.updateFlag(false, null);
        featureFlagRepository.save(entity);
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
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
                                + "VALUES (:email, 'CMP2151', 'テスト', 'CMP2151 テスト', 'ACTIVE', "
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

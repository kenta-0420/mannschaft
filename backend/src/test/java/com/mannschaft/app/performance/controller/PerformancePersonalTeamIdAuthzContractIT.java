package com.mannschaft.app.performance.controller;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.performance.AggregationType;
import com.mannschaft.app.performance.MetricDataType;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceMetricRepository;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PerformancePersonalController#getMyPerformance}（{@code GET /api/v1/performance/me}）の
 * {@code teamId} クエリパラメータに対する所属検証 契約テスト（試練・認可根治）。
 *
 * <p>正本: 依頼文（AC-31〜33b）・{@code PerformanceStatsService:222-244}
 * （{@code teamId} 指定時に {@link com.mannschaft.app.common.AccessControlService} による所属検証を
 * 一切経由せず、非所属チームの指標定義名・チーム名が読める）。</p>
 *
 * <h2>主眼: teamId 省略（null）が主経路であり、これを壊してはならない</h2>
 * <p>{@code teamId} は {@code @RequestParam(required = false)} であり、null のときは
 * {@code userRoleRepository.findTeamIdsByUserId(currentUserId)} で本人の全所属チームを横断集計する
 * （メソッドの説明どおり「自分のパフォーマンスを全チーム横断で取得する」）。ここへ無条件の認可検証を
 * 足すと null が 403 になり主機能が壊れるため、AC-31 で退行を固定する。</p>
 *
 * <p>{@code teamId} を<b>指定したときだけ</b>次の認可式を適用する（未実装のため現状は素通り）:</p>
 * <pre>isSystemAdmin || isAdminOrAbove || isMember</pre>
 *
 * <p>金型: {@link com.mannschaft.app.notification.NotificationSelfScopeContractIT}
 * （MockMvc 経由の Controller 契約テスト。TEST_CONVENTION.md §3.1.1 により
 * Controller の Bean 直呼びは禁止）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("PerformancePersonalController#getMyPerformance teamId 所属検証 契約テスト（試練）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PerformancePersonalTeamIdAuthzContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PerformanceMetricRepository metricRepository;

    @Autowired
    private PerformanceRecordRepository recordRepository;

    @PersistenceContext
    private EntityManager em;

    // --- テスト用ユーザー（高位ID・seed と衝突しない範囲） ---
    private static final Long USER_MULTI_TEAM_MEMBER = 93_101L;
    private static final Long USER_NON_MEMBER = 93_102L;
    private static final Long USER_SINGLE_TEAM_MEMBER = 93_103L;
    private static final Long USER_ADMIN_VIA_USER_ROLES_ONLY = 93_104L;

    // --- チーム ---
    private static final Long TEAM_MINE_1 = 71_101L;
    private static final Long TEAM_MINE_2 = 71_102L;
    private static final Long TEAM_OTHER_NOT_MINE = 71_103L;
    private static final Long TEAM_SINGLE = 71_104L;
    private static final Long TEAM_ADMIN_ONLY = 71_105L;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, USER_MULTI_TEAM_MEMBER);
        MembershipTestHelper.insertActiveUser(em, USER_NON_MEMBER);
        MembershipTestHelper.insertActiveUser(em, USER_SINGLE_TEAM_MEMBER);
        MembershipTestHelper.insertActiveUser(em, USER_ADMIN_VIA_USER_ROLES_ONLY);
    }

    /** 指定チームに、指定ユーザーの実績を可視化する最小の指標＋記録を1件仕込む。 */
    private void seedMetricAndRecord(Long teamId, Long userId) {
        PerformanceMetricEntity metric = metricRepository.save(PerformanceMetricEntity.builder()
                .teamId(teamId)
                .name("走行距離-" + teamId)
                .unit("km")
                .dataType(MetricDataType.DECIMAL)
                .aggregationType(AggregationType.SUM)
                .isActive(true)
                .build());
        recordRepository.save(PerformanceRecordEntity.builder()
                .metricId(metric.getId())
                .userId(userId)
                .recordedDate(LocalDate.now())
                .value(java.math.BigDecimal.TEN)
                .build());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-31: teamId 省略（主経路） — 本人の全所属チームを横断集計する既存挙動の非回帰
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "93101")
    @DisplayName("AC-31: teamId省略時は本人の所属チーム全部を横断集計して200を返す（主経路の非回帰）")
    void AC31_teamId省略は本人の全所属チームを返す() throws Exception {
        MembershipTestHelper.insertMembership(em, USER_MULTI_TEAM_MEMBER, ScopeType.TEAM, TEAM_MINE_1, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, USER_MULTI_TEAM_MEMBER, ScopeType.TEAM, TEAM_MINE_2, RoleKind.MEMBER);
        seedMetricAndRecord(TEAM_MINE_1, USER_MULTI_TEAM_MEMBER);
        seedMetricAndRecord(TEAM_MINE_2, USER_MULTI_TEAM_MEMBER);

        mockMvc.perform(get("/api/v1/performance/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].teamId")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                TEAM_MINE_1.intValue(), TEAM_MINE_2.intValue())));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-32: 非メンバーが非所属 teamId を指定 → 403 COMMON_002
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "93102")
    @DisplayName("AC-32: 非メンバーが非所属teamIdを指定すると403（COMMON_002）")
    void AC32_非メンバーの非所属teamId指定は403() throws Exception {
        // USER_NON_MEMBER は TEAM_OTHER_NOT_MINE に一切所属していない。
        seedMetricAndRecord(TEAM_OTHER_NOT_MINE, 93_999L);

        mockMvc.perform(get("/api/v1/performance/me").param("teamId", TEAM_OTHER_NOT_MINE.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-33: メンバーが自チームの teamId を指定 → 200
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "93103")
    @DisplayName("AC-33: メンバーが自チームのteamIdを指定すると200")
    void AC33_メンバーの自チームteamId指定は200() throws Exception {
        MembershipTestHelper.insertMembership(em, USER_SINGLE_TEAM_MEMBER, ScopeType.TEAM, TEAM_SINGLE, RoleKind.MEMBER);
        seedMetricAndRecord(TEAM_SINGLE, USER_SINGLE_TEAM_MEMBER);

        mockMvc.perform(get("/api/v1/performance/me").param("teamId", TEAM_SINGLE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].teamId").value(TEAM_SINGLE.intValue()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-33b: user_roles にのみ ADMIN 行を持つ（memberships 行なし）利用者が
    // 自チームの teamId を指定 → 200（isMember単独判定だと落ちる）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "93104")
    @DisplayName("AC-33b: user_rolesのみADMIN（membershipsなし）の利用者が自チームteamId指定で200")
    void AC33b_userRolesのみADMINは自チームteamId指定で200() throws Exception {
        // memberships 行は一切作らず、user_roles にのみ ADMIN を張る
        // （isMember は memberships しか見ないため、isAdminOrAbove との論理和が必須）。
        MembershipTestHelper.insertUserRole(em, USER_ADMIN_VIA_USER_ROLES_ONLY, "ADMIN", TEAM_ADMIN_ONLY, null);
        seedMetricAndRecord(TEAM_ADMIN_ONLY, USER_ADMIN_VIA_USER_ROLES_ONLY);

        mockMvc.perform(get("/api/v1/performance/me").param("teamId", TEAM_ADMIN_ONLY.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].teamId").value(TEAM_ADMIN_ONLY.intValue()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 余力: 存在しない teamId は非メンバーと同じ403（存在オラクルを作らない）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "93102")
    @DisplayName("存在しないteamIdを指定しても非メンバーと同じ403（存在オラクルを作らない）")
    void 存在しないteamIdも非メンバーと同じ403() throws Exception {
        mockMvc.perform(get("/api/v1/performance/me").param("teamId", "999999999"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }
}

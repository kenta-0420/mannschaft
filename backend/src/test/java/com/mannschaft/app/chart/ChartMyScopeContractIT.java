package com.mannschaft.app.chart;

import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * マイカルテ一覧（{@code ChartMyController#listMyCharts}）の自己スコープ契約テスト（実 MySQL + 実 Spring MVC）。
 *
 * <p>一覧の対象は認証主体が顧客であり、かつ顧客へ共有済みのカルテだけである。
 * {@code teamId} は絞り込み条件にすぎず、別のチーム ID を指定しても他人のカルテや
 * 共有されていないカルテは一覧に現れないこと、同じ URL で認証主体だけを差し替えると
 * それぞれ自分のカルテだけが返ることを固定する。</p>
 *
 * <p>金型: {@link ChartScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 手動 SecurityContext）。
 * フィクスチャはすべて JPA リポジトリ経由で作る。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("マイカルテ一覧 自己スコープ契約テスト（ChartMyController#listMyCharts）")
class ChartMyScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final Long ME = 18_350_001L;
    private static final Long OTHER = 18_350_002L;
    private static final Long NO_CHART_USER = 18_350_003L;
    private static final Long STAFF = 18_350_009L;
    private static final Long TEAM_A = 18_351_001L;
    private static final Long TEAM_B = 18_351_002L;
    private static final Long TEAM_UNRELATED = 18_351_999L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ChartRecordRepository chartRecordRepository;

    private Long mineSharedA;
    private Long mineUnsharedA;
    private Long mineSharedB;
    private Long othersSharedA;
    private Long othersSharedB;
    private Long othersUnsharedA;
    private Long othersSharedUnrelated;
    @SuppressWarnings("unused")
    private Long othersUnsharedUnrelated;

    @BeforeEach
    void setUp() {
        mineSharedA = persistChart(ME, TEAM_A, true);
        mineUnsharedA = persistChart(ME, TEAM_A, false);
        mineSharedB = persistChart(ME, TEAM_B, true);
        othersSharedA = persistChart(OTHER, TEAM_A, true);
        othersSharedB = persistChart(OTHER, TEAM_B, true);
        othersUnsharedA = persistChart(OTHER, TEAM_A, false);
        // 本人に縁のないチームにも、他人の共有済み・未共有カルテが実在する。
        othersSharedUnrelated = persistChart(OTHER, TEAM_UNRELATED, true);
        othersUnsharedUnrelated = persistChart(OTHER, TEAM_UNRELATED, false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("チーム指定なしの一覧は、自分宛てで共有済みのカルテだけを返す")
    void list_returnsOnlyOwnSharedCharts() throws Exception {
        setAuth(ME);
        mockMvc.perform(get("/api/v1/charts/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id",
                        containsInAnyOrder(mineSharedA.intValue(), mineSharedB.intValue())));
    }

    @Test
    @DisplayName("チーム指定の一覧は、そのチームの自分宛て共有済みカルテだけを返す")
    void list_withTeamId_returnsOnlyOwnSharedChartsOfThatTeam() throws Exception {
        setAuth(ME);
        mockMvc.perform(get("/api/v1/charts/me").param("teamId", TEAM_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(mineSharedA.intValue())));
        mockMvc.perform(get("/api/v1/charts/me").param("teamId", TEAM_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(mineSharedB.intValue())));
    }

    @Test
    @DisplayName("自分に縁のないチームの ID を指定しても、他人のカルテは一覧に現れない")
    void list_withUnrelatedTeamId_returnsNothing() throws Exception {
        // 対象データが実在することの確認: 所有者本人には共有済みの 1 件だけ取得できる。
        setAuth(OTHER);
        mockMvc.perform(get("/api/v1/charts/me").param("teamId", TEAM_UNRELATED.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(othersSharedUnrelated.intValue())));

        // 同じ URL で認証主体を本人に差し替えると、そのチームの他人のカルテは返らない。
        setAuth(ME);
        mockMvc.perform(get("/api/v1/charts/me").param("teamId", TEAM_UNRELATED.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("同じ URL で認証主体だけ差し替えると、それぞれ自分のカルテだけが返る")
    void list_sameRequestUnderDifferentPrincipal_returnsEachOwnCharts() throws Exception {
        setAuth(OTHER);
        mockMvc.perform(get("/api/v1/charts/me").param("teamId", TEAM_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(othersSharedA.intValue())));
        mockMvc.perform(get("/api/v1/charts/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id",
                        containsInAnyOrder(othersSharedA.intValue(), othersSharedB.intValue(),
                                othersSharedUnrelated.intValue())));

        setAuth(ME);
        mockMvc.perform(get("/api/v1/charts/me").param("teamId", TEAM_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].id", containsInAnyOrder(mineSharedA.intValue())));
    }

    @Test
    @DisplayName("カルテを持たない利用者の一覧は、チーム指定の有無にかかわらず空")
    void list_userWithoutCharts_returnsEmpty() throws Exception {
        setAuth(NO_CHART_USER);
        mockMvc.perform(get("/api/v1/charts/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        mockMvc.perform(get("/api/v1/charts/me").param("teamId", TEAM_A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    private Long persistChart(Long customerUserId, Long teamId, boolean shared) {
        return chartRecordRepository.save(ChartRecordEntity.builder()
                .teamId(teamId)
                .customerUserId(customerUserId)
                .staffUserId(STAFF)
                .visitDate(LocalDate.now())
                .chiefComplaint("マイカルテ契約テスト")
                .isSharedToCustomer(shared)
                .build()).getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}

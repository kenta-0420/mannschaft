package com.mannschaft.app.survey;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyResultViewerEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 試練（#2779）— アンケート詳細応答の {@code viewerCanViewResults} と、
 * 結果取得 API の実際の可否が一致することを HTTP レベルで固定する契約テスト。
 *
 * <p>金型: {@code SurveyDetailShapeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} ＋ 実 MySQL ＋ 手動 SecurityContext）。</p>
 *
 * <p><b>本丸は AC-3（一致性）</b>である。詳細応答が返す可否と、結果取得 API が返す
 * 200/403 が食い違えば「押せるのに必ず失敗する導線」や「見えるはずのものが隠れる」を
 * 生むため、両者を必ず同じ判定経路から導いていることを機械的に証明する。</p>
 *
 * <p>担保する受け入れ条件: <b>AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-6 / AC-7 / AC-8 / AC-9</b>。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("アンケート結果閲覧可否の詳細応答契約（#2779）")
class SurveyViewerCanViewResultsConsistencyIT extends AbstractMySqlIntegrationTest {

    private static final String TEAM_SLUG = "svcanview-team";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SurveyRepository surveyRepository;

    @Autowired
    private SurveyResultViewerRepository resultViewerRepository;

    @Autowired
    private SurveyTargetRepository targetRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    /** アンケート作成者（当該チームの ADMIN）。 */
    private Long creatorId;
    /** 当該チームの一般会員。 */
    private Long memberId;
    /** 当該チームに属さない利用者。 */
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("SVCANVIEW チーム", TEAM_SLUG);

        creatorId = insertUser("svcanview-creator@example.com");
        memberId = insertUser("svcanview-member@example.com");
        outsiderId = insertUser("svcanview-outsider@example.com");

        MembershipTestHelper.insertMembership(em, creatorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, creatorId, "ADMIN", teamId, null);
        // 所属ロール（MEMBER/SUPPORTER）は memberships のみで表現する。
        // user_roles へ張るのは V60.010 移行後の本番で成立しえないため MembershipTestHelper が拒否する。
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1 / AC-2 — 閲覧可否がそのまま応答に載る
    // ═════════════════════════════════════════════════════════════════════

    /** AC-1 — 結果を閲覧できる利用者では true。 */
    @Test
    @DisplayName("AC-1: 結果を閲覧できる利用者では viewerCanViewResults = true")
    void ac1_viewerCanViewResultsIsTrueForPermittedUser() throws Exception {
        Long surveyId = insertSurvey("SVCANVIEW ALWAYS", SurveyStatus.PUBLISHED,
                ResultsVisibility.ALWAYS, null);

        assertDetailFlag(memberId, surveyId, true);
    }

    /** AC-2 — 閲覧できない利用者では false。 */
    @Test
    @DisplayName("AC-2: 閲覧できない利用者では viewerCanViewResults = false")
    void ac2_viewerCanViewResultsIsFalseForDeniedUser() throws Exception {
        Long surveyId = insertSurvey("SVCANVIEW ADMINS_ONLY", SurveyStatus.PUBLISHED,
                ResultsVisibility.ADMINS_ONLY, null);

        assertDetailFlag(memberId, surveyId, false);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-3 — 本丸: 結果取得 API の 200/403 と完全に一致する
    // ═════════════════════════════════════════════════════════════════════

    /**
     * AC-3 — 同一利用者・同一アンケートで、結果取得 API が 403 なら false、200 なら true。
     *
     * <p>可視性の 5 値 × 3 種の利用者を総当たりし、詳細応答の真偽と結果取得の
     * ステータスが 1 件残らず一致することを固定する。</p>
     */
    @Test
    @DisplayName("AC-3: 詳細応答の可否と結果取得 API の 200/403 が全組合せで一致する")
    void ac3_flagMatchesResultsEndpointStatus() throws Exception {
        record Fixture(String label, Long surveyId) {
        }

        List<Fixture> fixtures = List.of(
                new Fixture("AFTER_RESPONSE", insertSurvey("SVCANVIEW AR", SurveyStatus.PUBLISHED,
                        ResultsVisibility.AFTER_RESPONSE, null)),
                new Fixture("AFTER_CLOSE(締切前)", insertSurvey("SVCANVIEW AC 未締切", SurveyStatus.PUBLISHED,
                        ResultsVisibility.AFTER_CLOSE, LocalDateTime.now().plusDays(1))),
                new Fixture("AFTER_CLOSE(締切後)", insertSurvey("SVCANVIEW AC 締切済", SurveyStatus.CLOSED,
                        ResultsVisibility.AFTER_CLOSE, LocalDateTime.now().minusDays(1))),
                new Fixture("ADMINS_ONLY", insertSurvey("SVCANVIEW AO", SurveyStatus.PUBLISHED,
                        ResultsVisibility.ADMINS_ONLY, null)),
                new Fixture("VIEWERS_ONLY", insertSurvey("SVCANVIEW VO", SurveyStatus.PUBLISHED,
                        ResultsVisibility.VIEWERS_ONLY, null)),
                new Fixture("ALWAYS", insertSurvey("SVCANVIEW AL", SurveyStatus.PUBLISHED,
                        ResultsVisibility.ALWAYS, null)),
                new Fixture("DRAFT", insertSurvey("SVCANVIEW DR", SurveyStatus.DRAFT,
                        ResultsVisibility.ALWAYS, null)));
        em.flush();
        em.clear();

        for (Fixture fixture : fixtures) {
            for (Long userId : List.of(creatorId, memberId)) {
                boolean flag = readDetailFlag(userId, fixture.surveyId());
                boolean resultsAllowed = readResultsAllowed(userId, fixture.surveyId());
                assertThat(flag)
                        .as("%s / userId=%d — 詳細応答の可否と結果取得の可否は一致しなければならない",
                                fixture.label(), userId)
                        .isEqualTo(resultsAllowed);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4 / AC-5 — 優先順位（作成者・結果閲覧者名簿）
    // ═════════════════════════════════════════════════════════════════════

    /** AC-4 — 作成者は常に true（優先順 1）。 */
    @Test
    @DisplayName("AC-4: 作成者は結果公開設定に関わらず viewerCanViewResults = true")
    void ac4_creatorAlwaysTrue() throws Exception {
        Long adminsOnly = insertSurvey("SVCANVIEW 作成者 AO", SurveyStatus.PUBLISHED,
                ResultsVisibility.ADMINS_ONLY, null);
        Long viewersOnly = insertSurvey("SVCANVIEW 作成者 VO", SurveyStatus.PUBLISHED,
                ResultsVisibility.VIEWERS_ONLY, null);

        assertDetailFlag(creatorId, adminsOnly, true);
        assertDetailFlag(creatorId, viewersOnly, true);
    }

    /** AC-5 — {@code survey_result_viewers} 登録者は結果公開設定に関わらず true（優先順 3）。 */
    @Test
    @DisplayName("AC-5: 結果閲覧者名簿の登録者は結果公開設定に関わらず true")
    void ac5_registeredResultViewerIsTrue() throws Exception {
        Long surveyId = insertSurvey("SVCANVIEW 名簿", SurveyStatus.PUBLISHED,
                ResultsVisibility.VIEWERS_ONLY, null);
        resultViewerRepository.save(SurveyResultViewerEntity.builder()
                .surveyId(surveyId)
                .userId(memberId)
                .build());
        em.flush();
        em.clear();

        assertDetailFlag(memberId, surveyId, true);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-6 / AC-7 — 時間軸と配信母集団
    // ═════════════════════════════════════════════════════════════════════

    /** AC-6 — AFTER_CLOSE は締切前なら所属者でも false、締切後なら true。 */
    @Test
    @DisplayName("AC-6: AFTER_CLOSE は締切前 false・締切後 true")
    void ac6_afterCloseDependsOnDeadline() throws Exception {
        Long before = insertSurvey("SVCANVIEW AC 前", SurveyStatus.PUBLISHED,
                ResultsVisibility.AFTER_CLOSE, LocalDateTime.now().plusDays(1));
        Long after = insertSurvey("SVCANVIEW AC 後", SurveyStatus.CLOSED,
                ResultsVisibility.AFTER_CLOSE, LocalDateTime.now().minusDays(1));

        assertDetailFlag(memberId, before, false);
        assertDetailFlag(memberId, after, true);
    }

    /** AC-7 — ALWAYS × TARGETED で名簿外の利用者は false。 */
    @Test
    @DisplayName("AC-7: ALWAYS でも配信母集団に含まれない利用者は false")
    void ac7_alwaysExcludesNonTargets() throws Exception {
        SurveyEntity saved = surveyRepository.save(SurveyEntity.builder()
                .scopeType("TEAM")
                .scopeId(teamId)
                .title("SVCANVIEW TARGETED")
                .status(SurveyStatus.PUBLISHED)
                .resultsVisibility(ResultsVisibility.ALWAYS)
                .distributionMode(DistributionMode.TARGETED)
                .createdBy(creatorId)
                .build());
        // 名簿には作成者のみを載せ、一般会員は配信母集団の外に置く。
        targetRepository.save(SurveyTargetEntity.builder()
                .surveyId(saved.getId())
                .userId(creatorId)
                .build());
        em.flush();
        em.clear();

        assertDetailFlag(memberId, saved.getId(), false);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8 / AC-9 — 境界（fail-closed）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * AC-8 — スコープ外の利用者は false（fail-closed）。
     *
     * <p>本体詳細そのものがスコープ所属ガードで 403 になる設計のため、
     * 「非所属に true が返ることは起こり得ない」ことを 403 で固定する。</p>
     */
    @Test
    @DisplayName("AC-8: スコープ外の利用者は詳細そのものを取得できない（fail-closed）")
    void ac8_outsiderIsFailClosed() throws Exception {
        Long surveyId = insertSurvey("SVCANVIEW 境界", SurveyStatus.PUBLISHED,
                ResultsVisibility.ALWAYS, null);
        em.flush();
        em.clear();

        setAuth(outsiderId);
        mockMvc.perform(get("/api/v1/teams/{slug}/surveys/{id}", TEAM_SLUG, surveyId))
                .andExpect(status().isForbidden());
    }

    /** AC-9 — DRAFT は作成者以外の誰に対しても false。 */
    @Test
    @DisplayName("AC-9: DRAFT は作成者以外に対して false")
    void ac9_draftIsFalse() throws Exception {
        Long surveyId = insertSurvey("SVCANVIEW 下書き", SurveyStatus.DRAFT,
                ResultsVisibility.ALWAYS, null);

        assertDetailFlag(memberId, surveyId, false);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 詳細応答の {@code viewerCanViewResults} が期待どおりであることを表明する。 */
    private void assertDetailFlag(Long userId, Long surveyId, boolean expected) throws Exception {
        em.flush();
        em.clear();
        setAuth(userId);
        mockMvc.perform(get("/api/v1/teams/{slug}/surveys/{id}", TEAM_SLUG, surveyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viewerCanViewResults").value(expected));
    }

    /** 詳細応答の {@code viewerCanViewResults} を読み出す。 */
    private boolean readDetailFlag(Long userId, Long surveyId) throws Exception {
        setAuth(userId);
        MvcResult result = mockMvc
                .perform(get("/api/v1/teams/{slug}/surveys/{id}", TEAM_SLUG, surveyId))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.data.viewerCanViewResults");
    }

    /** 結果取得 API が 200 を返すか（403 なら false）。 */
    private boolean readResultsAllowed(Long userId, Long surveyId) throws Exception {
        setAuth(userId);
        int status = mockMvc.perform(get("/api/v1/surveys/{id}/results", surveyId))
                .andReturn().getResponse().getStatus();
        if (status != 200 && status != 403) {
            throw new AssertionError(
                    "結果取得 API が 200/403 以外を返した: status=" + status + ", surveyId=" + surveyId);
        }
        return status == 200;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertSurvey(String title, SurveyStatus status,
                              ResultsVisibility resultsVisibility, LocalDateTime expiresAt) {
        SurveyEntity saved = surveyRepository.save(SurveyEntity.builder()
                .scopeType("TEAM")
                .scopeId(teamId)
                .title(title)
                .status(status)
                .resultsVisibility(resultsVisibility)
                .expiresAt(expiresAt)
                .createdBy(creatorId)
                .build());
        return saved.getId();
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
                                + "VALUES (:email, 'SVCANVIEW', 'テスト', 'SVCANVIEW テスト', 'ACTIVE', "
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
}

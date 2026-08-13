package com.mannschaft.app.survey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 試練（#2635 / #2617）— アンケート API の応答形・enum 束縛・認可の契約テスト（HTTP レベル）。
 *
 * <p>金型: {@code SurveyScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>御裁可（案2・#2635）</b>: {@code SurveyDetailResponse} の入れ子 {@code survey} を解体し、
 * 作成・詳細取得・複製の応答を {@code data.id} ＋ {@code questions} のフラット形に揃える。
 * 一覧・更新・公開・締切・延長は従来どおり。</p>
 *
 * <p>担保する受け入れ条件:
 * <b>AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-12 / AC-13 / AC-16 / AC-17</b>。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("アンケート応答形・enum・認可の契約テスト（#2635 / #2617）")
class SurveyDetailShapeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SurveyRepository surveyRepository;

    @Autowired
    private SurveyQuestionRepository questionRepository;

    @PersistenceContext
    private EntityManager em;

    private static final String TEAM_A_SLUG = "svshape-team-a";
    private static final String TEAM_B_SLUG = "svshape-team-b";

    private Long teamAId;
    private Long teamBId;
    private Long adminTeamAId;
    private Long adminTeamBId;
    private Long draftSurveyTeamAId;
    private Long publishedSurveyTeamAId;
    private Long draftSurveyTeamBId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SVSHAPE チームA", TEAM_A_SLUG);
        teamBId = insertTeam("SVSHAPE チームB", TEAM_B_SLUG);

        adminTeamAId = insertUser("svshape-admin-team-a@example.com");
        adminTeamBId = insertUser("svshape-admin-team-b@example.com");

        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);

        draftSurveyTeamAId = insertSurvey("TEAM", teamAId, "SVSHAPE 下書き", SurveyStatus.DRAFT, adminTeamAId);
        publishedSurveyTeamAId =
                insertSurvey("TEAM", teamAId, "SVSHAPE 公開中", SurveyStatus.PUBLISHED, adminTeamAId);
        draftSurveyTeamBId = insertSurvey("TEAM", teamBId, "SVSHAPE 他チーム", SurveyStatus.DRAFT, adminTeamBId);

        insertQuestion(draftSurveyTeamAId, "SVSHAPE 設問1", 0);
        insertQuestion(publishedSurveyTeamAId, "SVSHAPE 公開設問", 0);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1 / AC-2 / AC-3 / AC-4 — フラット化した応答形
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("応答形のフラット化（#2635）")
    class FlatShape {

        /** AC-1 / AC-2 — 作成応答は data.id で id を返し、questions も含む。 */
        @Test
        @DisplayName("AC-1/AC-2: 作成応答は data.id ＋ questions のフラット形")
        void ac1_ac2_createIsFlat() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSurveyBody(1))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.survey").doesNotExist())
                    .andExpect(jsonPath("$.data.content.title").value("SVSHAPE 新規アンケート"))
                    .andExpect(jsonPath("$.data.questions").isArray())
                    .andExpect(jsonPath("$.data.questions.length()").value(1));
        }

        /** AC-3 — 詳細取得も同じフラット形。 */
        @Test
        @DisplayName("AC-3: 詳細取得は data.id ＋ questions のフラット形")
        void ac3_detailIsFlat() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, draftSurveyTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(draftSurveyTeamAId))
                    .andExpect(jsonPath("$.data.survey").doesNotExist())
                    .andExpect(jsonPath("$.data.status").exists())
                    .andExpect(jsonPath("$.data.scope").exists())
                    .andExpect(jsonPath("$.data.policy").exists())
                    .andExpect(jsonPath("$.data.questions.length()").value(1));
        }

        /** AC-4 — 複製も同じフラット形。 */
        @Test
        @DisplayName("AC-4: 複製応答も data.id ＋ questions のフラット形")
        void ac4_duplicateIsFlat() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/duplicate", TEAM_A_SLUG, draftSurveyTeamAId))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.survey").doesNotExist())
                    .andExpect(jsonPath("$.data.questions").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5 — 既存 EP の応答形は不変（回帰防止）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-5: 一覧・更新・公開・締切・延長の応答形は不変")
    class UnchangedShapes {

        @Test
        @DisplayName("AC-5: 一覧は data[].id のまま")
        void ac5_listUnchanged() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").isNumber())
                    .andExpect(jsonPath("$.data[0].survey").doesNotExist())
                    .andExpect(jsonPath("$.data[0].questions").doesNotExist());
        }

        @Test
        @DisplayName("AC-5: 更新は data.id のまま（questions は含まない）")
        void ac5_updateUnchanged() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "SVSHAPE 更新後"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(draftSurveyTeamAId))
                    .andExpect(jsonPath("$.data.questions").doesNotExist());
        }

        @Test
        @DisplayName("AC-5: 公開は data.id のまま")
        void ac5_publishUnchanged() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/publish", TEAM_A_SLUG, draftSurveyTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(draftSurveyTeamAId))
                    .andExpect(jsonPath("$.data.survey").doesNotExist());
        }

        @Test
        @DisplayName("AC-5: 締切は data.id のまま")
        void ac5_closeUnchanged() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/close", TEAM_A_SLUG, publishedSurveyTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(publishedSurveyTeamAId))
                    .andExpect(jsonPath("$.data.survey").doesNotExist());
        }

        @Test
        @DisplayName("AC-5: 延長は data.id のまま")
        void ac5_extendUnchanged() throws Exception {
            setAuth(adminTeamAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("newDeadline", LocalDateTime.now().plusDays(30).toString());

            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/extend", TEAM_A_SLUG, publishedSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(publishedSurveyTeamAId))
                    .andExpect(jsonPath("$.data.survey").doesNotExist());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12 / AC-13 — enum 不正値は 400
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("enum 束縛（#2617-1,2）")
    class EnumBinding {

        @Test
        @DisplayName("AC-12: resultsVisibility の不正値 RESPONDENTS は400")
        void ac12_invalidResultsVisibilityIs400() throws Exception {
            setAuth(adminTeamAId);
            Map<String, Object> body = createSurveyBody(1);
            body.put("resultsVisibility", "RESPONDENTS");

            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("AC-12: 新値 ALWAYS は受理される（201）")
        void ac12_alwaysIsAccepted() throws Exception {
            setAuth(adminTeamAId);
            Map<String, Object> body = createSurveyBody(1);
            body.put("resultsVisibility", "ALWAYS");

            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.policy.resultsVisibility").value("ALWAYS"));
        }

        @Test
        @DisplayName("AC-13: questionType の不正値 TEXT は400")
        void ac13_invalidQuestionTypeIs400() throws Exception {
            setAuth(adminTeamAId);
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("questionType", "TEXT");
            question.put("questionText", "SVSHAPE 不正設問");
            question.put("isRequired", false);
            question.put("displayOrder", 0);

            Map<String, Object> body = createSurveyBody(0);
            body.put("questions", List.of(question));

            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-16 — 認可（IDOR）。契約変更で緩まないこと
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-16: 他スコープのアンケートID指定は 403/404（IDOR）")
    class Idor {

        @Test
        @DisplayName("AC-16: 自チームURLに他チームのIDを指定した詳細取得は404（存在秘匿）")
        void ac16_detailWithForeignIdIsNotFound() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, draftSurveyTeamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("AC-16: 他チームURLに他チームのIDを指定した詳細取得は403/404")
        void ac16_detailAcrossScopeIsDenied() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/surveys/{id}", TEAM_B_SLUG, draftSurveyTeamBId))
                    .andExpect(result -> {
                        int sc = result.getResponse().getStatus();
                        org.assertj.core.api.Assertions.assertThat(sc)
                                .as("AC-16: 他スコープのアンケート実体を読ませてはならない")
                                .isIn(403, 404);
                    });
        }

        @Test
        @DisplayName("AC-16: 他チームのアンケート結果取得は403/404")
        void ac16_resultsAcrossScopeIsDenied() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/surveys/{id}/results", draftSurveyTeamBId))
                    .andExpect(result -> {
                        int sc = result.getResponse().getStatus();
                        org.assertj.core.api.Assertions.assertThat(sc)
                                .as("AC-16: 他スコープの結果を読ませてはならない")
                                .isIn(403, 404);
                    });
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-17 — 0件（questions 空）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-17: questions 0件でも 201/200 で questions: [] を返す")
    class EmptyQuestions {

        @Test
        @DisplayName("AC-17: 設問なしで作成しても201・questions は空配列")
        void ac17_createWithoutQuestions() throws Exception {
            setAuth(adminTeamAId);
            Map<String, Object> body = createSurveyBody(0);
            body.put("questions", List.of());

            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").isNumber())
                    .andExpect(jsonPath("$.data.questions").isArray())
                    .andExpect(jsonPath("$.data.questions.length()").value(0));
        }

        @Test
        @DisplayName("AC-17: 設問なしアンケートの詳細取得も200・questions は空配列（500 にしない）")
        void ac17_detailWithoutQuestions() throws Exception {
            Long emptySurveyId =
                    insertSurvey("TEAM", teamAId, "SVSHAPE 設問なし", SurveyStatus.DRAFT, adminTeamAId);
            em.flush();
            em.clear();

            setAuth(adminTeamAId);
            mockMvc.perform(get("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, emptySurveyId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(emptySurveyId))
                    .andExpect(jsonPath("$.data.questions").isArray())
                    .andExpect(jsonPath("$.data.questions.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * アンケート作成リクエストボディ。
     * 省略可フィールドに明示 null を渡すと Entity 側の {@code @Builder.Default} が無効化されるため、
     * 必須項目のみを積んで送る。
     */
    private Map<String, Object> createSurveyBody(int questionCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "SVSHAPE 新規アンケート");
        body.put("isAnonymous", false);
        body.put("allowMultipleSubmissions", false);
        body.put("resultsVisibility", "AFTER_RESPONSE");
        body.put("distributionMode", "ALL");

        List<Map<String, Object>> questions = new java.util.ArrayList<>();
        for (int i = 0; i < questionCount; i++) {
            Map<String, Object> question = new LinkedHashMap<>();
            question.put("questionType", "FREE_TEXT");
            question.put("questionText", "SVSHAPE 新規設問" + i);
            question.put("isRequired", false);
            question.put("displayOrder", i);
            questions.add(question);
        }
        body.put("questions", questions);
        return body;
    }

    private Long insertSurvey(String scopeType, Long scopeId, String title,
                              SurveyStatus status, Long createdBy) {
        SurveyEntity saved = surveyRepository.save(SurveyEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title(title)
                .status(status)
                .createdBy(createdBy)
                .build());
        return saved.getId();
    }

    private void insertQuestion(Long surveyId, String questionText, int displayOrder) {
        questionRepository.save(SurveyQuestionEntity.builder()
                .surveyId(surveyId)
                .questionType(QuestionType.FREE_TEXT)
                .questionText(questionText)
                .isRequired(false)
                .displayOrder(displayOrder)
                .build());
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
                                + "VALUES (:email, 'SVSHAPE', 'テスト', 'SVSHAPE テスト', 'ACTIVE', "
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

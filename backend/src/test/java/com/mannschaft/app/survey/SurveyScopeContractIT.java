package com.mannschaft.app.survey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7 — survey ドメイン（F05.4 アンケート・投票）API 契約テスト。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}。
 * 金型: {@code EquipmentScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 *
 * <p><b>検証する防御仕様</b></p>
 * <ul>
 *   <li>作成（{@code POST /surveys}）は当該スコープの会員のみ。</li>
 *   <li>既存アンケートの管理操作（更新 / 公開 / 締切 / 削除 / 設問追加・削除 /
 *       配信対象追加 / 結果閲覧者追加）は<b>作成者または ADMIN+</b> のみ。</li>
 *   <li>認可スコープは<b>アンケート実体由来</b>で確定する。パス変数のスコープと実体の
 *       スコープが一致しない指定は <b>404（存在秘匿）</b>。</li>
 *   <li>自分の回答取得 EP（{@code /responses/me}）は自己スコープで閉じ、
 *       呼び出し元本人の回答行のみを返す。</li>
 * </ul>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 別スコープ ADMIN（BOLA）/ 非 ADMIN メンバー /
 * 正当な権限保持者（作成者・当該スコープ ADMIN）。各エンドポイントに正常系（200/201/204）を含める。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("survey ドメイン（アンケート・投票）認可契約テスト")
class SurveyScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SurveyRepository surveyRepository;

    @Autowired
    private SurveyQuestionRepository questionRepository;

    @Autowired
    private SurveyResponseRepository responseRepository;

    @PersistenceContext
    private EntityManager em;

    private static final String TEAM_A_SLUG = "svauthz-team-a";
    private static final String TEAM_B_SLUG = "svauthz-team-b";
    private static final String ORG_A_SLUG = "svauthz-org-a";
    private static final String ORG_B_SLUG = "svauthz-org-b";

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    private Long adminTeamAId;   // TEAM A の ADMIN（正当）
    private Long adminTeamBId;   // TEAM B の ADMIN（別スコープ）
    private Long memberTeamAId;  // TEAM A の非 ADMIN メンバー（アンケート作成者ではない）
    private Long creatorTeamAId; // TEAM A の非 ADMIN メンバーかつアンケート作成者
    private Long adminOrgAId;    // ORG A の ADMIN（正当）
    private Long adminOrgBId;    // ORG B の ADMIN（別スコープ）
    private Long memberOrgAId;   // ORG A の非 ADMIN メンバー
    private Long outsiderId;     // どこにも所属しない非メンバー

    private Long draftSurveyTeamAId;      // DRAFT・設問あり（更新 / 公開 / 設問 / 対象者 / 閲覧者の検証用）
    private Long publishedSurveyTeamAId;  // PUBLISHED（締切の検証用）
    private Long deletableSurveyTeamAId;  // DRAFT（削除の検証用）
    private Long draftSurveyOrgAId;       // ORG A の DRAFT
    private Long deletableQuestionTeamAId;   // draftSurveyTeamA に属する削除検証用の設問
    private Long publishedQuestionTeamAId;   // publishedSurveyTeamA に属する設問（回答取得の検証用）

    /** 実在しない ID（他テストと衝突しない高位の値）。 */
    private static final Long MISSING_SURVEY_ID = 987_654_321L;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SVAUTHZ チームA", TEAM_A_SLUG);
        teamBId = insertTeam("SVAUTHZ チームB", TEAM_B_SLUG);
        orgAId = insertOrganization("SVAUTHZ 組織A", ORG_A_SLUG);
        orgBId = insertOrganization("SVAUTHZ 組織B", ORG_B_SLUG);

        adminTeamAId = insertUser("svauthz-admin-team-a@example.com");
        adminTeamBId = insertUser("svauthz-admin-team-b@example.com");
        memberTeamAId = insertUser("svauthz-member-team-a@example.com");
        creatorTeamAId = insertUser("svauthz-creator-team-a@example.com");
        adminOrgAId = insertUser("svauthz-admin-org-a@example.com");
        adminOrgBId = insertUser("svauthz-admin-org-b@example.com");
        memberOrgAId = insertUser("svauthz-member-org-a@example.com");
        outsiderId = insertUser("svauthz-outsider@example.com");

        // checkMembership（memberships）と isAdminOrAbove（user_roles）は別系統のため、
        // ADMIN ユーザーにも memberships 行を張る（EquipmentScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminTeamBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminTeamBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, creatorTeamAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, adminOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminOrgBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminOrgBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, memberOrgAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        draftSurveyTeamAId = insertSurvey("TEAM", teamAId, "SVAUTHZ 下書き", SurveyStatus.DRAFT, creatorTeamAId);
        publishedSurveyTeamAId =
                insertSurvey("TEAM", teamAId, "SVAUTHZ 公開中", SurveyStatus.PUBLISHED, creatorTeamAId);
        deletableSurveyTeamAId =
                insertSurvey("TEAM", teamAId, "SVAUTHZ 削除対象", SurveyStatus.DRAFT, creatorTeamAId);
        draftSurveyOrgAId = insertSurvey("ORGANIZATION", orgAId, "SVAUTHZ 組織下書き",
                SurveyStatus.DRAFT, adminOrgAId);

        // publish には設問が 1 問以上必要（NO_QUESTIONS 回避）。
        insertQuestion(draftSurveyTeamAId, "SVAUTHZ 設問1", 0);
        deletableQuestionTeamAId = insertQuestion(draftSurveyTeamAId, "SVAUTHZ 設問2（削除対象）", 1);
        publishedQuestionTeamAId = insertQuestion(publishedSurveyTeamAId, "SVAUTHZ 公開設問", 0);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. POST /api/v1/teams/{slug}/surveys（作成: スコープ会員のみ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. POST /teams/{slug}/surveys（アンケート作成）")
    class CreateSurvey {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSurveyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（チームBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSurveyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは201")
        void 非ADMINメンバーは201() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSurveyBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys", TEAM_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSurveyBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. PATCH /api/v1/teams/{slug}/surveys/{id}（更新: 作成者 or ADMIN+）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. PATCH /teams/{slug}/surveys/{id}（更新）")
    class UpdateSurvey {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが他チームのURLを指定すると403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが自チームのURLに他チームのIDを指定すると404（存在秘匿）")
        void 別scopeADMINは自スコープURLでも404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/surveys/{id}", TEAM_B_SLUG, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("存在しないアンケートIDは404")
        void 存在しないIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, MISSING_SURVEY_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("作成者は200")
        void 作成者は200() throws Exception {
            setAuth(creatorTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(patch("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /api/v1/teams/{slug}/surveys/{id}/publish（公開: 作成者 or ADMIN+）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /teams/{slug}/surveys/{id}/publish（公開）")
    class PublishSurvey {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/publish", TEAM_A_SLUG, draftSurveyTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/publish", TEAM_A_SLUG, draftSurveyTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが自チームのURLに他チームのIDを指定すると404（存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/publish", TEAM_B_SLUG, draftSurveyTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/publish", TEAM_A_SLUG, draftSurveyTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /api/v1/teams/{slug}/surveys/{id}/close（締切: 作成者 or ADMIN+）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST /teams/{slug}/surveys/{id}/close（締切）")
    class CloseSurvey {

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/close",
                            TEAM_A_SLUG, publishedSurveyTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが自チームのURLに他チームのIDを指定すると404（存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/close",
                            TEAM_B_SLUG, publishedSurveyTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("作成者は200")
        void 作成者は200() throws Exception {
            setAuth(creatorTeamAId);
            mockMvc.perform(post("/api/v1/teams/{slug}/surveys/{id}/close",
                            TEAM_A_SLUG, publishedSurveyTeamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. DELETE /api/v1/teams/{slug}/surveys/{id}（削除: 作成者 or ADMIN+）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE /teams/{slug}/surveys/{id}（削除）")
    class DeleteSurvey {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, deletableSurveyTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, deletableSurveyTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが自チームのURLに他チームのIDを指定すると404（存在秘匿）")
        void 別scopeADMINは404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{slug}/surveys/{id}", TEAM_B_SLUG, deletableSurveyTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{slug}/surveys/{id}", TEAM_A_SLUG, deletableSurveyTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 設問 POST/DELETE .../surveys/{id}/questions（作成者 or ADMIN+）
    //    ※ 本 EP の scopeId は数値 ID（他 EP のスラッグとは別契約・既存仕様）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. 設問の追加・削除")
    class Questions {

        @Test
        @DisplayName("非メンバーは追加403")
        void 非メンバーは追加403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{scopeId}/surveys/{id}/questions", teamAId, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createQuestionBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は追加403")
        void 非ADMINメンバーは追加403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/teams/{scopeId}/surveys/{id}/questions", teamAId, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createQuestionBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが自チームのIDに他チームのアンケートIDを指定すると404（存在秘匿）")
        void 別scopeADMINは追加404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/teams/{scopeId}/surveys/{id}/questions", teamBId, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createQuestionBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは追加201")
        void 正当ADMINは追加201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/teams/{scopeId}/surveys/{id}/questions", teamAId, draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createQuestionBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は削除403")
        void 非ADMINメンバーは削除403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{scopeId}/surveys/{id}/questions/{questionId}",
                            teamAId, draftSurveyTeamAId, deletableQuestionTeamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが自チームのIDに他チームのアンケートIDを指定すると削除404（存在秘匿）")
        void 別scopeADMINは削除404() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(delete("/api/v1/teams/{scopeId}/surveys/{id}/questions/{questionId}",
                            teamBId, draftSurveyTeamAId, deletableQuestionTeamAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("作成者は削除204")
        void 作成者は削除204() throws Exception {
            setAuth(creatorTeamAId);
            mockMvc.perform(delete("/api/v1/teams/{scopeId}/surveys/{id}/questions/{questionId}",
                            teamAId, draftSurveyTeamAId, deletableQuestionTeamAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. POST /api/v1/surveys/{id}/targets（配信対象追加: 作成者 or ADMIN+）
    //    スコープ無しパスのため認可スコープはアンケート実体由来で確定する。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. POST /surveys/{id}/targets（配信対象追加）")
    class AddTargets {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/surveys/{id}/targets", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(outsiderId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（チームBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/surveys/{id}/targets", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(adminTeamBId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/surveys/{id}/targets", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(memberTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("存在しないアンケートIDは404")
        void 存在しないIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/surveys/{id}/targets", MISSING_SURVEY_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(memberTeamAId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("作成者は201")
        void 作成者は201() throws Exception {
            setAuth(creatorTeamAId);
            mockMvc.perform(post("/api/v1/surveys/{id}/targets", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(memberTeamAId))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/surveys/{id}/targets", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(memberTeamAId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. POST /api/v1/surveys/{id}/result-viewers（結果閲覧者追加: 作成者 or ADMIN+）
    //    結果閲覧権の付与自体を管理権限者に限定する（自己付与による閲覧権の獲得を封じる）。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. POST /surveys/{id}/result-viewers（結果閲覧者追加）")
    class AddResultViewers {

        @Test
        @DisplayName("非メンバーが自分を閲覧者に指定しても403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/surveys/{id}/result-viewers", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(outsiderId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが自分を閲覧者に指定しても403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminTeamBId);
            mockMvc.perform(post("/api/v1/surveys/{id}/result-viewers", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(adminTeamBId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）が自分を閲覧者に指定しても403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberTeamAId);
            mockMvc.perform(post("/api/v1/surveys/{id}/result-viewers", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(memberTeamAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("存在しないアンケートIDは404")
        void 存在しないIDは404() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/surveys/{id}/result-viewers", MISSING_SURVEY_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(memberTeamAId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("作成者は201")
        void 作成者は201() throws Exception {
            setAuth(creatorTeamAId);
            mockMvc.perform(post("/api/v1/surveys/{id}/result-viewers", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(memberTeamAId))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminTeamAId);
            mockMvc.perform(post("/api/v1/surveys/{id}/result-viewers", draftSurveyTeamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(userIdsBody(memberTeamAId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. GET /api/v1/surveys/{id}/responses/me（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. GET /surveys/{id}/responses/me（自分の回答取得・自己スコープ）")
    class MyResponses {

        @Test
        @DisplayName("回答者本人は自分の回答のみ200で取得できる")
        void 本人は200で自分の回答のみ() throws Exception {
            insertResponse(publishedSurveyTeamAId, publishedQuestionTeamAId, memberTeamAId, "自分の回答");
            insertResponse(publishedSurveyTeamAId, publishedQuestionTeamAId, creatorTeamAId, "他人の回答");
            em.flush();
            em.clear();

            setAuth(memberTeamAId);
            mockMvc.perform(get("/api/v1/surveys/{id}/responses/me", publishedSurveyTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].textResponse").value("自分の回答"));
        }

        @Test
        @DisplayName("未回答ユーザーは200かつ空配列（他ユーザーの回答は返らない）")
        void 未回答ユーザーは空配列() throws Exception {
            insertResponse(publishedSurveyTeamAId, publishedQuestionTeamAId, creatorTeamAId, "他人の回答");
            em.flush();
            em.clear();

            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/surveys/{id}/responses/me", publishedSurveyTeamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. 組織スコープ（/organizations/{slug}/surveys）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. 組織スコープ /organizations/{slug}/surveys")
    class OrganizationScope {

        @Test
        @DisplayName("非メンバーは作成403")
        void 非メンバーは作成403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/surveys", ORG_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSurveyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（組織BのADMIN）は作成403")
        void 別scopeADMINは作成403() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/surveys", ORG_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSurveyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは作成201")
        void 非ADMINメンバーは作成201() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(post("/api/v1/organizations/{slug}/surveys", ORG_A_SLUG)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSurveyBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非ADMINメンバー（非作成者）は更新403")
        void 非ADMINメンバーは更新403() throws Exception {
            setAuth(memberOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{slug}/surveys/{id}", ORG_A_SLUG, draftSurveyOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINが自組織のURLに他組織のIDを指定すると404（存在秘匿）")
        void 別scopeADMINは更新404() throws Exception {
            setAuth(adminOrgBId);
            mockMvc.perform(patch("/api/v1/organizations/{slug}/surveys/{id}", ORG_B_SLUG, draftSurveyOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminOrgAId);
            mockMvc.perform(patch("/api/v1/organizations/{slug}/surveys/{id}", ORG_A_SLUG, draftSurveyOrgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateSurveyBody())))
                    .andExpect(status().isOk());
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
    private Map<String, Object> createSurveyBody() {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("questionType", "FREE_TEXT");
        question.put("questionText", "SVAUTHZ 新規設問");
        question.put("isRequired", false);
        question.put("displayOrder", 0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "SVAUTHZ 新規アンケート");
        body.put("isAnonymous", false);
        body.put("allowMultipleSubmissions", false);
        body.put("resultsVisibility", "AFTER_RESPONSE");
        body.put("distributionMode", "ALL");
        body.put("questions", List.of(question));
        return body;
    }

    private Map<String, Object> updateSurveyBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "SVAUTHZ 更新後タイトル");
        return body;
    }

    private Map<String, Object> createQuestionBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("questionType", "FREE_TEXT");
        body.put("questionText", "SVAUTHZ 追加設問");
        body.put("isRequired", false);
        body.put("displayOrder", 5);
        return body;
    }

    private Map<String, Object> userIdsBody(Long userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userIds", List.of(userId));
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

    private Long insertQuestion(Long surveyId, String questionText, int displayOrder) {
        SurveyQuestionEntity saved = questionRepository.save(SurveyQuestionEntity.builder()
                .surveyId(surveyId)
                .questionType(QuestionType.FREE_TEXT)
                .questionText(questionText)
                .isRequired(false)
                .displayOrder(displayOrder)
                .build());
        return saved.getId();
    }

    private void insertResponse(Long surveyId, Long questionId, Long userId, String textResponse) {
        responseRepository.save(SurveyResponseEntity.builder()
                .surveyId(surveyId)
                .questionId(questionId)
                .userId(userId)
                .textResponse(textResponse)
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
                                + "VALUES (:email, 'SVAUTHZ', 'テスト', 'SVAUTHZ テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}

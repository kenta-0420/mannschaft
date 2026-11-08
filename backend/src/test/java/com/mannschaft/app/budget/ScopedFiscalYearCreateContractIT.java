package com.mannschaft.app.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * チーム・組織の会計年度作成契約を実 JWT・Security フィルタ・MySQL で検査する。
 *
 * <p>3項目本文と期間エラーは red 試練、既存の認可境界は characterization。
 * テスト全体を TX で包まず、HTTP の書込を commit させ、別 TX の再読込で非保存も確認する。
 * 外部 Redis だけは共通基底の構成を継承し、業務 Bean・認可・DB は実物を通す。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("スコープ別会計年度作成 API 契約")
class ScopedFiscalYearCreateContractIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private BudgetFiscalYearRepository fiscalYears;
    @PersistenceContext private EntityManager em;

    private Long admin;
    private Long member;
    private Long foreignAdmin;
    private TeamEntity team;
    private TeamEntity foreignTeam;
    private OrganizationEntity organization;
    private OrganizationEntity foreignOrganization;
    private Long teamYearId;
    private Long organizationYearId;

    @BeforeEach
    void fixtureをコミットする() {
        inTx(() -> {
            admin = user("admin");
            member = user("member");
            foreignAdmin = user("foreign-admin");
            team = team("自チーム");
            foreignTeam = team("他チーム");
            organization = organization("自組織");
            foreignOrganization = organization("他組織");
            membership(admin, "TEAM", team.getId(), true);
            membership(member, "TEAM", team.getId(), false);
            membership(foreignAdmin, "TEAM", foreignTeam.getId(), true);
            membership(admin, "ORGANIZATION", organization.getId(), true);
            membership(member, "ORGANIZATION", organization.getId(), false);
            membership(foreignAdmin, "ORGANIZATION", foreignOrganization.getId(), true);
            teamYearId = existingYear("TEAM");
            organizationYearId = existingYear("ORGANIZATION");
            return null;
        });
    }

    @AfterEach
    void 自分が作成したfixtureだけ削除する() {
        if (team == null || organization == null) {
            return;
        }
        inTx(() -> {
            for (String scope : List.of("TEAM", "ORGANIZATION")) {
                fiscalYears.deleteAll(fiscalYears.findByScopeTypeAndScopeId(scope, scopeId(scope)));
                fiscalYears.deleteAll(fiscalYears.findByScopeTypeAndScopeId(scope, foreignScopeId(scope)));
            }
            for (Long userId : List.of(admin, member, foreignAdmin)) {
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :id")
                        .setParameter("id", userId).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :id")
                        .setParameter("id", userId).executeUpdate();
            }
            teams.deleteAll(List.of(team, foreignTeam));
            organizations.deleteAll(List.of(organization, foreignOrganization));
            users.deleteAllById(List.of(admin, member, foreignAdmin));
            return null;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    void 管理者がslugと3項目本文で作成でき一覧とDBに永続化される(String scope) throws Exception {
        JsonNode data = created(scope, body("新会計年度", "2027-01-01", "2027-12-31"));
        Long id = data.path("id").asLong();

        assertThat(data.path("scopeId").asLong()).isEqualTo(scopeId(scope));
        assertThat(data.path("scopeType").asText()).isEqualTo(scope);
        assertThat(data.path("status").asText()).isEqualTo("OPEN");
        BudgetFiscalYearEntity loaded = inTx(() -> fiscalYears.findById(id).orElseThrow());
        assertThat(loaded.getName()).isEqualTo("新会計年度");
        assertThat(loaded.getStartDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(loaded.getEndDate()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(loaded.getScopeId()).isEqualTo(scopeId(scope));
        assertThat(loaded.getScopeType()).isEqualTo(scope);
        http(get(path(scope)), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.id == " + id + ")].name").value("新会計年度"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    void 年度名100文字は保存できる(String scope) throws Exception {
        String name = "年".repeat(100);
        JsonNode data = created(scope, body(name, "2027-01-01", "2027-12-31"));
        assertThat(inTx(() -> fiscalYears.findById(data.path("id").asLong()).orElseThrow().getName()))
                .isEqualTo(name);
    }

    @ParameterizedTest
    @CsvSource({"TEAM,name,empty", "TEAM,name,blank", "TEAM,name,missing", "TEAM,name,null", "TEAM,name,long",
            "TEAM,startDate,missing", "TEAM,startDate,null", "TEAM,endDate,missing", "TEAM,endDate,null",
            "ORGANIZATION,name,empty", "ORGANIZATION,name,blank", "ORGANIZATION,name,missing", "ORGANIZATION,name,null",
            "ORGANIZATION,name,long", "ORGANIZATION,startDate,missing", "ORGANIZATION,startDate,null",
            "ORGANIZATION,endDate,missing", "ORGANIZATION,endDate,null"})
    void 不正な必須入力を400で拒否し既存年度を保持する(String scope, String field, String invalid) throws Exception {
        Map<String, Object> body = body("新年度", "2027-01-01", "2027-12-31");
        if ("missing".equals(invalid)) {
            body.remove(field);
        } else {
            body.put(field, "null".equals(invalid) ? null : "long".equals(invalid) ? "年".repeat(101) : "empty".equals(invalid) ? "" : "   ");
        }

        http(post(path(scope)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"))
                .andExpect(jsonPath("$.error.fieldErrors[?(@.field == '" + field + "')]").isNotEmpty());
        assertUnchanged(scope);
    }

    @ParameterizedTest
    @CsvSource({"TEAM,2027-01-01,2027-01-01", "TEAM,2027-12-31,2027-01-01",
            "ORGANIZATION,2027-01-01,2027-01-01", "ORGANIZATION,2027-12-31,2027-01-01"})
    void 同日と逆順を入力エラー400で拒否する(String scope, String start, String end) throws Exception {
        Map<String, Object> body = body("不正期間", start, end);
        // 旧DTOでも形式検証を通し、期間チェックそのものの誤った404を red で捕捉する。
        body.put("scopeId", scopeId(scope));
        body.put("scopeType", scope);
        http(post(path(scope)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("COMMON_001"));
        assertUnchanged(scope);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    void 期間重複を400で拒否し既存年度を保持する(String scope) throws Exception {
        http(post(path(scope)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body("重複期間", "2026-12-31", "2027-12-31"))), admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("BUDGET_016"));
        assertUnchanged(scope);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    void 本文のscope偽装は無視されパスのスコープだけへ保存される(String scope) throws Exception {
        Map<String, Object> body = body("スコープ偽装", "2027-01-01", "2027-12-31");
        body.put("scopeId", foreignScopeId(scope));
        body.put("scopeType", "TEAM".equals(scope) ? "ORGANIZATION" : "TEAM");
        JsonNode data = created(scope, body);

        BudgetFiscalYearEntity loaded = inTx(() -> fiscalYears.findById(data.path("id").asLong()).orElseThrow());
        assertThat(loaded.getScopeId()).isEqualTo(scopeId(scope));
        assertThat(loaded.getScopeType()).isEqualTo(scope);
        assertThat(inTx(() -> fiscalYears.findByScopeTypeAndScopeId(scope, foreignScopeId(scope)))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,member", "TEAM,foreign", "TEAM,anonymous",
            "ORGANIZATION,member", "ORGANIZATION,foreign", "ORGANIZATION,anonymous"})
    void MEMBERと越境管理者と未認証は年度一覧取得を拒否される(String scope, String actor) throws Exception {
        http(get(path(scope)), actorId(actor)).andExpect(status().is("anonymous".equals(actor) ? 401 : 403));
    }

    @ParameterizedTest
    @CsvSource({"TEAM,member", "TEAM,foreign", "TEAM,anonymous",
            "ORGANIZATION,member", "ORGANIZATION,foreign", "ORGANIZATION,anonymous"})
    void MEMBERと越境管理者と未認証は作成を拒否され保存されない(String scope, String actor) throws Exception {
        Map<String, Object> body = body("不正作成", "2027-01-01", "2027-12-31");
        body.put("scopeId", foreignScopeId(scope));
        body.put("scopeType", scope);
        http(post(path(scope)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)), actorId(actor))
                .andExpect(status().is("anonymous".equals(actor) ? 401 : 403));
        assertUnchanged(scope);
    }

    @ParameterizedTest
    @CsvSource({"TEAM,member", "TEAM,foreign", "TEAM,anonymous",
            "ORGANIZATION,member", "ORGANIZATION,foreign", "ORGANIZATION,anonymous"})
    void MEMBERと越境管理者と未認証はサマリを拒否される(String scope, String actor) throws Exception {
        Long id = "TEAM".equals(scope) ? teamYearId : organizationYearId;
        http(get("/api/v1/budget/fiscal-years/{id}/summary", id), actorId(actor))
                .andExpect(status().is("anonymous".equals(actor) ? 401 : 403));
    }

    private JsonNode created(String scope, Map<String, Object> body) throws Exception {
        String response = http(post(path(scope)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)), admin)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data");
    }

    private void assertUnchanged(String scope) {
        List<BudgetFiscalYearEntity> rows = inTx(() -> fiscalYears.findByScopeTypeAndScopeId(scope, scopeId(scope)));
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getName()).isEqualTo("既存年度");
        assertThat(rows.getFirst().getStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(rows.getFirst().getEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    private ResultActions http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
        }
        return mockMvc.perform(request);
    }

    private Map<String, Object> body(String name, String start, String end) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("startDate", start);
        body.put("endDate", end);
        return body;
    }

    private Long actorId(String actor) {
        return "anonymous".equals(actor) ? null : "member".equals(actor) ? member : foreignAdmin;
    }

    private String path(String scope) {
        return "TEAM".equals(scope) ? "/api/v1/teams/" + team.getSlug() + "/budget/fiscal-years"
                : "/api/v1/organizations/" + organization.getSlug() + "/budget/fiscal-years";
    }

    private Long scopeId(String scope) {
        return "TEAM".equals(scope) ? team.getId() : organization.getId();
    }

    private Long foreignScopeId(String scope) {
        return "TEAM".equals(scope) ? foreignTeam.getId() : foreignOrganization.getId();
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long user(String label) {
        return users.save(UserEntity.builder().email("budget-contract-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }

    private TeamEntity team(String label) {
        return teams.save(TeamEntity.builder().slug("budget-" + UUID.randomUUID().toString().substring(0, 8))
                .name(label).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
    }

    private OrganizationEntity organization(String label) {
        return organizations.save(OrganizationEntity.builder().slug("budget-" + UUID.randomUUID().toString().substring(0, 8))
                .name(label).orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build());
    }

    private void membership(Long userId, String scope, Long id, boolean isAdmin) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.valueOf(scope), id, RoleKind.MEMBER);
        if (isAdmin) {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", "TEAM".equals(scope) ? id : null,
                    "ORGANIZATION".equals(scope) ? id : null);
        }
    }

    private Long existingYear(String scope) {
        return fiscalYears.save(BudgetFiscalYearEntity.builder().scopeType(scope).scopeId(scopeId(scope))
                .name("既存年度").startDate(LocalDate.of(2026, 1, 1)).endDate(LocalDate.of(2026, 12, 31))
                .createdBy(admin).build()).getId();
    }
}

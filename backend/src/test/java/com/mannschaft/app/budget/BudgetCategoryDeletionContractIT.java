package com.mannschaft.app.budget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.budget.entity.BudgetAllocationEntity;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetAllocationRepository;
import com.mannschaft.app.budget.repository.BudgetCategoryRepository;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.budget.service.BudgetCategoryService;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 費目削除を実JWT・Security・MySQL・別TX再読込で検査する(CMP-261017-2047 AC1-7/12)。
 * fixtureとHTTPの書込はcommitし、テスト全体のrollbackによる偽の緑を避ける。
 * 認可の既存契約はcharacterization、論理削除と履歴保持は実装前redを確認する。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BudgetCategoryDeletionContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private BudgetFiscalYearRepository years;
    @Autowired private BudgetCategoryRepository categories;
    @Autowired private BudgetAllocationRepository allocations;
    @Autowired private BudgetTransactionRepository transactions;
    @Autowired private BudgetCategoryService categoryService;
    @PersistenceContext private EntityManager em;
    // S3/R2だけは外部境界として置換し、報告書の実生成バイト列を観測する。
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.mannschaft.app.common.storage.R2StorageService storage;
    @Autowired private com.mannschaft.app.budget.repository.BudgetReportRepository reports;
    private Long admin;
    private Long member;
    private Long foreignAdmin;
    private TeamEntity team;
    private TeamEntity foreignTeam;
    private OrganizationEntity organization;
    private OrganizationEntity foreignOrganization;
    private Long teamYear;
    private Long orgYear;

    @BeforeEach
    void fixtureを別TXでコミットする() {
        tx(() -> {
            admin = user("管理者"); member = user("会員"); foreignAdmin = user("他管理者");
            team = teams.save(TeamEntity.builder().slug("budget-del-" + UUID.randomUUID().toString().substring(0, 8)).name("削除試練")
                    .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
            foreignTeam = teams.save(TeamEntity.builder().slug("budget-del-" + UUID.randomUUID().toString().substring(0, 8)).name("他チーム")
                    .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
            organization = organizations.save(OrganizationEntity.builder().slug("budget-del-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("削除試練組織").orgType(OrganizationEntity.OrgType.OTHER)
                    .visibility(OrganizationEntity.Visibility.PUBLIC).hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .supporterEnabled(false).build());
            foreignOrganization = organizations.save(OrganizationEntity.builder().slug("budget-del-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("他組織").orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build());
            membership(foreignAdmin, "ORGANIZATION", foreignOrganization.getId(), true);
            membership(admin, "TEAM", team.getId(), true); membership(member, "TEAM", team.getId(), false);
            membership(foreignAdmin, "TEAM", foreignTeam.getId(), true);
            membership(admin, "ORGANIZATION", organization.getId(), true);
            membership(member, "ORGANIZATION", organization.getId(), false);
            teamYear = year("TEAM"); orgYear = year("ORGANIZATION");
            return null;
        });
    }

    @AfterEach
    void 所有fixtureだけ片付ける() {
        SecurityContextHolder.clearContext();
        if (team == null) return;
        tx(() -> {
            for (Long id : List.of(teamYear, orgYear)) {
                em.createNativeQuery("DELETE FROM storage_acls WHERE file_key LIKE :prefix")
                        .setParameter("prefix", "budget/reports/" + id + "/%").executeUpdate();
                em.createNativeQuery("DELETE FROM budget_reports WHERE fiscal_year_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM budget_transactions WHERE fiscal_year_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM budget_allocations WHERE fiscal_year_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM budget_categories WHERE fiscal_year_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM budget_fiscal_years WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : List.of(admin, member, foreignAdmin)) {
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :id").setParameter("id", id).executeUpdate();
            }
            teams.deleteAll(List.of(team, foreignTeam)); organizations.deleteAll(List.of(organization, foreignOrganization));
            users.deleteAllById(List.of(admin, member, foreignAdmin));
            return null;
        });
    }

    @ParameterizedTest
    @CsvSource({"TEAM,-1", "TEAM,0", "TEAM,1200", "ORGANIZATION,-1", "ORGANIZATION,0", "ORGANIZATION,1200"})
    void 配分なし0円正額の費目を204論理削除し配分ID金額を保持する(String scope, int amount) throws Exception {
        Long category = category(scope, null);
        Long allocation = amount < 0 ? null : tx(() -> allocations.save(BudgetAllocationEntity.builder()
                .fiscalYearId(yearId(scope)).categoryId(category).amount(BigDecimal.valueOf(amount)).build()).getId());
        remove(category, scope, admin).andExpect(status().isNoContent());
        assertDeleted(category);
        if (allocation != null) {
            assertThat(tx(() -> allocations.findById(allocation).orElseThrow().getAmount())).isEqualByComparingTo(BigDecimal.valueOf(amount));
        }
        http(get(base(scope) + "/categories"), admin).andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        remove(category, scope, admin).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    void 削除後も配分取引詳細一覧CSVと集計を同額で保持し取消できる(String scope) throws Exception {
        Long category = category(scope, null);
        Long allocation = tx(() -> allocations.save(BudgetAllocationEntity.builder().fiscalYearId(yearId(scope))
                .categoryId(category).amount(new BigDecimal("1200")).build()).getId());
        Long transaction = transaction(scope, category);
        JsonNode before = data(get(base(scope) + "/summary"));
        remove(category, scope, admin).andExpect(status().isNoContent());
        assertDeleted(category);
        assertThat(tx(() -> transactions.findById(transaction).orElseThrow().getAmount())).isEqualByComparingTo("300");
        http(get(base(scope) + "/allocations"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(allocation))
                .andExpect(jsonPath("$.data[0].categoryName").value("試練費目（削除済み費目）"));
        http(get("/api/v1/budget/transactions/{id}", transaction).param("scopeId", scopeId(scope).toString())
                .param("scopeType", scope), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryName").value("試練費目（削除済み費目）"));
        String listPath = "TEAM".equals(scope) ? "/api/v1/teams/" + team.getSlug() + "/budget/transactions"
                : "/api/v1/organizations/" + organization.getSlug() + "/budget/transactions";
        http(get(listPath).param("fiscalYearId", yearId(scope).toString()), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].categoryName").value("試練費目（削除済み費目）"));
        JsonNode after = data(get(base(scope) + "/summary"));
        assertThat(after.path("totalBudget")).isEqualTo(before.path("totalBudget"));
        assertThat(after.path("totalExpense")).isEqualTo(before.path("totalExpense"));
        assertThat(after.path("categories").size()).isEqualTo(1);
        assertThat(after.path("categories").get(0).path("actualAmount").decimalValue()).isEqualByComparingTo("300");
        http(get(base(scope) + "/categories/" + category + "/summary"), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryName").value("試練費目（削除済み費目）"));
        String csv = http(get(base(scope) + "/export"), admin).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("試練費目（削除済み費目）", "EXPENSE,300");
        http(post("/api/v1/budget/transactions/{id}/reverse", transaction).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"取消試練\"}"), admin).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.categoryName").value("試練費目（削除済み費目）"))
                .andExpect(jsonPath("$.data.amount").value(300)).andExpect(jsonPath("$.data.transactionType").value("INCOME"));
    }

    @Test
    void 削除済み費目は新規配分取引親指定から拒否され無履歴の削除済み費目は集計に出ない() throws Exception {
        Long category = category("TEAM", null);
        remove(category, "TEAM", admin).andExpect(status().isNoContent());
        http(put(base("TEAM") + "/allocations").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                Map.of("fiscalYearId", teamYear, "allocations", List.of(Map.of("categoryId", category, "month", 6, "amount", 10))))), admin)
                .andExpect(status().isNotFound());
        http(post(base("TEAM") + "/transactions").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(
                Map.of("fiscalYearId", teamYear, "categoryId", category, "transactionType", "EXPENSE", "amount", 10,
                        "transactionDate", "2026-06-01", "description", "新規拒否"))), admin).andExpect(status().isNotFound());
        http(post(base("TEAM") + "/categories").param("scopeId", team.getId().toString()).param("scopeType", "TEAM")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("fiscalYearId", teamYear,
                        "name", "子費目", "categoryType", "EXPENSE", "parentId", category))), admin).andExpect(status().isNotFound());
        assertThat(data(get(base("TEAM") + "/summary")).path("categories").size()).isZero();
        assertThat(tx(() -> transactions.countByFiscalYearId(teamYear))).isZero();
        assertThat(tx(() -> allocations.findByFiscalYearId(teamYear))).isEmpty();
    }

    @Test
    void 生存子費目がある親は400で保持し子削除後に親を削除できる() throws Exception {
        Long parent = category("TEAM", null); Long child = category("TEAM", parent);
        remove(parent, "TEAM", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("子費目が存在するため削除できません"));
        assertThat(tx(() -> categories.findById(parent))).isPresent();
        remove(child, "TEAM", admin).andExpect(status().isNoContent());
        remove(parent, "TEAM", admin).andExpect(status().isNoContent());
        assertDeleted(parent); assertDeleted(child);
    }

    @ParameterizedTest
    @CsvSource({"TEAM,anonymous,401", "TEAM,member,403", "TEAM,foreign,404", "ORGANIZATION,anonymous,401", "ORGANIZATION,member,403", "ORGANIZATION,foreign,404"})
    void 不正主体は削除できず物理行を保持する(String scope, String actor, int expected) throws Exception {
        Long category = category(scope, null);
        Long user = "anonymous".equals(actor) ? null : "member".equals(actor) ? member : foreignAdmin;
        MockHttpServletRequestBuilder request = delete("/api/v1/budget/categories/{id}", category)
                .param("scopeId", "foreign".equals(actor) ? ("TEAM".equals(scope) ? foreignTeam.getId() : foreignOrganization.getId()).toString() : scopeId(scope).toString())
                .param("scopeType", scope);
        http(request, user).andExpect(status().is(expected));
        assertThat(tx(() -> categories.findById(category))).isPresent();
    }

    @Test
    void 削除後の途中失敗は同じトランザクションで巻き戻る() {
        Long category = category("TEAM", null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin.toString(), null, List.of()));
        assertThatThrownBy(() -> tx(() -> { categoryService.delete(category, team.getId(), "TEAM"); em.flush();
            throw new IllegalStateException("途中失敗の試練"); })).isInstanceOf(IllegalStateException.class);
        assertThat(tx(() -> categories.findById(category))).isPresent();
    }

    @Test
    void 削除済み費目だけの年度も論理削除でき取引あり年度は拒否する() throws Exception {
        Long empty = category("TEAM", null);
        remove(empty, "TEAM", admin).andExpect(status().isNoContent());
        http(delete("/api/v1/budget/fiscal-years/{id}", teamYear), admin).andExpect(status().isNoContent());
        List<?> yearRows = tx(() -> em.createNativeQuery("SELECT deleted_at FROM budget_fiscal_years WHERE id=:id")
                .setParameter("id", teamYear).getResultList());
        assertThat(yearRows).hasSize(1);
        assertThat(yearRows.getFirst()).isNotNull();
        Long used = category("ORGANIZATION", null); transaction("ORGANIZATION", used);
        http(delete("/api/v1/budget/fiscal-years/{id}", orgYear), admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUDGET_020"));
        assertThat(tx(() -> years.findById(orgYear))).isPresent();
    }

    @Test
    void 費目別集計で他年度の費目名を取得できない() throws Exception {
        Long category = category("ORGANIZATION", null);
        http(get(base("TEAM") + "/categories/" + category + "/summary"), admin).andExpect(status().isNotFound());
    }

    @Test
    void 報告書CSVは削除済み費目の明細と総計を保持する() throws Exception {
        Long category = category("TEAM", null);
        transaction("TEAM", category);
        tx(() -> allocations.save(BudgetAllocationEntity.builder().fiscalYearId(teamYear).categoryId(category)
                .amount(new BigDecimal("1200")).build()));
        remove(category, "TEAM", admin).andExpect(status().isNoContent());
        java.util.concurrent.atomic.AtomicReference<byte[]> uploaded = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> { uploaded.set(invocation.getArgument(1, byte[].class)); return null; })
                .when(storage).upload(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(byte[].class),
                        org.mockito.ArgumentMatchers.eq("text/csv"));
        JsonNode report = mapper.readTree(http(post(base("TEAM") + "/reports").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("fiscalYearId", teamYear, "reportType", "ANNUAL"))), admin)
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).path("data");
        assertThat(tx(() -> reports.findById(report.path("id").asLong()).orElseThrow().getStatus()))
                .isEqualTo(BudgetReportStatus.COMPLETED);
        assertThat(uploaded.get()).isNotNull();
        String csv = new String(uploaded.get(), StandardCharsets.UTF_8);
        String[] detail = csv.lines().filter(line -> line.startsWith("試練費目（削除済み費目）,EXPENSE,"))
                .findFirst().orElseThrow().split(",", -1);
        String[] total = csv.lines().filter(line -> line.startsWith("合計,,")).findFirst().orElseThrow().split(",", -1);
        assertThat(new BigDecimal(detail[2])).isEqualByComparingTo("1200");
        assertThat(new BigDecimal(detail[3])).isEqualByComparingTo("300");
        assertThat(new BigDecimal(total[2])).isEqualByComparingTo("1200");
        assertThat(new BigDecimal(total[3])).isEqualByComparingTo("300");
    }

    @Test
    void 前年度コピーは削除済み費目を含めず有効な親指定を維持する() throws Exception {
        Long deleted = category("TEAM", null);
        remove(deleted, "TEAM", admin).andExpect(status().isNoContent());
        Long active = category("TEAM", null);
        http(post(base("TEAM") + "/categories").param("scopeId", team.getId().toString()).param("scopeType", "TEAM")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("fiscalYearId", teamYear,
                        "name", "有効子", "categoryType", "EXPENSE", "parentId", active))), admin).andExpect(status().isCreated());
        // 他年度の親も新しい子の親には使用できない。
        http(post(base("ORGANIZATION") + "/categories").param("scopeId", organization.getId().toString()).param("scopeType", "ORGANIZATION")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("fiscalYearId", orgYear,
                        "name", "越境子", "categoryType", "EXPENSE", "parentId", active))), admin).andExpect(status().isNotFound());
        // コピー先の所有権・同scopeの既存境界を保持するため同scope年度を作り、finallyで所有行だけ片付ける。
        Long target = tx(() -> years.save(BudgetFiscalYearEntity.builder().scopeType("TEAM").scopeId(team.getId()).name("コピー先")
                .startDate(LocalDate.of(2027, 1, 1)).endDate(LocalDate.of(2027, 12, 31)).createdBy(admin).build()).getId());
        try {
            http(post("/api/v1/budget/fiscal-years/{target}/categories/copy-from/{source}", target, teamYear), admin)
                    .andExpect(status().isCreated());
            assertThat(tx(() -> categories.findByFiscalYearId(target))).hasSize(2);
        } finally {
            tx(() -> { em.createNativeQuery("DELETE FROM budget_categories WHERE fiscal_year_id=:id").setParameter("id", target).executeUpdate();
                years.deleteById(target); return null; });
        }
    }

    @Test
    void 配分と取引の一覧は費目数が増えても問い合わせ数が増えない() throws Exception {
        Long first = category("TEAM", null); transaction("TEAM", first);
        tx(() -> allocations.save(BudgetAllocationEntity.builder().fiscalYearId(teamYear).categoryId(first).amount(BigDecimal.TEN).build()));
        org.hibernate.stat.Statistics stats = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous = stats.isStatisticsEnabled(); stats.setStatisticsEnabled(true);
        try {
            long smallAlloc = statementCount(get(base("TEAM") + "/allocations"), stats);
            String path = "/api/v1/teams/" + team.getSlug() + "/budget/transactions";
            long smallTx = statementCount(get(path).param("fiscalYearId", teamYear.toString()).param("size", "100"), stats);
            for (int i = 0; i < 12; i++) {
                Long category = category("TEAM", null); transaction("TEAM", category);
                tx(() -> allocations.save(BudgetAllocationEntity.builder().fiscalYearId(teamYear).categoryId(category).amount(BigDecimal.TEN).build()));
            }
            assertThat(statementCount(get(base("TEAM") + "/allocations"), stats)).isLessThanOrEqualTo(smallAlloc + 1);
            assertThat(statementCount(get(path).param("fiscalYearId", teamYear.toString()).param("size", "100"), stats))
                    .isLessThanOrEqualTo(smallTx + 1);
        } finally { stats.setStatisticsEnabled(previous); }
    }

    private long statementCount(MockHttpServletRequestBuilder request, org.hibernate.stat.Statistics stats) throws Exception {
        long start = stats.getPrepareStatementCount(); http(request, admin).andExpect(status().isOk());
        return stats.getPrepareStatementCount() - start;
    }

    @Test
    void 論理削除済み取引履歴がある年度も削除を拒否する() throws Exception {
        Long category = category("TEAM", null);
        Long transaction = transaction("TEAM", category);
        tx(() -> { BudgetTransactionEntity row = transactions.findById(transaction).orElseThrow();
            row.softDelete(); transactions.save(row); return null; });
        assertThat(tx(() -> transactions.findById(transaction))).isEmpty();
        http(delete("/api/v1/budget/fiscal-years/{id}", teamYear), admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BUDGET_020"));
        assertThat(tx(() -> years.findById(teamYear))).isPresent();
    }

    private Long category(String scope, Long parent) {
        return tx(() -> categories.save(BudgetCategoryEntity.builder().fiscalYearId(yearId(scope)).parentId(parent)
                .name("試練費目").categoryType(BudgetCategoryType.EXPENSE).build()).getId());
    }
    private Long transaction(String scope, Long category) {
        return tx(() -> transactions.save(BudgetTransactionEntity.builder().fiscalYearId(yearId(scope)).categoryId(category)
                .scopeId(scopeId(scope)).scopeType(scope).transactionType(BudgetTransactionType.EXPENSE).amount(new BigDecimal("300"))
                .transactionDate(LocalDate.of(2026, 6, 1)).title("支出試練").recordedBy(admin).build()).getId());
    }
    private void assertDeleted(Long id) {
        List<?> rows = tx(() -> em.createNativeQuery("SELECT deleted_at FROM budget_categories WHERE id=:id")
                .setParameter("id", id).getResultList());
        assertThat(rows).as("論理削除後も費目の物理行を保持する").hasSize(1);
        assertThat(rows.getFirst()).isNotNull();
        assertThat(tx(() -> categories.findById(id))).isEmpty();
    }
    private ResultActions remove(Long category, String scope, Long actor) throws Exception {
        return http(delete("/api/v1/budget/categories/{id}", category).param("scopeId", scopeId(scope).toString()).param("scopeType", scope), actor);
    }
    private JsonNode data(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(http(request, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }
    private ResultActions http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
        return mvc.perform(request);
    }
    private <T> T tx(Supplier<T> work) { return new TransactionTemplate(txManager).execute(status -> work.get()); }
    private String base(String scope) { return "/api/v1/budget/fiscal-years/" + yearId(scope); }
    private Long yearId(String scope) { return "TEAM".equals(scope) ? teamYear : orgYear; }
    private Long scopeId(String scope) { return "TEAM".equals(scope) ? team.getId() : organization.getId(); }
    private Long user(String label) {
        return users.save(UserEntity.builder().email("budget-delete-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }
    private Long year(String scope) {
        return years.save(BudgetFiscalYearEntity.builder().scopeType(scope).scopeId(scopeId(scope)).name("削除試練年度")
                .startDate(LocalDate.of(2026, 1, 1)).endDate(LocalDate.of(2026, 12, 31)).createdBy(admin).build()).getId();
    }
    private void membership(Long user, String scope, Long id, boolean isAdmin) {
        MembershipTestHelper.insertMembership(em, user, ScopeType.valueOf(scope), id, RoleKind.MEMBER);
        if (isAdmin) MembershipTestHelper.insertUserRole(em, user, "ADMIN", "TEAM".equals(scope) ? id : null, "ORGANIZATION".equals(scope) ? id : null);
    }
}

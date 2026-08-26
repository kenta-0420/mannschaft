package com.mannschaft.app.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.budget.entity.BudgetCategoryEntity;
import com.mannschaft.app.budget.entity.BudgetFiscalYearEntity;
import com.mannschaft.app.budget.entity.BudgetReportEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionAttachmentEntity;
import com.mannschaft.app.budget.entity.BudgetTransactionEntity;
import com.mannschaft.app.budget.repository.BudgetCategoryRepository;
import com.mannschaft.app.budget.repository.BudgetFiscalYearRepository;
import com.mannschaft.app.budget.repository.BudgetReportRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionAttachmentRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B9: budget flat経路（BudgetTransactionController/BudgetCategoryController/
 * BudgetReportController）API 契約テスト（試練）。
 *
 * <p>正本: 早馬（殿からの直接指示・Wave3-B9依頼文）。{@code AccessControlService}
 * （{@code checkMembership}/{@code checkAdminOrAbove}/{@code isMember}）。金型:
 * {@code SupporterScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL）。</p>
 *
 * <p>認可モデル（budget flat 経路固有の設計判断）:</p>
 * <ul>
 *   <li><b>scopeId/scopeType が query param として明示される EP</b>
 *       （transaction getById/delete、category create/update/delete）: entity（または
 *       その親＝fiscalYear）由来の真の scope とクライアント指定値が一致するか検証し、
 *       不一致は存在秘匿のため 404。一致すれば checkMembership/checkAdminOrAbove で 403。</li>
 *   <li><b>scope を宣言する query param を持たない「ID 直指定」EP</b>
 *       （添付ファイル3種、category copyFromPreviousYear、report getDownloadUrl）:
 *       {@code isMember}/{@code isAdminOrAbove} で判定し、非所属は 404（存在秘匿）、
 *       所属だが権限不足は 403（incident ドメイン踏襲）。</li>
 * </ul>
 *
 * <p>BOLA親子鎖突合: category→fiscalYear→scope（copyFromPreviousYear の source/target
 * 同一scope確認を含む）、attachment→transaction→scope を明示的に検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("budget flat経路 API 契約テスト（認可根治 Wave3-B9）")
class BudgetFlatWriteScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BudgetFiscalYearRepository fiscalYearRepository;

    @Autowired
    private BudgetCategoryRepository categoryRepository;

    @Autowired
    private BudgetTransactionRepository transactionRepository;

    @Autowired
    private BudgetTransactionAttachmentRepository attachmentRepository;

    @Autowired
    private BudgetReportRepository reportRepository;

    /**
     * 認可根治戦役 Wave3-B9: budget の StorageService インターフェース注入先である
     * 具象 bean は R2StorageService（bean名 r2StorageService）である。ここを interface 型
     * {@code @MockitoBean StorageService} で置換すると bean が StorageService$MockitoMock 型に
     * すり替わり、同一 context 内で具象 {@code R2StorageService} を注入する
     * {@code StoragePathMigrationBatchService} の DI が型不一致で壊れて context 起動が失敗する。
     * 具象型でモックすれば interface 消費者(budget)・具象型消費者(migration)の双方を満たす。
     */
    @MockitoBean
    private R2StorageService storageService;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;   // teamA の ADMIN（正当）
    private Long adminBId;   // teamB の ADMIN（別scope越境攻撃者）
    private Long memberAId;  // teamA の非ADMINメンバー
    private Long outsiderId; // どこにも所属しない非メンバー

    private BudgetFiscalYearEntity fyA;
    private BudgetFiscalYearEntity fyA2; // teamA の第2会計年度（copyFromPreviousYear のコピー先用）
    private BudgetFiscalYearEntity fyB;

    private BudgetCategoryEntity categoryA;
    private BudgetCategoryEntity categoryADeletable; // 子を持たない削除専用カテゴリ

    private BudgetTransactionEntity transactionA;
    private BudgetTransactionAttachmentEntity attachmentA;

    private BudgetReportEntity reportA;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("BGT認可契約チームA");
        teamBId = insertTeam("BGT認可契約チームB");

        adminAId = insertUser("bgt-authz-admin-a@example.com");
        adminBId = insertUser("bgt-authz-admin-b@example.com");
        memberAId = insertUser("bgt-authz-member-a@example.com");
        outsiderId = insertUser("bgt-authz-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        fyA = fiscalYearRepository.save(BudgetFiscalYearEntity.builder()
                .name("BGT認可年度A").startDate(LocalDate.of(2026, 1, 1)).endDate(LocalDate.of(2026, 12, 31))
                .scopeId(teamAId).scopeType("TEAM").status(BudgetFiscalYearStatus.OPEN)
                .createdBy(adminAId).build());
        fyA2 = fiscalYearRepository.save(BudgetFiscalYearEntity.builder()
                .name("BGT認可年度A2").startDate(LocalDate.of(2027, 1, 1)).endDate(LocalDate.of(2027, 12, 31))
                .scopeId(teamAId).scopeType("TEAM").status(BudgetFiscalYearStatus.OPEN)
                .createdBy(adminAId).build());
        fyB = fiscalYearRepository.save(BudgetFiscalYearEntity.builder()
                .name("BGT認可年度B").startDate(LocalDate.of(2026, 1, 1)).endDate(LocalDate.of(2026, 12, 31))
                .scopeId(teamBId).scopeType("TEAM").status(BudgetFiscalYearStatus.OPEN)
                .createdBy(adminBId).build());

        categoryA = categoryRepository.save(BudgetCategoryEntity.builder()
                .fiscalYearId(fyA.getId()).name("BGT認可費目A").categoryType(BudgetCategoryType.EXPENSE)
                .sortOrder(0).build());
        categoryADeletable = categoryRepository.save(BudgetCategoryEntity.builder()
                .fiscalYearId(fyA.getId()).name("BGT認可費目A削除用").categoryType(BudgetCategoryType.EXPENSE)
                .sortOrder(1).build());

        transactionA = transactionRepository.save(BudgetTransactionEntity.builder()
                .fiscalYearId(fyA.getId()).categoryId(categoryA.getId())
                .scopeType("TEAM").scopeId(teamAId)
                .transactionType(BudgetTransactionType.EXPENSE).amount(new BigDecimal("1000"))
                .transactionDate(LocalDate.of(2026, 4, 1)).title("BGT認可取引A")
                .approvalStatus(BudgetApprovalStatus.APPROVED).recordedBy(adminAId).build());

        attachmentA = attachmentRepository.save(BudgetTransactionAttachmentEntity.builder()
                .transactionId(transactionA.getId()).fileKey("budget/attachments/existing.pdf")
                .originalFilename("existing.pdf").fileSize(100L).mimeType("application/pdf").build());

        reportA = reportRepository.save(BudgetReportEntity.builder()
                .fiscalYearId(fyA.getId()).scopeType("TEAM").scopeId(teamAId)
                .reportType(BudgetReportType.MONTHLY)
                .periodStart(LocalDate.of(2026, 4, 1)).periodEnd(LocalDate.of(2026, 4, 30))
                .status(BudgetReportStatus.COMPLETED).fileKey("budget/reports/existing.csv")
                .generatedBy(adminAId).build());

        given(storageService.generateUploadUrl(anyString(), anyString(), any(Duration.class)))
                .willReturn(new PresignedUploadResult("https://mock-upload.example/put", "budget/attachments/mock.pdf", 900));
        given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .willReturn("https://mock-download.example/get");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /api/v1/budget/transactions/{id}（getById・scope宣言型+entity突合）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 取引取得(getById)")
    class TransactionGetById {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/budget/transactions/{id}", transactionA.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境ID: 別チームADMINが自チームIDを詐称して取得すると404（BOLA存在秘匿）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/budget/transactions/{id}", transactionA.getId())
                            .param("scopeId", teamBId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_009"));
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/budget/transactions/{id}", transactionA.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(transactionA.getId()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. DELETE /api/v1/budget/transactions/{id}（delete・scope宣言型+entity突合）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 取引削除(delete)")
    class TransactionDelete {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/budget/transactions/{id}", transactionA.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境ID: 別チームADMINが自チームIDを詐称して削除すると404（BOLA）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/budget/transactions/{id}", transactionA.getId())
                            .param("scopeId", teamBId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_009"));
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/budget/transactions/{id}", transactionA.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 添付ファイル3種（ID直指定EP・isMember-conceal + checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 添付ファイル(upload-url/register/delete)")
    class TransactionAttachments {

        @Test
        @DisplayName("非ADMINメンバーのアップロードURL取得は403")
        void 非ADMINのアップロードURLは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/budget/transactions/{id}/upload-url", transactionA.getId())
                            .param("fileName", "receipt.pdf")
                            .param("contentType", "application/pdf"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非所属(越境)ADMINのアップロードURL取得は404（BOLA存在秘匿）")
        void 非所属のアップロードURLは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/budget/transactions/{id}/upload-url", transactionA.getId())
                            .param("fileName", "receipt.pdf")
                            .param("contentType", "application/pdf"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_009"));
        }

        @Test
        @DisplayName("正当ADMINのアップロードURL取得は200")
        void 正当ADMINのアップロードURLは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/budget/transactions/{id}/upload-url", transactionA.getId())
                            .param("fileName", "receipt.pdf")
                            .param("contentType", "application/pdf"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.uploadUrl").exists());
        }

        @Test
        @DisplayName("非ADMINメンバーの添付登録は403")
        void 非ADMINの添付登録は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/budget/transactions/{id}/attachments", transactionA.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerAttachmentBody(transactionA.getId()))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非所属(越境)ADMINの添付登録は404（BOLA存在秘匿）")
        void 非所属の添付登録は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/budget/transactions/{id}/attachments", transactionA.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerAttachmentBody(transactionA.getId()))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_009"));
        }

        @Test
        @DisplayName("正当ADMINの添付登録は201")
        void 正当ADMINの添付登録は201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/budget/transactions/{id}/attachments", transactionA.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerAttachmentBody(transactionA.getId()))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非ADMINメンバーの添付削除は403")
        void 非ADMINの添付削除は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/budget/transactions/{id}/attachments/{attachmentId}",
                            transactionA.getId(), attachmentA.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非所属(越境)ADMINの添付削除は404（BOLA: attachment→transaction→scope突合）")
        void 非所属の添付削除は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/budget/transactions/{id}/attachments/{attachmentId}",
                            transactionA.getId(), attachmentA.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_009"));
        }

        @Test
        @DisplayName("正当ADMINの添付削除は204")
        void 正当ADMINの添付削除は204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/budget/transactions/{id}/attachments/{attachmentId}",
                            transactionA.getId(), attachmentA.getId()))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /api/v1/budget/fiscal-years/{fiscalYearId}/categories（create・scope宣言型+FY突合）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. カテゴリ作成(create)")
    class CategoryCreate {

        @Test
        @DisplayName("非ADMINメンバーの作成は403")
        void 非ADMINの作成は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/budget/fiscal-years/{fyId}/categories", fyA.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createCategoryBody(fyA.getId()))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境: 別チームADMINが自チームscopeIdを詐称してfyAにカテゴリ作成すると404（BOLA）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/budget/fiscal-years/{fyId}/categories", fyA.getId())
                            .param("scopeId", teamBId.toString())
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createCategoryBody(fyA.getId()))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_003"));
        }

        @Test
        @DisplayName("正当ADMINの作成は201")
        void 正当ADMINの作成は201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/budget/fiscal-years/{fyId}/categories", fyA.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createCategoryBody(fyA.getId()))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. PATCH /api/v1/budget/categories/{id}（update・scope宣言型+親(FY)突合）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. カテゴリ更新(update)")
    class CategoryUpdate {

        @Test
        @DisplayName("非ADMINメンバーの更新は403")
        void 非ADMINの更新は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/budget/categories/{id}", categoryA.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateCategoryBody("乗っ取り更新"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境ID: 別チームADMINが自チームscopeIdを詐称して更新すると404（BOLA: category→fy→scope突合）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/budget/categories/{id}", categoryA.getId())
                            .param("scopeId", teamBId.toString())
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateCategoryBody("乗っ取り更新"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_006"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/budget/categories/{id}", categoryA.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateCategoryBody("正規更新"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("正規更新"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. DELETE /api/v1/budget/categories/{id}（delete・scope宣言型+親(FY)突合）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. カテゴリ削除(delete)")
    class CategoryDelete {

        @Test
        @DisplayName("非ADMINメンバーの削除は403")
        void 非ADMINの削除は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/budget/categories/{id}", categoryADeletable.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("越境ID: 別チームADMINが自チームscopeIdを詐称して削除すると404（BOLA）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/budget/categories/{id}", categoryADeletable.getId())
                            .param("scopeId", teamBId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_006"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/budget/categories/{id}", categoryADeletable.getId())
                            .param("scopeId", teamAId.toString())
                            .param("scopeType", "TEAM"))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. POST .../categories/copy-from/{sourceFiscalYearId}（ID直指定EP・isMember-conceal＋同一scope確認）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. カテゴリ前年度コピー(copyFromPreviousYear)")
    class CategoryCopyFromPreviousYear {

        @Test
        @DisplayName("非ADMINメンバーのコピーは403")
        void 非ADMINのコピーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/budget/fiscal-years/{targetId}/categories/copy-from/{sourceId}",
                            fyA2.getId(), fyA.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非所属(越境)ADMINのコピーは404（BOLA存在秘匿・target非所属）")
        void 非所属のコピーは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/budget/fiscal-years/{targetId}/categories/copy-from/{sourceId}",
                            fyA2.getId(), fyA.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_003"));
        }

        @Test
        @DisplayName("BOLA親子鎖: targetは自チーム(ADMIN)だがsourceが他チームのfyだと404（source/target scope不一致）")
        void source他チーム年度は404() throws Exception {
            setAuth(adminAId);
            // target=fyA2(teamA・正当ADMIN) だが source=fyB(teamB) を指定 → 他scopeのカテゴリ構成を盗用しようとする攻撃
            mockMvc.perform(post("/api/v1/budget/fiscal-years/{targetId}/categories/copy-from/{sourceId}",
                            fyA2.getId(), fyB.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_003"));
        }

        @Test
        @DisplayName("正当ADMINの同一scope内コピーは201")
        void 正当ADMINのコピーは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/budget/fiscal-years/{targetId}/categories/copy-from/{sourceId}",
                            fyA2.getId(), fyA.getId()))
                    .andExpect(status().isCreated());

            List<BudgetCategoryEntity> copied = categoryRepository.findByFiscalYearId(fyA2.getId());
            org.assertj.core.api.Assertions.assertThat(copied).isNotEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET /api/v1/budget/reports/{id}/download-url（ID直指定EP・isMember-conceal）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. 報告書ダウンロードURL取得(getDownloadUrl)")
    class ReportGetDownloadUrl {

        @Test
        @DisplayName("非メンバーは404（BOLA存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/budget/reports/{id}/download-url", reportA.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_010"));
        }

        @Test
        @DisplayName("越境(別チーム所属)は404（BOLA存在秘匿）")
        void 越境は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/budget/reports/{id}/download-url", reportA.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("BUDGET_010"));
        }

        @Test
        @DisplayName("正当メンバー(ADMIN不要)は200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/budget/reports/{id}/download-url", reportA.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.downloadUrl").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> registerAttachmentBody(Long transactionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", transactionId);
        body.put("fileName", "receipt.pdf");
        body.put("fileType", "application/pdf");
        body.put("fileSize", 1024);
        body.put("s3Key", "budget/attachments/mock.pdf");
        return body;
    }

    private Map<String, Object> createCategoryBody(Long fiscalYearId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fiscalYearId", fiscalYearId);
        body.put("name", "新規費目");
        body.put("categoryType", "EXPENSE");
        body.put("parentId", null);
        body.put("sortOrder", 0);
        body.put("description", null);
        return body;
    }

    private Map<String, Object> updateCategoryBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("sortOrder", 1);
        body.put("description", null);
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
                                + "VALUES (:email, 'BGT契約', 'テスト', 'BGT契約テスト', 'ACTIVE', "
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
                                + "CONCAT('bgt-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}

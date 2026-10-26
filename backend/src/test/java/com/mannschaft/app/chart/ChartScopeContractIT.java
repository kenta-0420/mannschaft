package com.mannschaft.app.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.chart.entity.ChartCustomFieldEntity;
import com.mannschaft.app.chart.entity.ChartFormulaEntity;
import com.mannschaft.app.chart.entity.ChartPhotoEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.entity.ChartRecordTemplateEntity;
import com.mannschaft.app.chart.repository.ChartCustomFieldRepository;
import com.mannschaft.app.chart.repository.ChartFormulaRepository;
import com.mannschaft.app.chart.repository.ChartPhotoRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.repository.ChartRecordTemplateRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 2 トランシェ2B — chart ドメイン（F07.4 カルテ：要配慮個人情報＝健康記録・
 * アレルギー・施術写真）API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} トランシェ2B chart 節。
 * ドメイン全体（6 Controller / 6 Service）で {@code AccessControlService} が未使用であり、
 * teamId を跨いだ IDOR（BOLA）が成立していた（他チームのカルテ・問診票・薬剤レシピ・
 * 写真・設定の閲覧/改変/削除）。</p>
 *
 * <p>金型: {@code ServiceRecordScopeContractIT} / {@code PerformanceScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext）。
 * Spring Security フィルタは無効化するが、越境 403/404 は {@code AccessControlService} の
 * アプリケーション層例外（{@code COMMON_002} → 403 / {@code CHART_xxx NOT_FOUND} → 404）
 * として発生するためフィルタ無効でも検証できる。未認証は {@code SecurityUtils.getCurrentUserId()}
 * の {@code COMMON_000} → 401（status のみ検証）。</p>
 *
 * <p><b>4象限＋BOLA秘匿</b>: 非メンバー（outsider・403）/ 別 scope ADMIN（teamB の ADMIN が
 * teamA の URL を叩く越境・403）/ 非 ADMIN メンバー（閲覧系 200・変更系 403）/ 正当 ADMIN（成功）。
 * さらに「teamB ADMIN が自チームの URL（path teamId=teamB）で teamA 配下の entity ID を指定する」
 * 横アクセスは {@code findByIdAndTeamId} 不一致 → CHART_xxx NOT_FOUND → <b>404 で存在秘匿</b>
 * されることを 6 Controller の代表エンドポイントで検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("chart ドメイン（カルテ）認可契約テスト（試練）")
class ChartScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChartRecordRepository chartRecordRepository;

    @Autowired
    private ChartFormulaRepository chartFormulaRepository;

    @Autowired
    private ChartPhotoRepository chartPhotoRepository;

    @Autowired
    private ChartCustomFieldRepository chartCustomFieldRepository;

    @Autowired
    private ChartRecordTemplateRepository chartRecordTemplateRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;    // TEAM A の ADMIN（正当）
    private Long adminBId;    // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberAId;   // TEAM A の非 ADMIN メンバー
    private Long outsiderId;  // どこにも所属しない非メンバー
    private Long customerAId; // TEAM A のカルテの顧客（customer_user_id 用）

    private Long chartAId;    // TEAM A のカルテ
    private Long formulaAId;  // TEAM A のカルテに属する薬剤レシピ
    private Long photoAId;    // TEAM A のカルテに属する写真
    private Long customFieldAId; // TEAM A のカスタムフィールド
    private Long templateAId;    // TEAM A のカルテテンプレート

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CHARTAUTHZ チームA");
        teamBId = insertTeam("CHARTAUTHZ チームB");

        adminAId = insertUser("chartauthz-admin-a@example.com");
        adminBId = insertUser("chartauthz-admin-b@example.com");
        memberAId = insertUser("chartauthz-member-a@example.com");
        outsiderId = insertUser("chartauthz-outsider@example.com");
        customerAId = insertUser("chartauthz-customer@example.com");

        // ADMIN 判定（checkAdminOrAbove）は user_roles、所属判定（checkMembership）は
        // memberships のみを見る別系統のため、ADMIN にも memberships 行を張る
        // （RepairPlanAuthorizationMatrixTest / ServiceRecordScopeContractIT 踏襲）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId / customerAId はどのチームにも所属させない。

        ChartRecordEntity chartA = chartRecordRepository.save(ChartRecordEntity.builder()
                .teamId(teamAId)
                .customerUserId(customerAId)
                .staffUserId(adminAId)
                .visitDate(LocalDate.now())
                .chiefComplaint("CHARTAUTHZ 主訴")
                .build());
        chartAId = chartA.getId();

        ChartFormulaEntity formulaA = chartFormulaRepository.save(ChartFormulaEntity.builder()
                .chartRecordId(chartAId)
                .productName("CHARTAUTHZ カラー剤")
                .build());
        formulaAId = formulaA.getId();

        ChartPhotoEntity photoA = chartPhotoRepository.save(ChartPhotoEntity.builder()
                .chartRecordId(chartAId)
                .photoType("BEFORE")
                .s3Key("charts/test/chartauthz-photo.jpg")
                .originalFilename("chartauthz-photo.jpg")
                .fileSizeBytes(1000)
                .contentType("image/jpeg")
                .build());
        photoAId = photoA.getId();

        ChartCustomFieldEntity customFieldA = chartCustomFieldRepository.save(ChartCustomFieldEntity.builder()
                .teamId(teamAId)
                .fieldName("CHARTAUTHZ 体重")
                .fieldType("NUMBER")
                .build());
        customFieldAId = customFieldA.getId();

        ChartRecordTemplateEntity templateA = chartRecordTemplateRepository.save(ChartRecordTemplateEntity.builder()
                .teamId(teamAId)
                .templateName("CHARTAUTHZ テンプレート")
                .build());
        templateAId = templateA.getId();

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. カルテ一覧（閲覧系: checkMembership）＋未認証401
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/charts（カルテ一覧）")
    class ListCharts {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts", teamAId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. カルテ作成（変更系: checkAdminOrAbove・作成先スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/charts（カルテ作成）")
    class CreateChart {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createChartBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createChartBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createChartBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createChartBody())))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. カルテ詳細（閲覧系・entity由来: checkMembership、BOLA秘匿404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /teams/{teamId}/charts/{id}（カルテ詳細）")
    class GetChart {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAのURLを叩く越境）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自チームURLでteamAのchartIdを指定→404で存在秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}", teamBId, chartAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. カルテ更新（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT /teams/{teamId}/charts/{id}（カルテ更新）")
    class UpdateChart {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateChartBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateChartBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateChartBody())))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. カルテ削除（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. DELETE /teams/{teamId}/charts/{id}（カルテ削除）")
    class DeleteChart {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/{id}", teamAId, chartAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. カルテコピー・共有・ピン留め（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST copy / PATCH share / PATCH pin")
    class CopyShareAndPin {

        @Test
        @DisplayName("非ADMINメンバーはコピー403")
        void 非ADMINメンバーはコピー403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts/{id}/copy", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINはコピー201")
        void 正当ADMINはコピー201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts/{id}/copy", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非ADMINメンバーは共有変更403")
        void 非ADMINメンバーは共有変更403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/charts/{id}/share", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isSharedToCustomer", true))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは共有変更200")
        void 正当ADMINは共有変更200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/charts/{id}/share", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isSharedToCustomer", true))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別scope ADMINはピン留め403")
        void 別scopeADMINはピン留め403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/charts/{id}/pin", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isPinned", true))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINはピン留め200")
        void 正当ADMINはピン留め200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/charts/{id}/pin", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("isPinned", true))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PDF・顧客別一覧・経過グラフ（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET pdf / customer/{userId} / customer/{userId}/progress")
    class PdfAndCustomerViews {

        @Test
        @DisplayName("非メンバーはPDF403")
        void 非メンバーはPDF403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}/pdf", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINはPDF403")
        void 別scopeADMINはPDF403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}/pdf", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非メンバーは顧客別一覧403")
        void 非メンバーは顧客別一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/customer/{userId}", teamAId, customerAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは顧客別一覧403")
        void 別scopeADMINは顧客別一覧403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/customer/{userId}", teamAId, customerAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは顧客別一覧200")
        void 非ADMINメンバーは顧客別一覧200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/customer/{userId}", teamAId, customerAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーは経過グラフ403")
        void 非メンバーは経過グラフ403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/customer/{userId}/progress", teamAId, customerAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは経過グラフ200")
        void 非ADMINメンバーは経過グラフ200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/customer/{userId}/progress", teamAId, customerAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. 身体チャート（変更系・entity由来: checkAdminOrAbove、BOLA秘匿404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PUT /teams/{teamId}/charts/{id}/body-marks（身体チャート一括更新）")
    class BodyMarks {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}/body-marks", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bodyMarksBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}/body-marks", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bodyMarksBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自チームURLでteamAのchartIdを指定→404で存在秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}/body-marks", teamBId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bodyMarksBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}/body-marks", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bodyMarksBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> bodyMarksBody() {
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("bodyPart", "FRONT");
            mark.put("xPosition", new BigDecimal("10.00"));
            mark.put("yPosition", new BigDecimal("20.00"));
            mark.put("markType", "PAIN");
            mark.put("severity", 3);
            return Map.of("marks", List.of(mark));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. 薬剤レシピ（閲覧: checkMembership / 変更: checkAdminOrAbove、BOLA秘匿404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. 薬剤レシピ list/create/update/delete")
    class Formulas {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}/formulas", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは一覧403")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}/formulas", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは一覧200")
        void 非ADMINメンバーは一覧200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}/formulas", teamAId, chartAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは追加403")
        void 非ADMINメンバーは追加403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts/{id}/formulas", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("productName", "新カラー剤"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは追加201")
        void 正当ADMINは追加201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts/{id}/formulas", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("productName", "新カラー剤"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("別scope ADMIN（teamAのURL）は更新403")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/formulas/{formulaId}", teamAId, formulaAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("productName", "改ざん"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自チームURLでteamAのformulaIdを指定→404で存在秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/formulas/{formulaId}", teamBId, formulaAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("productName", "改ざん"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/formulas/{formulaId}", teamAId, formulaAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("productName", "更新後カラー剤"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは削除403")
        void 非ADMINメンバーは削除403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/formulas/{formulaId}", teamAId, formulaAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/formulas/{formulaId}", teamAId, formulaAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. 問診票（閲覧: checkMembership / 変更: checkAdminOrAbove、BOLA秘匿404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. GET/PUT /teams/{teamId}/charts/{id}/intake-form（問診票）")
    class IntakeForm {

        @Test
        @DisplayName("非メンバーは取得403")
        void 非メンバーは取得403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}/intake-form", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは取得403")
        void 別scopeADMINは取得403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}/intake-form", teamAId, chartAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは取得200")
        void 非ADMINメンバーは取得200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/{id}/intake-form", teamAId, chartAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは更新403")
        void 非ADMINメンバーは更新403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}/intake-form", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(intakeFormBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自チームURLでteamAのchartIdを指定→404で存在秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}/intake-form", teamBId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(intakeFormBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/{id}/intake-form", teamAId, chartAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(intakeFormBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> intakeFormBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("formType", "INTAKE");
            body.put("content", "{\"answers\":[]}");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. カルテ写真（変更系: checkAdminOrAbove、BOLA秘匿404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. POST /charts/{id}/photos / DELETE /charts/photos/{photoId}（写真）")
    class Photos {

        @Test
        @DisplayName("非ADMINメンバーはアップロード403")
        void 非ADMINメンバーはアップロード403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(multipart("/api/v1/teams/{teamId}/charts/{id}/photos", teamAId, chartAId)
                            .file(new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                            .param("photo_type", "BEFORE"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINはアップロード403")
        void 別scopeADMINはアップロード403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(multipart("/api/v1/teams/{teamId}/charts/{id}/photos", teamAId, chartAId)
                            .file(new MockMultipartFile("file", "test.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                            .param("photo_type", "BEFORE"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは削除403")
        void 非ADMINメンバーは削除403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/photos/{photoId}", teamAId, photoAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自チームURLでteamAのphotoIdを指定→404で存在秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/photos/{photoId}", teamBId, photoAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/photos/{photoId}", teamAId, photoAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. セクション設定（閲覧: checkMembership / 変更: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. GET/PUT /charts/settings/sections（セクション設定）")
    class SectionSettings {

        @Test
        @DisplayName("非メンバーは取得403")
        void 非メンバーは取得403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/settings/sections", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは取得403")
        void 別scopeADMINは取得403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/settings/sections", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは取得200")
        void 非ADMINメンバーは取得200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/settings/sections", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは更新403")
        void 非ADMINメンバーは更新403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/sections", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sectionsBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは更新403")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/sections", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sectionsBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/sections", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sectionsBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> sectionsBody() {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("sectionType", "ALLERGY");
            section.put("isEnabled", true);
            return Map.of("sections", List.of(section));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. カスタムフィールド（閲覧: checkMembership / 変更: checkAdminOrAbove、BOLA秘匿404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. カスタムフィールド list/create/update/deactivate")
    class CustomFields {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/settings/custom-fields", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは一覧200")
        void 非ADMINメンバーは一覧200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/settings/custom-fields", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは作成403")
        void 非ADMINメンバーは作成403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts/settings/custom-fields", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(customFieldBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは作成201")
        void 正当ADMINは作成201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts/settings/custom-fields", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(customFieldBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("別scope ADMIN（teamAのURL）は更新403")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/custom-fields/{id}",
                            teamAId, customFieldAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(customFieldBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自チームURLでteamAのfieldIdを指定→404で存在秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/custom-fields/{id}",
                            teamBId, customFieldAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(customFieldBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/custom-fields/{id}",
                            teamAId, customFieldAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(customFieldBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは無効化403")
        void 非ADMINメンバーは無効化403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/settings/custom-fields/{id}",
                            teamAId, customFieldAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは無効化204")
        void 正当ADMINは無効化204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/settings/custom-fields/{id}",
                            teamAId, customFieldAId))
                    .andExpect(status().isNoContent());
        }

        private Map<String, Object> customFieldBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fieldName", "CHARTAUTHZ 血圧");
            body.put("fieldType", "NUMBER");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. カルテテンプレート（閲覧: checkMembership / 変更: checkAdminOrAbove、BOLA秘匿404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. カルテテンプレート list/create/update/delete")
    class RecordTemplates {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/settings/record-templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは一覧200")
        void 非ADMINメンバーは一覧200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/charts/settings/record-templates", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは作成403")
        void 非ADMINメンバーは作成403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts/settings/record-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("templateName", "新テンプレート"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは作成201")
        void 正当ADMINは作成201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/charts/settings/record-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("templateName", "新テンプレート"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("別scope ADMIN（teamAのURL）は更新403")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/record-templates/{id}",
                            teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("templateName", "改ざん"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自チームURLでteamAのtemplateIdを指定→404で存在秘匿")
        void BOLAは404秘匿() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/record-templates/{id}",
                            teamBId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("templateName", "改ざん"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/charts/settings/record-templates/{id}",
                            teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("templateName", "更新後テンプレート"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは削除403")
        void 非ADMINメンバーは削除403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/settings/record-templates/{id}",
                            teamAId, templateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/charts/settings/record-templates/{id}",
                            teamAId, templateAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> createChartBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerUserId", customerAId);
        body.put("visitDate", LocalDate.now().toString());
        return body;
    }

    private Map<String, Object> updateChartBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitDate", LocalDate.now().toString());
        body.put("version", 0L);
        body.put("chiefComplaint", "更新後の主訴");
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'CHARTAUTHZ', 'テスト', 'CHARTAUTHZ テスト', 'ACTIVE', "
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
                                + "CONCAT('c-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}

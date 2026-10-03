package com.mannschaft.app.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceMetricTemplateEntity;
import com.mannschaft.app.performance.entity.PerformanceRecordEntity;
import com.mannschaft.app.performance.repository.PerformanceMetricRepository;
import com.mannschaft.app.performance.repository.PerformanceMetricTemplateRepository;
import com.mannschaft.app.performance.repository.PerformanceRecordRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 2 トランシェ2B — performance ドメイン（F07.2 パフォーマンス管理）
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} トランシェ2B performance 節。
 * ドメイン全体で {@code AccessControlService} が未使用であり、teamId を跨いだ IDOR（BOLA）が
 * 成立していた（他チームの指標定義閲覧・改変・成績捏造・成績CSVの窃取等）。</p>
 *
 * <p>金型: {@code TeamAdvertiserScopeContractIT} / {@code ServiceRecordScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext）。
 * Spring Security フィルタは無効化するが、越境 403/404 は {@code AccessControlService} の
 * アプリケーション層例外（{@code COMMON_002} → 403 / {@code PERF_xxx NOT_FOUND} → 404）として
 * 発生するためフィルタ無効でも検証できる。</p>
 *
 * <p><b>3象限</b>: 非メンバー/非ADMIN（403）・BOLA（別scope ADMIN による越境。path-scope権限は
 * あるが対象IDが別チーム配下 → entity 由来 scope 判定で 404 秘匿）・正当ADMIN/メンバー（成功）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("performance ドメイン（パフォーマンス管理）認可契約テスト（試練）")
class PerformanceScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PerformanceMetricRepository metricRepository;

    @Autowired
    private PerformanceRecordRepository recordRepository;

    @Autowired
    private PerformanceMetricTemplateRepository templateRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;   // TEAM A の ADMIN（正当）
    private Long adminBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberAId;  // TEAM A の非 ADMIN メンバー
    private Long outsiderId; // どこにも所属しない非メンバー

    private Long metricAId;   // TEAM A の指標（自己記録可）
    private Long recordAId;   // TEAM A の記録（memberA分）
    private Long scheduleAId; // TEAM A のスケジュール
    private Long scheduleBId; // TEAM B のスケジュール（BOLA検証用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("PERFAUTHZ チームA");
        teamBId = insertTeam("PERFAUTHZ チームB");

        adminAId = insertUser("perfauthz-admin-a@example.com");
        adminBId = insertUser("perfauthz-admin-b@example.com");
        memberAId = insertUser("perfauthz-member-a@example.com");
        outsiderId = insertUser("perfauthz-outsider@example.com");

        // ADMIN 判定（checkAdminOrAbove）は user_roles、所属判定（checkMembership）は
        // memberships のみを見る別系統のため、ADMIN にも memberships 行を張る
        // （RepairPlanAuthorizationMatrixTest 踏襲）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどのチームにも所属させない。

        PerformanceMetricEntity metricA = metricRepository.save(PerformanceMetricEntity.builder()
                .teamId(teamAId)
                .name("PERFAUTHZ 走行距離")
                .unit("km")
                .dataType(MetricDataType.DECIMAL)
                .aggregationType(AggregationType.SUM)
                .isSelfRecordable(true)
                .build());
        metricAId = metricA.getId();

        PerformanceRecordEntity recordA = recordRepository.save(PerformanceRecordEntity.builder()
                .metricId(metricAId)
                .userId(memberAId)
                .recordedDate(LocalDate.now())
                .value(new BigDecimal("5.0"))
                .source(RecordSource.ADMIN)
                .recordedBy(adminAId)
                .build());
        recordAId = recordA.getId();

        ScheduleEntity scheduleA = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamAId)
                .userId(adminAId)
                .title("PERFAUTHZ 練習A")
                .startAt(LocalDateTime.now().plusDays(1))
                .allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ANYONE)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .createdBy(adminAId)
                .build());
        scheduleAId = scheduleA.getId();

        ScheduleEntity scheduleB = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamBId)
                .userId(adminBId)
                .title("PERFAUTHZ 練習B")
                .startAt(LocalDateTime.now().plusDays(1))
                .allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ANYONE)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .createdBy(adminBId)
                .build());
        scheduleBId = scheduleB.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 指標定義一覧（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/performance/metrics（指標一覧）")
    class ListMetrics {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/metrics", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/metrics", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/metrics", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/metrics", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 指標定義作成（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/performance/metrics（指標作成）")
    class CreateMetric {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/metrics", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "新指標"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/metrics", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "新指標"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/metrics", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "新指標"))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 指標定義更新（変更系・entity由来: checkAdminOrAbove、BOLA厳禁）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PUT /teams/{teamId}/performance/metrics/{id}（指標更新）")
    class UpdateMetric {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/performance/metrics/{id}", teamAId, metricAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "更新後"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自分のteamB path＋teamAの指標IDを叩くと404で秘匿")
        void BOLA_別チームの指標IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/performance/metrics/{id}", teamBId, metricAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "更新後"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/performance/metrics/{id}", teamAId, metricAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "更新後"))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 指標定義無効化（変更系・entity由来: checkAdminOrAbove、BOLA厳禁）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. DELETE /teams/{teamId}/performance/metrics/{id}（指標無効化）")
    class DeactivateMetric {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/performance/metrics/{id}", teamAId, metricAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自分のteamB path＋teamAの指標IDを叩くと404で秘匿")
        void BOLA_別チームの指標IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/performance/metrics/{id}", teamBId, metricAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/performance/metrics/{id}", teamAId, metricAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. テンプレートから指標一括作成（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST /teams/{teamId}/performance/metrics/from-template（テンプレ一括適用）")
    class CreateFromTemplate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/metrics/from-template", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("sportCategory", "PERFAUTHZ_CAT"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            templateRepository.save(PerformanceMetricTemplateEntity.builder()
                    .sportCategory("PERFAUTHZ_CAT")
                    .name("PERFAUTHZ テンプレ指標")
                    .unit("回")
                    .dataType(MetricDataType.INTEGER)
                    .aggregationType(AggregationType.SUM)
                    .sortOrder(1)
                    .isSelfRecordable(false)
                    .build());

            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/metrics/from-template", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("sportCategory", "PERFAUTHZ_CAT"))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 指標並び順一括更新（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. PATCH /teams/{teamId}/performance/metrics/sort-order（並び順一括更新）")
    class UpdateSortOrder {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/performance/metrics/sort-order", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sortOrderBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/performance/metrics/sort-order", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sortOrderBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> sortOrderBody() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", metricAId);
            entry.put("sortOrder", 5);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orders", List.of(entry));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. 連携可能フィールド一覧（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET /teams/{teamId}/performance/metrics/linkable-fields")
    class ListLinkableFields {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/metrics/linkable-fields", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/metrics/linkable-fields", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. パフォーマンス記録入力（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. POST /teams/{teamId}/performance/records（記録入力）")
    class CreateRecord {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/records", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRecordBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/records", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRecordBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/records", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createRecordBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createRecordBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("metricId", metricAId);
            body.put("userId", memberAId);
            body.put("recordedDate", LocalDate.now().toString());
            body.put("value", new BigDecimal("3.0"));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. パフォーマンス記録更新（変更系・entity由来: checkAdminOrAbove、BOLA厳禁）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. PUT /teams/{teamId}/performance/records/{id}（記録更新）")
    class UpdateRecord {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/performance/records/{id}", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRecordBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自分のteamB path＋teamAの記録IDを叩くと404で秘匿")
        void BOLA_別チームの記録IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/performance/records/{id}", teamBId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRecordBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/performance/records/{id}", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateRecordBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateRecordBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("value", new BigDecimal("7.5"));
            body.put("recordedDate", LocalDate.now().toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. パフォーマンス記録削除（変更系・entity由来: checkAdminOrAbove、BOLA厳禁）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. DELETE /teams/{teamId}/performance/records/{id}（記録削除）")
    class DeleteRecord {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/performance/records/{id}", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: teamB ADMINが自分のteamB path＋teamAの記録IDを叩くと404で秘匿")
        void BOLA_別チームの記録IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/performance/records/{id}", teamBId, recordAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/performance/records/{id}", teamAId, recordAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. 一括記録入力（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. POST /teams/{teamId}/performance/records/bulk（一括記録入力）")
    class CreateBulkRecords {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/records/bulk", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/records/bulk", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> bulkBody() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userId", memberAId);
            entry.put("metricId", metricAId);
            entry.put("value", new BigDecimal("2.0"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recordedDate", LocalDate.now().toString());
            body.put("entries", List.of(entry));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. MEMBER自己記録入力（閲覧系相当: checkMembership、本人固定のため代理記録不可）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. POST /teams/{teamId}/performance/records/self（自己記録入力）")
    class CreateSelfRecord {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/records/self", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(selfBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバー（本人）は201")
        void 非ADMINメンバーは201() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/performance/records/self", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(selfBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> selfBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("metricId", metricAId);
            body.put("recordedDate", LocalDate.now().toString());
            body.put("value", new BigDecimal("1.5"));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. パフォーマンス記録CSVエクスポート（閲覧系: checkMembership、他チーム成績窃取防止）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. GET /teams/{teamId}/performance/records/export（CSVエクスポート）")
    class ExportRecords {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/records/export", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（他チーム全メンバー成績CSVの窃取試行）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/records/export", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/records/export", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. スケジュールからの一括記録入力（変更系: checkAdminOrAbove、scheduleId scope BOLA厳禁）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. POST /teams/{teamId}/schedules/{scheduleId}/performance/records/bulk")
    class CreateScheduleBulkRecords {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedules/{scheduleId}/performance/records/bulk",
                            teamAId, scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scheduleBulkBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BOLA: 正当ADMINでも自チームpath＋他チームのscheduleIdは404で秘匿")
        void BOLA_別チームのscheduleIdは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedules/{scheduleId}/performance/records/bulk",
                            teamAId, scheduleBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scheduleBulkBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMIN＋自チームのscheduleIdは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/schedules/{scheduleId}/performance/records/bulk",
                            teamAId, scheduleAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scheduleBulkBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> scheduleBulkBody() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("userId", memberAId);
            entry.put("metricId", metricAId);
            entry.put("value", new BigDecimal("4.0"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("entries", List.of(entry));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 15. チーム統計ダッシュボード（閲覧系: checkMembership、他チーム統計越境防止）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("15. GET /teams/{teamId}/performance/stats（チーム統計）")
    class GetTeamStats {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/stats", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/stats", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/performance/stats", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 16. 特定メンバーのパフォーマンス（閲覧系: checkMembership、健康関連データ越境防止）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("16. GET /teams/{teamId}/members/{userId}/performance（メンバー成績）")
    class GetMemberPerformance {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{userId}/performance", teamAId, memberAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{userId}/performance", teamAId, memberAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{userId}/performance", teamAId, memberAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 17. スケジュール紐付きパフォーマンス（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("17. GET /teams/{teamId}/schedules/{scheduleId}/performance")
    class GetSchedulePerformance {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedules/{scheduleId}/performance", teamAId, scheduleAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/schedules/{scheduleId}/performance", teamAId, scheduleAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 18. 活動記録紐付きパフォーマンス（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("18. GET /teams/{teamId}/activities/{activityId}/performance")
    class GetActivityPerformance {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/activities/{activityId}/performance", teamAId, 1L))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/activities/{activityId}/performance", teamAId, 1L))
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

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'PERFAUTHZ', 'テスト', 'PERFAUTHZ テスト', 'ACTIVE', "
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

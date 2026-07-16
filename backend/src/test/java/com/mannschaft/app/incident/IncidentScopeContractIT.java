package com.mannschaft.app.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B3: incident ドメイン API 契約テスト（試練）。
 *
 * <p>正本: 軍議上奏「incident（ドメイン全体無防備）」。moderation と異なり incident には
 * SecurityConfig レベルの role 制約が一切無く、Controller の javadoc に記載された
 * 「認可: MEMBER以上」「認可: ADMIN相当」「認可: ADMIN または報告者本人」「認可: ADMIN または担当者」は
 * すべて未実装だった（authz呼び皆無）。</p>
 *
 * <p>金型: {@code DigestScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL。
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>
 *
 * <p>認可設計（Wave3-B3 出陣）:</p>
 * <ul>
 *   <li>scopeId/scopeType がリクエスト由来（create/list）: 通常の
 *       {@code checkMembership}/{@code checkAdminOrAbove} → 非メンバーは 403</li>
 *   <li>ID 直指定 EP（get/{id} put/{id} patch/{id}/status post/{id}/assign delete/{id} 等）:
 *       entity を先に fetch → 呼び出し元が entity 由来 scope のメンバーでない場合は
 *       存在秘匿のため 404（BOLA是正）。メンバーだが権限不足（非報告者/非ADMIN/非担当者）の場合は 403</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("incident ドメイン API 契約テスト（認可根治 Wave3-B3）")
class IncidentScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long memberAId;
    private Long adminBId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("INC認可契約チームA");
        teamBId = insertTeam("INC認可契約チームB");

        adminAId = insertUser("inc-authz-admin-a@example.com");
        memberAId = insertUser("inc-authz-member-a@example.com");
        adminBId = insertUser("inc-authz-admin-b@example.com");
        outsiderId = insertUser("inc-authz-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA はチームAの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // outsiderId はどちらのチームにも一切所属しない

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // インシデント報告(reportIncident) — scopeId/scopeTypeはリクエスト由来
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("インシデント報告(report)")
    class Report {

        @Test
        @DisplayName("非メンバーの報告は403（認可: MEMBER以上）")
        void 非メンバーの報告は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(post("/api/v1/incidents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reportBody(teamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーの報告は201")
        void 正当メンバーの報告は201() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/incidents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reportBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists())
                    .andExpect(jsonPath("$.data.reportedBy").value(memberAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // インシデント取得(getIncident) — ID直指定・entity由来scope検証(BOLA)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("インシデント取得(get)")
    class Get {

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのインシデントIDを直指定すると404で存在秘匿")
        void 越境取得は404() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/incidents/{id}", incidentId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INCIDENT_002"));
        }

        @Test
        @DisplayName("正当メンバーの取得は200")
        void 正当メンバーの取得は200() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/incidents/{id}", incidentId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(incidentId));
        }

        @Test
        @DisplayName("不在IDの取得は404（INCIDENT_002の存在秘匿）")
        void 不在IDの取得は404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/incidents/{id}", 999_999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("INCIDENT_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // インシデント一覧(listIncidents) — scopeId/scopeTypeはクエリ由来
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("インシデント一覧(list)")
    class ListIncidents {

        @Test
        @DisplayName("非メンバーの一覧取得は403")
        void 非メンバーの一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/incidents")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーの一覧取得は200")
        void 正当メンバーの一覧は200() throws Exception {
            insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/incidents")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // インシデント更新(updateIncident) — ADMIN または報告者本人
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("インシデント更新(update)")
    class Update {

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのインシデントを更新しようとすると404")
        void 越境更新は404() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/incidents/{id}", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("乗っ取りタイトル"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("同一チームだが報告者でもADMINでもないメンバーの更新は403")
        void 非報告者非ADMINの更新は403() throws Exception {
            // memberA とは別の一般メンバーを用意し、reportedBy とは別人にする
            Long anotherMemberId = insertUser("inc-authz-member-a2@example.com");
            MembershipTestHelper.insertMembership(em, anotherMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            em.flush();

            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(anotherMemberId);
            mockMvc.perform(put("/api/v1/incidents/{id}", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("勝手に変更"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当: 報告者本人の更新は200")
        void 報告者本人の更新は200() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/incidents/{id}", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("修正後タイトル"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("修正後タイトル"));
        }

        @Test
        @DisplayName("正当: 同一チームADMIN(非報告者)の更新は200")
        void ADMINの更新は200() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/incidents/{id}", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("ADMIN修正"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("ADMIN修正"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ステータス変更(changeStatus) — ADMIN または担当者
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ステータス変更(status)")
    class ChangeStatus {

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのインシデントのステータスを変更しようとすると404")
        void 越境ステータス変更は404() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminBId);
            mockMvc.perform(patch("/api/v1/incidents/{id}/status", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("ACKNOWLEDGED"))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("同一チームだがADMINでも担当者でもないメンバーのステータス変更は403")
        void 非ADMIN非担当者のステータス変更は403() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(memberAId); // 報告者だが担当者でもADMINでもない
            mockMvc.perform(patch("/api/v1/incidents/{id}/status", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("ACKNOWLEDGED"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当: ADMINのステータス変更は200")
        void ADMINのステータス変更は200() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/incidents/{id}/status", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("ACKNOWLEDGED"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"));
        }

        @Test
        @DisplayName("正当: 担当者(非ADMIN)のステータス変更は200")
        void 担当者のステータス変更は200() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");
            insertIncidentAssignment(incidentId, memberAId);

            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/incidents/{id}/status", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("IN_PROGRESS"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 担当者アサイン(assign) — ADMIN相当
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("担当者アサイン(assign)")
    class Assign {

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのインシデントに担当者をアサインしようとすると404")
        void 越境アサインは404() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/incidents/{id}/assign", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(memberAId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("同一チームの非ADMINメンバーのアサインは403")
        void 非ADMINのアサインは403() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/incidents/{id}/assign", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(memberAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当: ADMINのアサインは200")
        void ADMINのアサインは200() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/incidents/{id}/assign", incidentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(assignBody(memberAId))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(incidentId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // インシデント削除(delete) — ADMIN相当
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("インシデント削除(delete)")
    class Delete {

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのインシデントを削除しようとすると404")
        void 越境削除は404() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/incidents/{id}", incidentId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("同一チームの非ADMINメンバーの削除は403")
        void 非ADMINの削除は403() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/incidents/{id}", incidentId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当: ADMINの削除は204")
        void ADMINの削除は204() throws Exception {
            Long incidentId = insertIncident(teamAId, memberAId, "REPORTED");

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/incidents/{id}", incidentId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // インシデントカテゴリ(categories)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("インシデントカテゴリ(categories)")
    class Categories {

        @Test
        @DisplayName("非ADMINメンバーのカテゴリ作成は403")
        void 非ADMINのカテゴリ作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/incidents/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(categoryCreateBody(teamAId, "設備障害"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのカテゴリ作成は201")
        void ADMINのカテゴリ作成は201() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/incidents/categories")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(categoryCreateBody(teamAId, "設備障害"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.name").value("設備障害"));
        }

        @Test
        @DisplayName("非メンバーのカテゴリ一覧取得は403")
        void 非メンバーのカテゴリ一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/incidents/categories")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーのカテゴリ一覧取得は200")
        void メンバーのカテゴリ一覧は200() throws Exception {
            insertIncidentCategory(teamAId, adminAId, "既存カテゴリ");

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/incidents/categories")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのカテゴリを更新しようとすると404")
        void 越境カテゴリ更新は404() throws Exception {
            Long categoryId = insertIncidentCategory(teamAId, adminAId, "越境対象カテゴリ");

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/incidents/categories/{id}", categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(categoryUpdateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINのカテゴリ更新は200")
        void ADMINのカテゴリ更新は200() throws Exception {
            Long categoryId = insertIncidentCategory(teamAId, adminAId, "更新対象カテゴリ");

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/incidents/categories/{id}", categoryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(categoryUpdateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのカテゴリを削除しようとすると404")
        void 越境カテゴリ削除は404() throws Exception {
            Long categoryId = insertIncidentCategory(teamAId, adminAId, "削除対象カテゴリB");

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/incidents/categories/{id}", categoryId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINのカテゴリ削除は204")
        void ADMINのカテゴリ削除は204() throws Exception {
            Long categoryId = insertIncidentCategory(teamAId, adminAId, "削除対象カテゴリA");

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/incidents/categories/{id}", categoryId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // メンテナンススケジュール(schedules)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メンテナンススケジュール(schedules)")
    class Schedules {

        @Test
        @DisplayName("非ADMINメンバーのスケジュール作成は403")
        void 非ADMINのスケジュール作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/maintenance-schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scheduleCreateBody(teamAId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのスケジュール作成は201")
        void ADMINのスケジュール作成は201() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/maintenance-schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scheduleCreateBody(teamAId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("非ADMINメンバーのスケジュール一覧取得は403（認可: ADMIN相当）")
        void 非ADMINの一覧取得は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/maintenance-schedules")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのスケジュール一覧取得は200")
        void ADMINの一覧取得は200() throws Exception {
            insertMaintenanceSchedule(teamAId, adminAId);

            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/maintenance-schedules")
                            .param("scopeType", "TEAM")
                            .param("scopeId", teamAId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのスケジュールを更新しようとすると404")
        void 越境スケジュール更新は404() throws Exception {
            Long scheduleId = insertMaintenanceSchedule(teamAId, adminAId);

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/maintenance-schedules/{id}", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scheduleUpdateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINのスケジュール更新は200")
        void ADMINのスケジュール更新は200() throws Exception {
            Long scheduleId = insertMaintenanceSchedule(teamAId, adminAId);

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/maintenance-schedules/{id}", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(scheduleUpdateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのスケジュールを削除しようとすると404")
        void 越境スケジュール削除は404() throws Exception {
            Long scheduleId = insertMaintenanceSchedule(teamAId, adminAId);

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/maintenance-schedules/{id}", scheduleId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINのスケジュール削除は204")
        void ADMINのスケジュール削除は204() throws Exception {
            Long scheduleId = insertMaintenanceSchedule(teamAId, adminAId);

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/maintenance-schedules/{id}", scheduleId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("越境(BOLA): チームB ADMINがチームAのスケジュールを手動トリガーしようとすると404")
        void 越境トリガーは404() throws Exception {
            Long scheduleId = insertMaintenanceSchedule(teamAId, adminAId);

            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/maintenance-schedules/{id}/trigger", scheduleId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINの手動トリガーは200")
        void ADMINの手動トリガーは200() throws Exception {
            Long scheduleId = insertMaintenanceSchedule(teamAId, adminAId);

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/maintenance-schedules/{id}/trigger", scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> reportBody(Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", scopeId);
        body.put("title", "契約テスト用インシデント");
        body.put("description", "本文");
        body.put("priority", "MEDIUM");
        return body;
    }

    private Map<String, Object> updateBody(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        return body;
    }

    private Map<String, Object> statusBody(String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        return body;
    }

    private Map<String, Object> assignBody(Long assigneeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assigneeId", assigneeId);
        body.put("assigneeType", "USER");
        return body;
    }

    private Map<String, Object> categoryCreateBody(Long scopeId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", scopeId);
        body.put("name", name);
        body.put("slaHours", 72);
        return body;
    }

    private Map<String, Object> categoryUpdateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slaHours", 48);
        return body;
    }

    private Map<String, Object> scheduleCreateBody(Long scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", scopeId);
        body.put("title", "定期点検");
        body.put("description", "毎週の定期点検");
        body.put("cronExpression", "0 0 3 * * MON");
        body.put("isActive", true);
        return body;
    }

    private Map<String, Object> scheduleUpdateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "更新後の定期点検");
        return body;
    }

    /** roles を name で引く idempotent seed（グローバル参照テーブルのため deleteAll しない）。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
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
                                + "VALUES (:email, 'INC契約', 'テスト', 'INC契約テスト', 'ACTIVE', "
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
                                + "CONCAT('inc-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * incidents へ 1 行 INSERT する（NOT NULL 列: scope_type/scope_id/title/status/priority/
     * is_sla_breached/reported_by/version/created_at/updated_at をすべて明示）。
     */
    private Long insertIncident(Long scopeId, Long reportedBy, String status) {
        em.createNativeQuery(
                        "INSERT INTO incidents (scope_type, scope_id, title, description, status, priority, "
                                + "is_sla_breached, reported_by, version, created_at, updated_at) "
                                + "VALUES ('TEAM', :sid, '契約テスト用インシデント', '本文', :status, 'MEDIUM', "
                                + "0, :reportedBy, 0, NOW(), NOW())")
                .setParameter("sid", scopeId)
                .setParameter("status", status)
                .setParameter("reportedBy", reportedBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM incidents").getSingleResult()).longValue();
    }

    private void insertIncidentAssignment(Long incidentId, Long userId) {
        em.createNativeQuery(
                        "INSERT INTO incident_assignments (incident_id, assignee_type, user_id, created_at) "
                                + "VALUES (:incidentId, 'USER', :userId, NOW())")
                .setParameter("incidentId", incidentId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /**
     * incident_categories へ 1 行 INSERT する（NOT NULL 列: scope_type/scope_id/name/sla_hours/
     * is_active/created_by/version/created_at/updated_at をすべて明示）。
     */
    private Long insertIncidentCategory(Long scopeId, Long createdBy, String name) {
        em.createNativeQuery(
                        "INSERT INTO incident_categories (scope_type, scope_id, name, sla_hours, is_active, "
                                + "created_by, version, created_at, updated_at) "
                                + "VALUES ('TEAM', :sid, :name, 72, 1, "
                                + ":createdBy, 0, NOW(), NOW())")
                .setParameter("sid", scopeId)
                .setParameter("name", name)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM incident_categories").getSingleResult()).longValue();
    }

    /**
     * incident_maintenance_schedules へ 1 行 INSERT する（NOT NULL 列: scope_type/scope_id/name/
     * cron_expression/next_execution_date/is_active/created_by/version/created_at/updated_at を
     * すべて明示）。
     */
    private Long insertMaintenanceSchedule(Long scopeId, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO incident_maintenance_schedules (scope_type, scope_id, name, description, "
                                + "cron_expression, next_execution_date, is_active, created_by, version, "
                                + "created_at, updated_at) "
                                + "VALUES ('TEAM', :sid, '定期点検', '説明', "
                                + "'0 0 3 * * MON', DATE_ADD(CURDATE(), INTERVAL 1 DAY), 1, :createdBy, 0, "
                                + "NOW(), NOW())")
                .setParameter("sid", scopeId)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM incident_maintenance_schedules")
                .getSingleResult()).longValue();
    }
}

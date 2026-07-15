package com.mannschaft.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.service.entity.ServiceRecordEntity;
import com.mannschaft.app.service.entity.ServiceRecordSettingsEntity;
import com.mannschaft.app.service.repository.ServiceRecordRepository;
import com.mannschaft.app.service.repository.ServiceRecordSettingsRepository;
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

import java.time.LocalDate;
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
 * 認可根治戦役 Wave 2 トランシェ2A #6 — service ドメイン（要配慮個人情報：ケア・福祉記録）
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md} トランシェ2A #6。
 * {@code ServiceRecordController}/{@code ServiceRecordService} は認可（{@code AccessControlService}）が
 * 一切敷設されておらず、teamId を跨いだ IDOR（BOLA）が成立する状態だった。</p>
 *
 * <p>金型: {@code TeamAdvertiserScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext）・{@code MemberPaymentAuthzIntegrationTest}（{@code MembershipTestHelper}
 * 経由の user_roles/memberships seed）。Spring Security フィルタは無効化するが、越境 403 は
 * {@code AccessControlService.checkMembership}/{@code checkAdminOrAbove} のアプリケーション層例外
 * （{@code COMMON_002} → 403）として発生するためフィルタ無効でも検証できる。</p>
 *
 * <p><b>4象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（teamB の ADMIN が teamA へアクセス）/
 * 非 ADMIN メンバー（memberA）/ 正当 ADMIN（adminA）。閲覧系は checkMembership、変更系は
 * checkAdminOrAbove を期待する。get/update/confirm/delete/duplicate/reactions/attachments は
 * {@code findByIdAndTeamId} で id と teamId を紐付けたうえで entity 由来 teamId を認可に使うため、
 * 「teamB ADMIN が teamA の recordId を teamA の URL で叩く」越境も 403 になることを検証する（BOLA）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("service ドメイン（サービス履歴）認可契約テスト（試練）")
class ServiceRecordScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ServiceRecordRepository serviceRecordRepository;

    @Autowired
    private ServiceRecordSettingsRepository serviceRecordSettingsRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;   // TEAM A の ADMIN（正当）
    private Long adminBId;   // TEAM B の ADMIN（別 scope の越境攻撃者）
    private Long memberAId;  // TEAM A の非 ADMIN メンバー
    private Long outsiderId; // どこにも所属しない非メンバー

    private Long recordAId;      // TEAM A のサービス記録（DRAFT）
    private Long confirmedRecordAId; // TEAM A のサービス記録（CONFIRMED・履歴/CSV/サマリー用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SVCAUTHZ チームA");
        teamBId = insertTeam("SVCAUTHZ チームB");

        adminAId = insertUser("svcauthz-admin-a@example.com");
        adminBId = insertUser("svcauthz-admin-b@example.com");
        memberAId = insertUser("svcauthz-member-a@example.com");
        outsiderId = insertUser("svcauthz-outsider@example.com");

        // ADMIN 判定（checkAdminOrAbove → resolveEffectiveRoleName）は user_roles を見るが、
        // 所属判定（checkMembership → isMember）は memberships テーブルのみを見る（別系統）。
        // 実運用の ADMIN は「先にメンバーとして加入 → 後で ADMIN に昇格」が通常経路のため、
        // ADMIN ユーザーにも memberships 行を張る（RepairPlanAuthorizationMatrixTest 踏襲）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどのチームにも所属させない。

        ServiceRecordEntity draft = serviceRecordRepository.save(ServiceRecordEntity.builder()
                .teamId(teamAId)
                .memberUserId(memberAId)
                .staffUserId(adminAId)
                .serviceDate(LocalDate.now())
                .title("SVCAUTHZ 記録（下書き）")
                .durationMinutes(30)
                .status(ServiceRecordStatus.DRAFT)
                .build());
        recordAId = draft.getId();

        ServiceRecordEntity confirmed = serviceRecordRepository.save(ServiceRecordEntity.builder()
                .teamId(teamAId)
                .memberUserId(memberAId)
                .staffUserId(adminAId)
                .serviceDate(LocalDate.now())
                .title("SVCAUTHZ 記録（確定済み）")
                .durationMinutes(45)
                .status(ServiceRecordStatus.CONFIRMED)
                .build());
        confirmedRecordAId = confirmed.getId();

        serviceRecordSettingsRepository.save(ServiceRecordSettingsEntity.builder()
                .teamId(teamAId)
                .isDashboardEnabled(true)
                .isReactionEnabled(true)
                .build());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 一覧（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /teams/{teamId}/service-records（一覧）")
    class ListRecords {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMIN）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 作成（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /teams/{teamId}/service-records（作成）")
    class CreateRecord {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("memberUserId", memberAId);
            body.put("serviceDate", LocalDate.now().toString());
            body.put("title", "新規記録");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 詳細取得（閲覧系・entity由来: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /teams/{teamId}/service-records/{id}（詳細）")
    class GetRecord {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAのURLを叩く越境）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 更新（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT /teams/{teamId}/service-records/{id}（更新）")
    class UpdateRecord {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（teamBのADMINがteamAの記録を更新しようとする越境）は403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("memberUserId", memberAId);
            body.put("serviceDate", LocalDate.now().toString());
            body.put("title", "更新後タイトル");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 確定（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. PATCH /teams/{teamId}/service-records/{id}/confirm（確定）")
    class ConfirmRecord {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/service-records/{id}/confirm", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/service-records/{id}/confirm", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/service-records/{id}/confirm", teamAId, recordAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 削除（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. DELETE /teams/{teamId}/service-records/{id}（削除）")
    class DeleteRecord {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/{id}", teamAId, recordAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. 複製（変更系・entity由来: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. POST /teams/{teamId}/service-records/{id}/duplicate（複製）")
    class DuplicateRecord {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/{id}/duplicate", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/{id}/duplicate", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/{id}/duplicate", teamAId, recordAId))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. メンバー履歴一覧・サマリー（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET /teams/{teamId}/members/{userId}/service-history（+summary）")
    class MemberHistory {

        @Test
        @DisplayName("非メンバーは履歴一覧403")
        void 非メンバーは履歴一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{userId}/service-history", teamAId, memberAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは履歴一覧403")
        void 別scopeADMINは履歴一覧403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{userId}/service-history", teamAId, memberAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは履歴一覧200")
        void 正当ADMINは履歴一覧200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{userId}/service-history", teamAId, memberAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーはサマリー403")
        void 非メンバーはサマリー403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{userId}/service-history/summary",
                            teamAId, memberAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINはサマリー200")
        void 正当ADMINはサマリー200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/members/{userId}/service-history/summary",
                            teamAId, memberAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. リアクション（checkMembership + 既存の本人確認）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. POST/DELETE /teams/{teamId}/service-records/{id}/reactions")
    class Reactions {

        @Test
        @DisplayName("非メンバーがリアクション追加→403")
        void 非メンバーは追加403() throws Exception {
            setAuth(outsiderId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reactionType", "LIKE");
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/{id}/reactions", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINがリアクション追加→403")
        void 別scopeADMINは追加403() throws Exception {
            setAuth(adminBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reactionType", "LIKE");
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/{id}/reactions", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("本人（記録のmemberUserId本人）は201")
        void 本人は201() throws Exception {
            setAuth(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reactionType", "LIKE");
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/{id}/reactions", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非メンバーがリアクション削除→403")
        void 非メンバーは削除403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/{id}/reactions", teamAId, recordAId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. 添付ファイル（変更系: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. 添付ファイル upload-url / register / delete")
    class Attachments {

        @Test
        @DisplayName("非ADMINメンバーはアップロードURL発行403")
        void 非ADMINメンバーはアップロードURL発行403() throws Exception {
            setAuth(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fileName", "test.jpg");
            body.put("contentType", "image/jpeg");
            body.put("fileSize", 1000);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/service-records/{id}/attachments/upload-url", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINはアップロードURL発行403")
        void 別scopeADMINはアップロードURL発行403() throws Exception {
            setAuth(adminBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fileName", "test.jpg");
            body.put("contentType", "image/jpeg");
            body.put("fileSize", 1000);
            mockMvc.perform(post(
                            "/api/v1/teams/{teamId}/service-records/{id}/attachments/upload-url", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは添付登録403")
        void 非ADMINメンバーは添付登録403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/{id}/attachments", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(attachmentBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは添付登録201")
        void 正当ADMINは添付登録201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/service-records/{id}/attachments", teamAId, recordAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(attachmentBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非ADMINメンバーは添付削除403")
        void 非ADMINメンバーは添付削除403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/service-records/{id}/attachments/{aid}",
                            teamAId, recordAId, 999L))
                    .andExpect(status().isForbidden());
        }

        private Map<String, Object> attachmentBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fileKey", "service-records/test/key.jpg");
            body.put("fileName", "test.jpg");
            body.put("contentType", "image/jpeg");
            body.put("fileSize", 1000);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. CSVエクスポート（閲覧系: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. GET /teams/{teamId}/service-records/export（CSV）")
    class ExportCsv {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/export", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403")
        void 別scopeADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/export", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/service-records/export", teamAId))
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
                                + "VALUES (:email, 'SVCAUTHZ', 'テスト', 'SVCAUTHZ テスト', 'ACTIVE', "
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

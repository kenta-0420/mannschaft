package com.mannschaft.app.notification.confirmable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3 バッチB12-notification — notification/confirmable（F04.9 確認通知）
 * {@code OrgConfirmableNotificationController}/{@code TeamConfirmableNotificationController}
 * API 契約テスト（試練 / red 先行）。
 *
 * <p>正本: 依頼文（Wave3-B12notif notification/confirmable 節）。send/list/getDetail/cancel/
 * resendReminder の 5EP に認可が一切敷設されておらず、未認証以外は誰でも到達できていた
 * （send は ORG スコープで通知クレジット消費まで発生する重大操作）。getDetail は scope 整合
 * チェックのみで membership チェックが欠落しており、notificationId と正しい orgId/teamId さえ
 * 知っていれば非メンバーでも詳細を閲覧できた。cancel/resendReminder/getRecipients は
 * notificationId ↔ path スコープの突合が無く、正当な自スコープ ADMIN であっても他スコープの
 * notificationId を渡せばその通知をキャンセル・リマインド再送・受信者一覧閲覧できる BOLA が
 * 成立していた（getRecipients は既に isAdminOrAbove 分岐は是正済みだが notificationId 突合が
 * 欠落していた副次 BOLA）。</p>
 *
 * <p>金型: {@code PaymentScopeContractIT}/{@code TeamPaymentScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext +
 * {@code MembershipTestHelper}）。confirmable_notifications/recipients は
 * {@code ConfirmableNotificationRepository} 経由の JPA save で seed する
 * （生 SQL の NOT NULL 全網羅を回避）。</p>
 *
 * <p><b>象限</b>: 非メンバー/非 ADMIN メンバー（outsider・member）/ 別 scope ADMIN
 * （scope B の ADMIN が scope A の URL を叩く越境）/ 正当 scope・他 scope の notificationId
 * （BOLA: notificationId が path 上位スコープに属するかの突合）/ 正当 ADMIN・正当 MEMBER。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("notification/confirmable（F04.9 確認通知）ドメイン 認可契約テスト（試練・Wave3-B12notif）")
class ConfirmableNotificationScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @PersistenceContext
    private EntityManager em;

    // ═════════════════════════════════════════════════════════════════════
    // 組織スコープ フィクスチャ
    // ═════════════════════════════════════════════════════════════════════
    private Long orgAId;
    private Long orgBId;
    private Long orgAdminAId;    // 組織A ADMIN（正当）
    private Long orgAdminBId;    // 組織B ADMIN（別 scope の越境攻撃者）
    private Long orgMemberAId;   // 組織A 非ADMINメンバー
    private Long orgOutsiderId;  // どこにも所属しない非メンバー
    private Long orgNotifAId;    // 組織A の ACTIVE 確認通知
    private Long orgNotifBId;    // 組織B の ACTIVE 確認通知（BOLA 越境検証用）

    // ═════════════════════════════════════════════════════════════════════
    // チームスコープ フィクスチャ
    // ═════════════════════════════════════════════════════════════════════
    private Long teamAId;
    private Long teamBId;
    private Long teamAdminAId;
    private Long teamAdminBId;
    private Long teamMemberAId;
    private Long teamOutsiderId;
    private Long teamNotifAId;
    private Long teamNotifBId;

    @BeforeEach
    void setUp() {
        // ---- 組織スコープ ----
        orgAId = insertOrganization("CNAUTHZ 組織A");
        orgBId = insertOrganization("CNAUTHZ 組織B");

        orgAdminAId = insertUser("cnauthz-org-admin-a@example.com");
        orgAdminBId = insertUser("cnauthz-org-admin-b@example.com");
        orgMemberAId = insertUser("cnauthz-org-member-a@example.com");
        orgOutsiderId = insertUser("cnauthz-org-outsider@example.com");

        MembershipTestHelper.insertMembership(em, orgAdminAId,
                com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION, orgAId,
                com.mannschaft.app.membership.domain.RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, orgAdminBId,
                com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION, orgBId,
                com.mannschaft.app.membership.domain.RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, orgMemberAId,
                com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION, orgAId,
                com.mannschaft.app.membership.domain.RoleKind.MEMBER);
        // orgOutsiderId はどこにも所属させない。

        orgNotifAId = notificationRepository.save(ConfirmableNotificationEntity.builder()
                        .scopeType(ScopeType.ORGANIZATION)
                        .scopeId(orgAId)
                        .title("CNAUTHZ 組織A確認通知")
                        .priority(ConfirmableNotificationPriority.NORMAL)
                        .totalRecipientCount(0)
                        .build())
                .getId();

        orgNotifBId = notificationRepository.save(ConfirmableNotificationEntity.builder()
                        .scopeType(ScopeType.ORGANIZATION)
                        .scopeId(orgBId)
                        .title("CNAUTHZ 組織B確認通知")
                        .priority(ConfirmableNotificationPriority.NORMAL)
                        .totalRecipientCount(0)
                        .build())
                .getId();

        // ---- チームスコープ ----
        teamAId = insertTeam("CNAUTHZ チームA");
        teamBId = insertTeam("CNAUTHZ チームB");

        teamAdminAId = insertUser("cnauthz-team-admin-a@example.com");
        teamAdminBId = insertUser("cnauthz-team-admin-b@example.com");
        teamMemberAId = insertUser("cnauthz-team-member-a@example.com");
        teamOutsiderId = insertUser("cnauthz-team-outsider@example.com");

        MembershipTestHelper.insertMembership(em, teamAdminAId,
                com.mannschaft.app.membership.domain.ScopeType.TEAM, teamAId,
                com.mannschaft.app.membership.domain.RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, teamAdminBId,
                com.mannschaft.app.membership.domain.ScopeType.TEAM, teamBId,
                com.mannschaft.app.membership.domain.RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, teamMemberAId,
                com.mannschaft.app.membership.domain.ScopeType.TEAM, teamAId,
                com.mannschaft.app.membership.domain.RoleKind.MEMBER);
        // teamOutsiderId はどこにも所属させない。

        teamNotifAId = notificationRepository.save(ConfirmableNotificationEntity.builder()
                        .scopeType(ScopeType.TEAM)
                        .scopeId(teamAId)
                        .title("CNAUTHZ チームA確認通知")
                        .priority(ConfirmableNotificationPriority.NORMAL)
                        .totalRecipientCount(0)
                        .build())
                .getId();

        teamNotifBId = notificationRepository.save(ConfirmableNotificationEntity.builder()
                        .scopeType(ScopeType.TEAM)
                        .scopeId(teamBId)
                        .title("CNAUTHZ チームB確認通知")
                        .priority(ConfirmableNotificationPriority.NORMAL)
                        .totalRecipientCount(0)
                        .build())
                .getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 組織スコープ（OrgConfirmableNotificationController）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("組織スコープ 1. POST .../confirmable-notifications（送信: checkAdminOrAbove）")
    class OrgSend {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(orgOutsiderId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(orgMemberAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(orgMemberAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（組織BのADMIN）は403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(orgMemberAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(orgMemberAId))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> sendBody(Long recipientUserId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "CNAUTHZ 送信テスト");
            body.put("recipientUserIds", List.of(recipientUserId));
            return body;
        }
    }

    @Nested
    @DisplayName("組織スコープ 2. GET .../confirmable-notifications（一覧: checkMembership）")
    class OrgList {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(orgOutsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications", orgAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("組織スコープ 3. GET .../confirmable-notifications/{id}（詳細: checkMembership + scope突合）")
    class OrgGetDetail {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(orgOutsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}",
                            orgAId, orgNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーだがnotificationIdが他組織所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}",
                            orgAId, orgNotifBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}",
                            orgAId, orgNotifAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("組織スコープ 4. PATCH .../{id}/cancel（キャンセル: checkAdminOrAbove + scope突合）")
    class OrgCancel {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/confirmable-notifications/{nid}/cancel",
                            orgAId, orgNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/confirmable-notifications/{nid}/cancel",
                            orgAId, orgNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他組織所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/confirmable-notifications/{nid}/cancel",
                            orgAId, orgNotifBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/confirmable-notifications/{nid}/cancel",
                            orgAId, orgNotifAId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("組織スコープ 5. POST .../{id}/resend-reminder（リマインド再送: checkAdminOrAbove + scope突合）")
    class OrgResendReminder {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/{nid}/resend-reminder",
                            orgAId, orgNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/{nid}/resend-reminder",
                            orgAId, orgNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他組織所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/{nid}/resend-reminder",
                            orgAId, orgNotifBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/{nid}/resend-reminder",
                            orgAId, orgNotifAId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("組織スコープ 6. GET .../{id}/recipients（受信者一覧: scope突合の副次BOLA是正）")
    class OrgGetRecipients {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(orgOutsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}/recipients",
                            orgAId, orgNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他組織所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}/recipients",
                            orgAId, orgNotifBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}/recipients",
                            orgAId, orgNotifAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // チームスコープ（TeamConfirmableNotificationController）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チームスコープ 1. POST .../confirmable-notifications（送信: checkAdminOrAbove）")
    class TeamSend {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(teamOutsiderId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(teamMemberAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(teamMemberAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（チームBのADMIN）は403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(teamMemberAId))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(teamMemberAId))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> sendBody(Long recipientUserId) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "CNAUTHZ チーム送信テスト");
            body.put("recipientUserIds", List.of(recipientUserId));
            return body;
        }
    }

    @Nested
    @DisplayName("チームスコープ 2. GET .../confirmable-notifications（一覧: checkMembership）")
    class TeamList {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(teamOutsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications", teamAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("チームスコープ 3. GET .../confirmable-notifications/{id}（詳細: checkMembership + scope突合）")
    class TeamGetDetail {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(teamOutsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}",
                            teamAId, teamNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーだがnotificationIdが他チーム所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}",
                            teamAId, teamNotifBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}",
                            teamAId, teamNotifAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("チームスコープ 4. PATCH .../{id}/cancel（キャンセル: checkAdminOrAbove + scope突合）")
    class TeamCancel {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/confirmable-notifications/{nid}/cancel",
                            teamAId, teamNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(patch("/api/v1/teams/{id}/confirmable-notifications/{nid}/cancel",
                            teamAId, teamNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他チーム所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/confirmable-notifications/{nid}/cancel",
                            teamAId, teamNotifBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/confirmable-notifications/{nid}/cancel",
                            teamAId, teamNotifAId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("チームスコープ 5. POST .../{id}/resend-reminder（リマインド再送: checkAdminOrAbove + scope突合）")
    class TeamResendReminder {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/{nid}/resend-reminder",
                            teamAId, teamNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/{nid}/resend-reminder",
                            teamAId, teamNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他チーム所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/{nid}/resend-reminder",
                            teamAId, teamNotifBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/{nid}/resend-reminder",
                            teamAId, teamNotifAId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("チームスコープ 6. GET .../{id}/recipients（受信者一覧: scope突合の副次BOLA是正）")
    class TeamGetRecipients {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(teamOutsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}/recipients",
                            teamAId, teamNotifAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他チーム所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}/recipients",
                            teamAId, teamNotifBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}/recipients",
                            teamAId, teamNotifAId))
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
                                + "VALUES (:email, 'CNAUTHZ', 'テスト', 'CNAUTHZ テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cn-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('cn-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}

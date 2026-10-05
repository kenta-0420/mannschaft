package com.mannschaft.app.notification.confirmable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationRecipientResponse;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTemplateRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.assertj.core.api.Assertions.assertThat;

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
 *
 * <p><b>認可根治戦役 Wave7 追加分</b>: settings（get/update）・template（list/create/update/delete）の
 * 計 12 エンドポイントに {@code AccessControlService} を敷設した契約を追加する。
 * settings は閲覧=checkMembership／変更=checkAdminOrAbove。template も
 * list=checkMembership／create=checkAdminOrAbove、update/delete はテンプレート実体由来の
 * スコープ突合（不一致は {@code TEMPLATE_NOT_FOUND} で 404 存在秘匿）＋checkAdminOrAbove。
 * confirm（org/team）と listPending は呼び出しユーザー自身の受信者行のみを検索条件に固定する
 * 構造的な自己スコープ EP と監査で確認し、{@code @AuthorizedInService} マーカーを付与した
 * （本ファイルでの契約テスト対象外）。</p>
 *
 * <p><b>CMP-260923-0954 W3b（存在オラクル是正）で期待値を更新</b>: 通知の詳細・受信者一覧の非メンバー、
 * cancel・resend・テンプレート更新/削除の別 scope ADMIN は 403 → 404（不在と同一の
 * {@code CONFIRMABLE_NOTIFICATION_NOT_FOUND} / {@code …_TEMPLATE_NOT_FOUND}）。他スコープ ID の越境は
 * {@code SCOPE_MISMATCH} を廃して {@code NOT_FOUND} に揃えたため code まで検証する。主体の全量は
 * {@link ConfirmableNotificationExistenceOracleContractIT} が持つ。</p>
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

    @Autowired
    private ConfirmableNotificationTemplateRepository templateRepository;

    @Autowired
    private ConfirmableNotificationService notificationService;

    @Autowired
    private ConfirmableNotificationMapper notificationMapper;

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
    private Long orgTemplateAId; // 組織A のテンプレート
    private Long orgTemplateBId; // 組織B のテンプレート（BOLA 越境検証用）

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
    private Long teamTemplateAId; // チームA のテンプレート
    private Long teamTemplateBId; // チームB のテンプレート（BOLA 越境検証用）

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

        orgTemplateAId = templateRepository.save(ConfirmableNotificationTemplateEntity.builder()
                        .scopeType(ScopeType.ORGANIZATION)
                        .scopeId(orgAId)
                        .name("CNAUTHZ 組織Aテンプレート")
                        .title("CNAUTHZ 組織Aテンプレートタイトル")
                        .build())
                .getId();

        orgTemplateBId = templateRepository.save(ConfirmableNotificationTemplateEntity.builder()
                        .scopeType(ScopeType.ORGANIZATION)
                        .scopeId(orgBId)
                        .name("CNAUTHZ 組織Bテンプレート")
                        .title("CNAUTHZ 組織Bテンプレートタイトル")
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

        teamTemplateAId = templateRepository.save(ConfirmableNotificationTemplateEntity.builder()
                        .scopeType(ScopeType.TEAM)
                        .scopeId(teamAId)
                        .name("CNAUTHZ チームAテンプレート")
                        .title("CNAUTHZ チームAテンプレートタイトル")
                        .build())
                .getId();

        teamTemplateBId = templateRepository.save(ConfirmableNotificationTemplateEntity.builder()
                        .scopeType(ScopeType.TEAM)
                        .scopeId(teamBId)
                        .name("CNAUTHZ チームBテンプレート")
                        .title("CNAUTHZ チームBテンプレートタイトル")
                        .build())
                .getId();

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("CMP-260920-1040 宛先件数プレビューの HTTP・認可契約")
    class RecipientPreview {

        @Test
        @DisplayName("チーム ADMIN は 200 と送信者を除いた見込み件数を得る")
        void チームADMINは見込み件数を得る() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/recipient-preview", teamAId)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.estimatedRecipientCount").value(1));
        }

        @Test
        @DisplayName("組織 ADMIN は 200 と見込み件数を得る")
        void 組織ADMINは見込み件数を得る() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/recipient-preview", orgAId)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.estimatedRecipientCount").isNumber());
        }

        @Test
        @DisplayName("チームの非 ADMIN メンバーと非メンバーは 403")
        void チーム権限なしは403() throws Exception {
            for (Long userId : List.of(teamMemberAId, teamOutsiderId, teamAdminBId)) {
                setAuth(userId);
                mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/recipient-preview", teamAId)
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("組織の非 ADMIN メンバーと非メンバーは 403")
        void 組織権限なしは403() throws Exception {
            for (Long userId : List.of(orgMemberAId, orgOutsiderId, orgAdminBId)) {
                setAuth(userId);
                mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/recipient-preview", orgAId)
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isForbidden());
            }
        }

        @Test
        @DisplayName("空 targets と targets・group 同時指定は 400")
        void 宛先入力違反は400() throws Exception {
            setAuth(teamAdminAId);
            for (String body : List.of("{\"targets\":[]}",
                    "{\"targets\":[{\"type\":\"TEAM\",\"id\":" + teamAId
                            + "}],\"recipientGroupId\":\"00000000-0000-0000-0000-000000000001\"}")) {
                mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/recipient-preview", teamAId)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest());
            }
        }

        @Test
        @DisplayName("スコープ外 target はチーム・組織とも 403")
        void 越境ターゲットは403() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/recipient-preview", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targets\":[{\"type\":\"TEAM\",\"id\":" + teamBId + "}]}"))
                    .andExpect(status().isForbidden());

            setAuth(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/recipient-preview", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"targets\":[{\"type\":\"ORGANIZATION\",\"id\":" + orgBId + "}]}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 組織スコープ（OrgConfirmableNotificationController）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("永続化コンテキスト終了後も本人の未確認通知を表示名付きで変換できる")
    void listPendingFetchesUserBeforeMappingOutsidePersistenceContext() {
        Long ownPendingId = insertRecipient(teamNotifAId, teamMemberAId, false, false);
        insertRecipient(teamNotifAId, teamOutsiderId, false, false);
        Long confirmedNotificationId = insertTeamNotification("CNAUTHZ 確認済み境界テスト");
        insertRecipient(confirmedNotificationId, teamMemberAId, true, false);
        Long excludedNotificationId = insertTeamNotification("CNAUTHZ 除外済み境界テスト");
        insertRecipient(excludedNotificationId, teamMemberAId, false, true);
        em.flush();
        em.clear();
        String expectedDisplayName = (String) em.createNativeQuery(
                        "SELECT display_name FROM users WHERE id = :userId")
                .setParameter("userId", teamMemberAId)
                .getSingleResult();
        em.clear();

        List<ConfirmableNotificationRecipientEntity> pending = notificationService.listPending(teamMemberAId);

        // service transaction 終了後に controller へ返るときと同じ、detach 済み境界を作る。
        em.clear();
        List<ConfirmableNotificationRecipientResponse> responses =
                notificationMapper.toRecipientResponseList(pending);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getId()).isEqualTo(ownPendingId);
        assertThat(responses.get(0).getUserId()).isEqualTo(teamMemberAId);
        assertThat(responses.get(0).getDisplayName()).isEqualTo(expectedDisplayName);
    }

    private Long insertTeamNotification(String title) {
        return notificationRepository.save(ConfirmableNotificationEntity.builder()
                        .scopeType(ScopeType.TEAM)
                        .scopeId(teamAId)
                        .title(title)
                        .priority(ConfirmableNotificationPriority.NORMAL)
                        .totalRecipientCount(1)
                        .build())
                .getId();
    }

    private Long insertRecipient(Long notificationId, Long userId, boolean confirmed, boolean excluded) {
        ConfirmableNotificationRecipientEntity recipient = ConfirmableNotificationRecipientEntity.builder()
                .confirmableNotification(em.getReference(ConfirmableNotificationEntity.class, notificationId))
                .user(em.getReference(com.mannschaft.app.auth.entity.UserEntity.class, userId))
                .confirmToken(UUID.randomUUID().toString())
                .isConfirmed(confirmed)
                .excludedAt(excluded ? java.time.LocalDateTime.now() : null)
                .build();
        em.persist(recipient);
        return recipient.getId();
    }

    @Nested
    @DisplayName("NotificationController の本人確認状態（実MySQL・HTTP）")
    class InboxConfirmationState {

        @Test
        @DisplayName("本人のfalseは既読でもfalse、明示確認でtrue、未読戻し後もtrue")
        void 既読と確認状態を独立して保持する() throws Exception {
            Long recipientId = insertRecipient(teamNotifAId, teamMemberAId, false, false);
            // 同じ確認通知に別人のtrueを併存させ、本人の行だけを選ぶ契約も固定する。
            insertRecipient(teamNotifAId, teamOutsiderId, true, false);
            Long inboxId = insertInboxNotification(teamMemberAId, "CONFIRMABLE_NOTIFICATION", teamNotifAId);
            em.flush();
            em.clear();
            setAuth(teamMemberAId);

            assertConfirmation(inboxNotification(inboxId), false);
            mockMvc.perform(post("/api/v1/notifications/{id}/read", inboxId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isRead").value(true))
                    .andExpect(jsonPath("$.data.isConfirmed").value(false));
            em.flush();
            em.clear();
            assertThat(em.find(ConfirmableNotificationRecipientEntity.class, recipientId).getIsConfirmed()).isFalse();
            assertConfirmation(inboxNotification(inboxId), false);

            mockMvc.perform(post("/api/v1/me/confirmable-notifications/{id}/confirm", teamNotifAId))
                    .andExpect(status().isNoContent());
            em.flush();
            em.clear();
            assertThat(em.find(ConfirmableNotificationRecipientEntity.class, recipientId).getIsConfirmed()).isTrue();
            assertConfirmation(inboxNotification(inboxId), true);

            mockMvc.perform(post("/api/v1/notifications/{id}/unread", inboxId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isRead").value(false))
                    .andExpect(jsonPath("$.data.isConfirmed").value(true));
            em.flush();
            em.clear();
            JsonNode unread = inboxNotification(inboxId);
            assertThat(unread.path("isRead").booleanValue()).isFalse();
            assertConfirmation(unread, true);
            assertThat(em.find(ConfirmableNotificationRecipientEntity.class, recipientId).getIsConfirmed()).isTrue();
        }

        @Test
        @DisplayName("通常通知・本人行なし・別人のみ・除外済みはnullで他人宛通知も漏れない")
        void 本人の有効受信者行以外の確認状態を公開しない() throws Exception {
            Long normalId = insertInboxNotification(teamMemberAId, "SYSTEM", teamNotifAId);
            Long missingId = insertInboxNotification(teamMemberAId, "CONFIRMABLE_NOTIFICATION", teamNotifAId);
            Long foreignSourceId = insertTeamNotification("別人だけが受信する確認通知");
            insertRecipient(foreignSourceId, teamOutsiderId, true, false);
            Long foreignId = insertInboxNotification(teamMemberAId, "CONFIRMABLE_NOTIFICATION", foreignSourceId);
            Long excludedSourceId = insertTeamNotification("本人が除外済みの確認通知");
            insertRecipient(excludedSourceId, teamMemberAId, true, true);
            Long excludedId = insertInboxNotification(teamMemberAId, "CONFIRMABLE_NOTIFICATION", excludedSourceId);
            Long othersInboxId = insertInboxNotification(teamOutsiderId, "CONFIRMABLE_NOTIFICATION", foreignSourceId);
            em.flush();
            em.clear();
            setAuth(teamMemberAId);

            JsonNode inbox = inbox();
            assertThat(inbox.size()).isEqualTo(4);
            for (Long id : List.of(normalId, missingId, foreignId, excludedId)) {
                JsonNode notification = notificationById(inbox, id);
                assertThat(notification.has("isConfirmed")).isTrue();
                assertThat(notification.get("isConfirmed").isNull()).isTrue();
            }
            assertThat(inbox.findValues("id")).noneMatch(id -> id.longValue() == othersInboxId);
            mockMvc.perform(post("/api/v1/notifications/{id}/read", othersInboxId))
                    .andExpect(status().isNotFound());
        }

        private JsonNode inbox() throws Exception {
            String body = mockMvc.perform(get("/api/v1/notifications").param("size", "20"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body).path("data");
        }

        private JsonNode inboxNotification(Long id) throws Exception {
            return notificationById(inbox(), id);
        }

        private void assertConfirmation(JsonNode notification, boolean confirmed) {
            assertThat(notification.path("isConfirmed").isBoolean()).isTrue();
            assertThat(notification.path("isConfirmed").booleanValue()).isEqualTo(confirmed);
        }

        private JsonNode notificationById(JsonNode inbox, Long id) {
            for (JsonNode notification : inbox) {
                if (notification.path("id").longValue() == id) {
                    // 欠落フィールドをfalseと読み違えて偽greenにしない。
                    assertThat(notification.path("isConfirmed").isMissingNode()).isFalse();
                    return notification;
                }
            }
            throw new AssertionError("本人の通知が一覧にありません: " + id);
        }

        private Long insertInboxNotification(Long userId, String sourceType, Long sourceId) {
            NotificationEntity notification = NotificationEntity.builder()
                    .userId(userId)
                    .notificationType("RECRUITMENT_PENALTY_APPLIED")
                    .priority(NotificationPriority.URGENT)
                    .title("確認状態の契約テスト")
                    .body("本文")
                    .sourceType(sourceType)
                    .sourceId(sourceId)
                    .scopeType(NotificationScopeType.TEAM)
                    .scopeId(teamAId)
                    .build();
            em.persist(notification);
            return notification.getId();
        }
    }

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
        @DisplayName("正当ADMINは202")
        void 正当ADMINは202() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(orgMemberAId))))
                    .andExpect(status().isAccepted());
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
        @DisplayName("非メンバーは404 NOT_FOUND（不在と同一。W3b で 403 から変更）")
        void 非メンバーは404() throws Exception {
            setAuth(orgOutsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}",
                            orgAId, orgNotifAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当メンバーだがnotificationIdが他組織所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}",
                            orgAId, orgNotifBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
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
        @DisplayName("別scope ADMINは404 NOT_FOUND（越境・不在と同一。W3b で 403 から変更）")
        void 別scopeADMINは404() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/confirmable-notifications/{nid}/cancel",
                            orgAId, orgNotifAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他組織所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(patch("/api/v1/organizations/{id}/confirmable-notifications/{nid}/cancel",
                            orgAId, orgNotifBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
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
        @DisplayName("別scope ADMINは404 NOT_FOUND（越境・不在と同一。W3b で 403 から変更）")
        void 別scopeADMINは404() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/{nid}/resend-reminder",
                            orgAId, orgNotifAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他組織所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications/{nid}/resend-reminder",
                            orgAId, orgNotifBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
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
        @DisplayName("非メンバーは404 NOT_FOUND（不在と同一。W3b で 403 から変更）")
        void 非メンバーは404() throws Exception {
            setAuth(orgOutsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}/recipients",
                            orgAId, orgNotifAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他組織所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notifications/{nid}/recipients",
                            orgAId, orgNotifBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
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

    @Nested
    @DisplayName("組織スコープ 7. GET .../confirmable-notification-settings（設定取得: checkMembership）")
    class OrgSettingsGet {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(orgOutsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notification-settings", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notification-settings", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notification-settings", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notification-settings", orgAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("組織スコープ 8. PUT .../confirmable-notification-settings（設定更新: checkAdminOrAbove）")
    class OrgSettingsUpdate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/confirmable-notification-settings", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(put("/api/v1/organizations/{id}/confirmable-notification-settings", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/confirmable-notification-settings", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("組織スコープ 9. GET .../confirmable-notification-templates（一覧: checkMembership）")
    class OrgTemplateList {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(orgOutsiderId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notification-templates", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notification-templates", orgAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notification-templates", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-notification-templates", orgAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("組織スコープ 10. POST .../confirmable-notification-templates（作成: checkAdminOrAbove）")
    class OrgTemplateCreate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notification-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notification-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notification-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("組織スコープ 11. PUT .../confirmable-notification-templates/{id}（更新: checkAdminOrAbove + scope突合）")
    class OrgTemplateUpdate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/confirmable-notification-templates/{tid}",
                            orgAId, orgTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは404 TEMPLATE_NOT_FOUND（越境・不在と同一。W3b で 403 から変更）")
        void 別scopeADMINは404() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(put("/api/v1/organizations/{id}/confirmable-notification-templates/{tid}",
                            orgAId, orgTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがtemplateIdが他組織所属は404（BOLA）")
        void templateId越境は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/confirmable-notification-templates/{tid}",
                            orgAId, orgTemplateBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(put("/api/v1/organizations/{id}/confirmable-notification-templates/{tid}",
                            orgAId, orgTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("組織スコープ 12. DELETE .../confirmable-notification-templates/{id}（削除: checkAdminOrAbove + scope突合）")
    class OrgTemplateDelete {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(orgMemberAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/confirmable-notification-templates/{tid}",
                            orgAId, orgTemplateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは404 TEMPLATE_NOT_FOUND（越境・不在と同一。W3b で 403 から変更）")
        void 別scopeADMINは404() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/confirmable-notification-templates/{tid}",
                            orgAId, orgTemplateAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがtemplateIdが他組織所属は404（BOLA）")
        void templateId越境は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/confirmable-notification-templates/{tid}",
                            orgAId, orgTemplateBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(delete("/api/v1/organizations/{id}/confirmable-notification-templates/{tid}",
                            orgAId, orgTemplateAId))
                    .andExpect(status().isNoContent());
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
        @DisplayName("正当ADMINは202")
        void 正当ADMINは202() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody(teamMemberAId))))
                    .andExpect(status().isAccepted());
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
        @DisplayName("非メンバーは404 NOT_FOUND（不在と同一。W3b で 403 から変更）")
        void 非メンバーは404() throws Exception {
            setAuth(teamOutsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}",
                            teamAId, teamNotifAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当メンバーだがnotificationIdが他チーム所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}",
                            teamAId, teamNotifBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
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
        @DisplayName("別scope ADMINは404 NOT_FOUND（越境・不在と同一。W3b で 403 から変更）")
        void 別scopeADMINは404() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(patch("/api/v1/teams/{id}/confirmable-notifications/{nid}/cancel",
                            teamAId, teamNotifAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他チーム所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(patch("/api/v1/teams/{id}/confirmable-notifications/{nid}/cancel",
                            teamAId, teamNotifBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
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
        @DisplayName("別scope ADMINは404 NOT_FOUND（越境・不在と同一。W3b で 403 から変更）")
        void 別scopeADMINは404() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/{nid}/resend-reminder",
                            teamAId, teamNotifAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他チーム所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notifications/{nid}/resend-reminder",
                            teamAId, teamNotifBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
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
        @DisplayName("非メンバーは404 NOT_FOUND（不在と同一。W3b で 403 から変更）")
        void 非メンバーは404() throws Exception {
            setAuth(teamOutsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}/recipients",
                            teamAId, teamNotifAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがnotificationIdが他チーム所属は404（BOLA）")
        void notificationId越境は404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notifications/{nid}/recipients",
                            teamAId, teamNotifBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_NOT_FOUND"));
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

    @Nested
    @DisplayName("チームスコープ 7. GET .../confirmable-notification-settings（設定取得: checkMembership）")
    class TeamSettingsGet {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(teamOutsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notification-settings", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notification-settings", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notification-settings", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notification-settings", teamAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("チームスコープ 8. PUT .../confirmable-notification-settings（設定更新: checkAdminOrAbove）")
    class TeamSettingsUpdate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(put("/api/v1/teams/{id}/confirmable-notification-settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(put("/api/v1/teams/{id}/confirmable-notification-settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(put("/api/v1/teams/{id}/confirmable-notification-settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("チームスコープ 9. GET .../confirmable-notification-templates（一覧: checkMembership）")
    class TeamTemplateList {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(teamOutsiderId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notification-templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notification-templates", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notification-templates", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get("/api/v1/teams/{id}/confirmable-notification-templates", teamAId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("チームスコープ 10. POST .../confirmable-notification-templates（作成: checkAdminOrAbove）")
    class TeamTemplateCreate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notification-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（越境）")
        void 別scopeADMINは403() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notification-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post("/api/v1/teams/{id}/confirmable-notification-templates", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("チームスコープ 11. PUT .../confirmable-notification-templates/{id}（更新: checkAdminOrAbove + scope突合）")
    class TeamTemplateUpdate {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(put("/api/v1/teams/{id}/confirmable-notification-templates/{tid}",
                            teamAId, teamTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは404 TEMPLATE_NOT_FOUND（越境・不在と同一。W3b で 403 から変更）")
        void 別scopeADMINは404() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(put("/api/v1/teams/{id}/confirmable-notification-templates/{tid}",
                            teamAId, teamTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがtemplateIdが他チーム所属は404（BOLA）")
        void templateId越境は404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(put("/api/v1/teams/{id}/confirmable-notification-templates/{tid}",
                            teamAId, teamTemplateBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINは200")
        void 正当ADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(put("/api/v1/teams/{id}/confirmable-notification-templates/{tid}",
                            teamAId, teamTemplateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody())))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("チームスコープ 12. DELETE .../confirmable-notification-templates/{id}（削除: checkAdminOrAbove + scope突合）")
    class TeamTemplateDelete {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(delete("/api/v1/teams/{id}/confirmable-notification-templates/{tid}",
                            teamAId, teamTemplateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは404 TEMPLATE_NOT_FOUND（越境・不在と同一。W3b で 403 から変更）")
        void 別scopeADMINは404() throws Exception {
            setAuth(teamAdminBId);
            mockMvc.perform(delete("/api/v1/teams/{id}/confirmable-notification-templates/{tid}",
                            teamAId, teamTemplateAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINだがtemplateIdが他チーム所属は404（BOLA）")
        void templateId越境は404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(delete("/api/v1/teams/{id}/confirmable-notification-templates/{tid}",
                            teamAId, teamTemplateBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CONFIRMABLE_NOTIFICATION_TEMPLATE_NOT_FOUND"));
        }

        @Test
        @DisplayName("正当ADMINは204")
        void 正当ADMINは204() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(delete("/api/v1/teams/{id}/confirmable-notification-templates/{tid}",
                            teamAId, teamTemplateAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> templateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "CNAUTHZ テンプレート");
        body.put("title", "CNAUTHZ テンプレートタイトル");
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

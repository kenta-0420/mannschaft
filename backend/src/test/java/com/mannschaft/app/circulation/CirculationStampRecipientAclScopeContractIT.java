package com.mannschaft.app.circulation;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 回覧板の押印系・コメント削除・あて先増減・組織スコープ管理操作の認可 API 契約テスト
 * （認可根治 Wave4 ロット B）。
 *
 * <p>本テストが固定する対象:</p>
 * <ul>
 *   <li>{@code CirculationStampController#stamp} / {@code #skip} / {@code #reject} /
 *       {@code #correctStamp} / {@code #delegateStamp} —
 *       当該文書に登録された受信者本人のみが実行できる（{@code CirculationAccessGuard}）</li>
 *   <li>{@code CirculationCommentController#deleteComment} — 当該文書に属する自分のコメントのみ削除できる</li>
 *   <li>{@code CirculationRecipientController#addRecipients} / {@code #removeRecipient} —
 *       当該文書スコープの管理者のみ</li>
 *   <li>{@code OrgCirculationDocumentController#updateDocument} / {@code #activateDocument} /
 *       {@code #cancelDocument} / {@code #deleteDocument} —
 *       組織スコープ側も {@code TeamCirculationDocumentController} と同一の判定を通る</li>
 *   <li>{@code MyCirculationController#listCreatedDocuments} — 自己スコープ（作成者が認証主体に束縛される）</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("回覧板 押印/コメント/あて先/組織スコープ 認可 API 契約テスト（認可根治 Wave4 ロットB）")
class CirculationStampRecipientAclScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long orgAId;
    private Long orgBId;

    private Long teamAdminId;
    private Long orgAdminAId;
    private Long orgAdminBId;
    /** 文書の作成者（チーム会員だが管理者ではない）。 */
    private Long authorId;
    /** 文書の受信者。 */
    private Long recipientId;
    /** 同じ文書のもう一人の受信者。 */
    private Long otherRecipientId;
    /** どのスコープにも属さない部外者。 */
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("回覧押印契約チームA");
        orgAId = insertOrganization("回覧押印契約 組織A");
        orgBId = insertOrganization("回覧押印契約 組織B");

        teamAdminId = insertUser("circ-lotb-team-admin@example.com");
        orgAdminAId = insertUser("circ-lotb-org-admin-a@example.com");
        orgAdminBId = insertUser("circ-lotb-org-admin-b@example.com");
        authorId = insertUser("circ-lotb-author@example.com");
        recipientId = insertUser("circ-lotb-recipient@example.com");
        otherRecipientId = insertUser("circ-lotb-recipient2@example.com");
        outsiderId = insertUser("circ-lotb-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        MembershipTestHelper.insertUserRole(em, orgAdminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, orgAdminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, orgAdminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 押印系: 受信者本人のみ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("押印(stamp)")
    class Stamp {

        @Test
        @DisplayName("受信者でない利用者の押印は拒否される")
        void 受信者でない利用者の押印は拒否される() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stampBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CIRCULATION_002"));
        }

        @Test
        @DisplayName("文書の作成者であっても受信者でなければ押印できない")
        void 作成者でも受信者でなければ押印できない() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(authorId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stampBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CIRCULATION_002"));
        }

        @Test
        @DisplayName("受信者本人の押印は200")
        void 受信者本人の押印は200() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(recipientId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(stampBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("STAMPED"));
        }
    }

    @Nested
    @DisplayName("スキップ(skip) / 拒否(reject)")
    class SkipAndReject {

        @Test
        @DisplayName("受信者でない利用者のスキップは拒否される")
        void 受信者でない利用者のスキップは拒否される() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp/skip", docId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CIRCULATION_002"));
        }

        @Test
        @DisplayName("受信者本人のスキップは200")
        void 受信者本人のスキップは200() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(recipientId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp/skip", docId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("SKIPPED"));
        }

        @Test
        @DisplayName("受信者でない利用者の拒否操作は遮断される")
        void 受信者でない利用者の拒否操作は遮断される() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp/reject", docId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CIRCULATION_002"));
        }

        @Test
        @DisplayName("受信者本人の拒否は200")
        void 受信者本人の拒否は200() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(recipientId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp/reject", docId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJECTED"));
        }
    }

    @Nested
    @DisplayName("押印訂正(correctStamp)")
    class CorrectStamp {

        @Test
        @DisplayName("他の受信者は他人の押印を訂正できない")
        void 他の受信者は他人の押印を訂正できない() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertStampedRecipient(docId, recipientId);

            setAuthentication(otherRecipientId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp/correct", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"押し直し\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CIRCULATION_002"));
        }

        @Test
        @DisplayName("押印者本人の訂正は200でPENDINGに戻る")
        void 押印者本人の訂正は200() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertStampedRecipient(docId, recipientId);

            setAuthentication(recipientId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp/correct", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"押し直し\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }
    }

    @Nested
    @DisplayName("押印委任(delegateStamp)")
    class DelegateStamp {

        @Test
        @DisplayName("受信者でない利用者の委任は拒否される")
        void 受信者でない利用者の委任は拒否される() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp/delegate", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateeUserId\":" + otherRecipientId + "}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CIRCULATION_002"));
        }

        @Test
        @DisplayName("受信者本人の委任は200")
        void 受信者本人の委任は200() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(recipientId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/stamp/delegate", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateeUserId\":" + otherRecipientId + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.delegateeUserId").value(otherRecipientId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // コメント削除: 投稿者本人のみ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("コメント削除(deleteComment)")
    class DeleteComment {

        @Test
        @DisplayName("他人のコメントは削除できない")
        void 他人のコメントは削除できない() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            insertRecipient(docId, recipientId, "PENDING");
            Long commentId = insertComment(docId, recipientId);

            setAuthentication(authorId);
            mockMvc.perform(delete("/api/v1/circulations/{documentId}/comments/{commentId}", docId, commentId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CIRCULATION_010"));
        }

        @Test
        @DisplayName("別文書のパスに他文書のコメントIDを差し込むと404")
        void 文書をまたぐコメントIDの差し込みは404() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            Long otherDocId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            Long commentId = insertComment(docId, recipientId);

            setAuthentication(recipientId);
            mockMvc.perform(delete("/api/v1/circulations/{documentId}/comments/{commentId}",
                            otherDocId, commentId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CIRCULATION_004"));
        }

        @Test
        @DisplayName("投稿者本人の削除は204")
        void 投稿者本人の削除は204() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "ACTIVE");
            Long commentId = insertComment(docId, recipientId);

            setAuthentication(recipientId);
            mockMvc.perform(delete("/api/v1/circulations/{documentId}/comments/{commentId}", docId, commentId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // あて先の増減: 当該文書スコープの管理者のみ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("あて先追加(addRecipients) / 削除(removeRecipient)")
    class Recipients {

        @Test
        @DisplayName("非管理者のあて先追加は403")
        void 非管理者のあて先追加は403() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "DRAFT");

            setAuthentication(authorId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/recipients", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(addRecipientsBody(recipientId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("当該スコープ管理者のあて先追加は201")
        void 当該スコープ管理者のあて先追加は201() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "DRAFT");

            setAuthentication(teamAdminId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/recipients", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(addRecipientsBody(recipientId))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非管理者のあて先削除は403")
        void 非管理者のあて先削除は403() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "DRAFT");
            insertRecipient(docId, recipientId, "PENDING");
            Long recipientRowId = findRecipientRowId(docId, recipientId);

            setAuthentication(authorId);
            mockMvc.perform(delete("/api/v1/circulations/{documentId}/recipients/{recipientId}",
                            docId, recipientRowId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("当該スコープ管理者のあて先削除は204")
        void 当該スコープ管理者のあて先削除は204() throws Exception {
            Long docId = insertDocument("TEAM", teamAId, authorId, "DRAFT");
            insertRecipient(docId, recipientId, "PENDING");
            Long recipientRowId = findRecipientRowId(docId, recipientId);

            setAuthentication(teamAdminId);
            mockMvc.perform(delete("/api/v1/circulations/{documentId}/recipients/{recipientId}",
                            docId, recipientRowId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 組織スコープの文書ライフサイクル管理（チームスコープと同一判定であることの確認）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("組織回覧の更新/公開/キャンセル/削除")
    class OrgDocumentLifecycle {

        @Test
        @DisplayName("別組織ADMINの更新は403（実体由来スコープで判定する）")
        void 別組織ADMINの更新は403() throws Exception {
            Long docId = insertDocument("ORGANIZATION", orgAId, authorId, "DRAFT");

            setAuthentication(orgAdminBId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/circulations/{id}", orgAId, docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"乗っ取り\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("当該組織ADMINの更新は200")
        void 当該組織ADMINの更新は200() throws Exception {
            Long docId = insertDocument("ORGANIZATION", orgAId, authorId, "DRAFT");

            setAuthentication(orgAdminAId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/circulations/{id}", orgAId, docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"組織回覧・改題済\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("組織回覧・改題済"));
        }

        @Test
        @DisplayName("別組織ADMINの公開は403")
        void 別組織ADMINの公開は403() throws Exception {
            Long docId = insertDocument("ORGANIZATION", orgAId, authorId, "DRAFT");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(orgAdminBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/circulations/{id}/activate", orgAId, docId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("当該組織ADMINの公開は200")
        void 当該組織ADMINの公開は200() throws Exception {
            Long docId = insertDocument("ORGANIZATION", orgAId, authorId, "DRAFT");
            insertRecipient(docId, recipientId, "PENDING");

            setAuthentication(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/circulations/{id}/activate", orgAId, docId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("別組織ADMINのキャンセルは403")
        void 別組織ADMINのキャンセルは403() throws Exception {
            Long docId = insertDocument("ORGANIZATION", orgAId, authorId, "DRAFT");

            setAuthentication(orgAdminBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/circulations/{id}/cancel", orgAId, docId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("当該組織ADMINのキャンセルは200")
        void 当該組織ADMINのキャンセルは200() throws Exception {
            Long docId = insertDocument("ORGANIZATION", orgAId, authorId, "DRAFT");

            setAuthentication(orgAdminAId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/circulations/{id}/cancel", orgAId, docId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("別組織ADMINの削除は403")
        void 別組織ADMINの削除は403() throws Exception {
            Long docId = insertDocument("ORGANIZATION", orgAId, authorId, "DRAFT");

            setAuthentication(orgAdminBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/circulations/{id}", orgAId, docId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("当該組織ADMINの削除は204")
        void 当該組織ADMINの削除は204() throws Exception {
            Long docId = insertDocument("ORGANIZATION", orgAId, authorId, "DRAFT");

            setAuthentication(orgAdminAId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/circulations/{id}", orgAId, docId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 自己スコープ: MyCirculationController#listCreatedDocuments
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("自分が作成した回覧一覧(MyCirculationController#listCreatedDocuments) の自己スコープ性")
    class MyCreatedDocuments {

        /**
         * {@code MyCirculationController#listCreatedDocuments} は作成者を認証主体から解決するため、
         * 他人が作成した文書は誰が呼んでも返らない。リクエストは page / size のみを受け取り、
         * 作成者を指定するパラメータを持たない。
         */
        @Test
        @DisplayName("他人が作成した文書は返らず、自分の作成分のみが返る")
        void 自分の作成分のみ返る() throws Exception {
            insertDocument("TEAM", teamAId, authorId, "ACTIVE");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/circulations/created"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(authorId);
            mockMvc.perform(get("/api/v1/me/circulations/created"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> stampBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sealId", 1);
        body.put("tiltAngle", 0);
        body.put("isFlipped", false);
        return body;
    }

    private Map<String, Object> addRecipientsBody(Long userId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("userId", userId);
        entry.put("sortOrder", 0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipients", List.of(entry));
        return body;
    }

    /**
     * circulation_documents へ直接 INSERT する。
     *
     * <p>test profile は {@code ddl-auto=create}（Flyway 無効）でスキーマを Entity から生成するため、
     * 生 SQL では NOT NULL 列を全て明示的に埋める（{@code @Builder.Default} の初期値は
     * Java 側でのみ効き DDL の DEFAULT には反映されない）。</p>
     */
    private Long insertDocument(String scopeType, Long scopeId, Long createdBy, String status) {
        String title = "ロットB契約テスト文書 " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO circulation_documents "
                                + "(scope_type, scope_id, created_by, title, body, "
                                + "circulation_mode, sequential_count, status, priority, "
                                + "reminder_enabled, reminder_interval_hours, stamp_display_style, "
                                + "total_recipient_count, stamped_count, attachment_count, comment_count, "
                                + "export_status, created_at, updated_at) "
                                + "VALUES (:scopeType, :scopeId, :createdBy, :title, '本文', "
                                + "'SIMULTANEOUS', 0, :status, 'NORMAL', "
                                + "0, 24, 'STANDARD', "
                                + "1, 0, 0, 0, "
                                + "'NOT_GENERATED', NOW(), NOW())")
                .setParameter("scopeType", scopeType)
                .setParameter("scopeId", scopeId)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM circulation_documents WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    private void insertRecipient(Long documentId, Long userId, String status) {
        em.createNativeQuery(
                        "INSERT INTO circulation_recipients "
                                + "(document_id, user_id, sort_order, status, tilt_angle, is_flipped, "
                                + "created_at, updated_at) "
                                + "VALUES (:docId, :userId, 0, :status, 0, 0, NOW(), NOW())")
                .setParameter("docId", documentId)
                .setParameter("userId", userId)
                .setParameter("status", status)
                .executeUpdate();
    }

    /** 押印済み（24 時間の訂正可能期間内）の受信者行を作る。 */
    private void insertStampedRecipient(Long documentId, Long userId) {
        em.createNativeQuery(
                        "INSERT INTO circulation_recipients "
                                + "(document_id, user_id, sort_order, status, seal_id, tilt_angle, is_flipped, "
                                + "stamped_at, created_at, updated_at) "
                                + "VALUES (:docId, :userId, 0, 'STAMPED', 1, 0, 0, "
                                + "NOW(), NOW(), NOW())")
                .setParameter("docId", documentId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private Long findRecipientRowId(Long documentId, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM circulation_recipients "
                                + "WHERE document_id = :docId AND user_id = :userId")
                .setParameter("docId", documentId)
                .setParameter("userId", userId)
                .getSingleResult()).longValue();
    }

    private Long insertComment(Long documentId, Long userId) {
        String body = "契約テストコメント " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO circulation_comments "
                                + "(document_id, user_id, body, created_at, updated_at) "
                                + "VALUES (:docId, :userId, :body, NOW(), NOW())")
                .setParameter("docId", documentId)
                .setParameter("userId", userId)
                .setParameter("body", body)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM circulation_comments WHERE body = :body")
                .setParameter("body", body)
                .getSingleResult()).longValue();
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
                                + "VALUES (:email, 'ロットB', 'テスト', 'ロットB契約テスト', 'ACTIVE', "
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
                                + "CONCAT('circb-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('circb-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}

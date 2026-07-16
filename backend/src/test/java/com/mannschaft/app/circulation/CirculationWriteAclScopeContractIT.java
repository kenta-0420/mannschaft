package com.mannschaft.app.circulation;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Duration;
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
 * 認可根治戦役 Wave3-B8: circulation ドメイン（受信者ACL・per-scope 管理者認可）の API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B8 circulation 節）・{@code CirculationService}
 * （{@code checkScopeAdminAccess}/{@code checkAttachmentManageAccess}）・
 * {@code ContentVisibilityChecker#assertCanView}（{@code CIRCULATION_DOCUMENT}）。
 * 金型: {@code GalleryScopeContractIT}（entity 由来 scope で認可判定）。</p>
 *
 * <p>circulation は 2 系統の認可モデルを持つ:</p>
 * <ul>
 *   <li><b>受信者ACL</b>（scope membership ではない）: listAttachments/listRecipients(BOLA)/
 *       listComments/createComment は「作成者 or 受信者 or SystemAdmin」のみ許可
 *       （{@link com.mannschaft.app.common.visibility.ContentVisibilityChecker#assertCanView}）。</li>
 *   <li><b>per-scope 管理者認可</b>: createDocument はメンバーのみ、update/activate/cancel/delete/
 *       getStats は当該文書スコープの ADMIN/DEPUTY_ADMIN のみ。添付の追加/presign/削除は
 *       作成者 or 当該スコープの管理者のみ。</li>
 * </ul>
 *
 * <p>R2StorageService は外部依存のため {@code @MockitoBean} でモックする（presign-upload で使用）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("circulation ドメイン 受信者ACL/per-scope認可 API 契約テスト（認可根治 Wave3-B8）")
class CirculationWriteAclScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** R2 は外部依存のため mock（presign-upload で使用）。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    /** teamA の非ADMINメンバー。文書作成者・添付管理不能者として使う。 */
    private Long memberAId;
    /** どのスコープにも所属しない完全な部外者。 */
    private Long outsiderId;
    /** 特定文書の受信者にのみ登録され、teamA の scope membership は一切持たないユーザー
     *  （受信者ACLが scope membership と独立であることを実証する）。 */
    private Long recipientOnlyId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CIRC認可契約チームA");
        teamBId = insertTeam("CIRC認可契約チームB");

        adminAId = insertUser("circ-authz-admin-a@example.com");
        adminBId = insertUser("circ-authz-admin-b@example.com");
        memberAId = insertUser("circ-authz-member-a@example.com");
        outsiderId = insertUser("circ-authz-outsider@example.com");
        recipientOnlyId = insertUser("circ-authz-recipient-only@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        em.flush();
        em.clear();

        given(r2StorageService.generateUploadUrl(anyString(), anyString(), any(Duration.class)))
                .willReturn(new PresignedUploadResult("https://r2.example.com/upload-dummy", "dummy-key", 900L));
    }

    // ═════════════════════════════════════════════════════════════════════
    // createDocument: 起票は当該スコープのメンバーのみ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("文書作成(createDocument)")
    class CreateDocument {

        @Test
        @DisplayName("非会員の作成は403（checkMembership）")
        void 非会員の作成は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/circulations", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createDocumentBody(recipientOnlyId))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("会員の作成は201")
        void 会員の作成は201() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/circulations", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createDocumentBody(recipientOnlyId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 文書ライフサイクル管理: update/activate/cancel/delete/getStats
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("文書更新(updateDocument)")
    class UpdateDocument {

        @Test
        @DisplayName("非ADMINメンバーの更新は403")
        void 非ADMINメンバーの更新は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/circulations/{id}", teamAId, docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDocumentBody("改題"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの更新は403（entity由来scopeで認可判定）")
        void 他チームADMINの更新は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(adminBId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/circulations/{id}", teamAId, docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDocumentBody("乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/teams/{teamId}/circulations/{id}", teamAId, docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDocumentBody("改題済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("改題済"));
        }
    }

    @Nested
    @DisplayName("文書公開(activateDocument)")
    class ActivateDocument {

        @Test
        @DisplayName("非ADMINメンバーの公開は403")
        void 非ADMINメンバーの公開は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/circulations/{id}/activate", teamAId, docId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの公開は200")
        void 正当ADMINの公開は200() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/circulations/{id}/activate", teamAId, docId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }
    }

    @Nested
    @DisplayName("文書キャンセル(cancelDocument)")
    class CancelDocument {

        @Test
        @DisplayName("非ADMINメンバーのキャンセルは403")
        void 非ADMINメンバーのキャンセルは403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/circulations/{id}/cancel", teamAId, docId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのキャンセルは200")
        void 正当ADMINのキャンセルは200() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/circulations/{id}/cancel", teamAId, docId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }
    }

    @Nested
    @DisplayName("文書削除(deleteDocument)")
    class DeleteDocument {

        @Test
        @DisplayName("非ADMINメンバーの削除は403")
        void 非ADMINメンバーの削除は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/circulations/{id}", teamAId, docId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/circulations/{id}", teamAId, docId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("統計取得(getStats)")
    class GetStats {

        @Test
        @DisplayName("非ADMINメンバーの統計取得は403")
        void 非ADMINメンバーの統計取得は403() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/circulations/stats", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの統計取得は200")
        void 正当ADMINの統計取得は200() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/circulations/stats", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").exists());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 受信者ACL(BOLA根治): listRecipients / listAttachments / listComments / createComment
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("受信者一覧(listRecipients) — BOLA根治")
    class ListRecipients {

        @Test
        @DisplayName("非受信者かつ非作成者は403（documentIdを知るだけでは受信者一覧を閲覧できない）")
        void 非受信者かつ非作成者は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/recipients", docId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));
        }

        @Test
        @DisplayName("受信者は200")
        void 受信者は200() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(recipientOnlyId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/recipients", docId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("作成者本人は200（受信者未登録でも自分の起票文書は閲覧可）")
        void 作成者本人は200() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/recipients", docId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("添付ファイル一覧(listAttachments)")
    class ListAttachments {

        @Test
        @DisplayName("非受信者かつ非作成者は403")
        void 非受信者かつ非作成者は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/attachments", docId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));
        }

        @Test
        @DisplayName("受信者は200")
        void 受信者は200() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(recipientOnlyId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/attachments", docId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("コメント一覧(listComments)・作成(createComment)")
    class Comments {

        @Test
        @DisplayName("非受信者かつ非作成者のコメント一覧取得は403")
        void 非受信者かつ非作成者のコメント一覧取得は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/comments", docId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));
        }

        @Test
        @DisplayName("受信者のコメント一覧取得は200")
        void 受信者のコメント一覧取得は200() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(recipientOnlyId);
            mockMvc.perform(get("/api/v1/circulations/{documentId}/comments", docId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非受信者かつ非作成者のコメント作成は403")
        void 非受信者かつ非作成者のコメント作成は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/comments", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createCommentBody("不正コメント"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VISIBILITY_001"));
        }

        @Test
        @DisplayName("受信者のコメント作成は201（読取ACLと同一 = 受信者もコメント可）")
        void 受信者のコメント作成は201() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "ACTIVE");
            insertRecipient(docId, recipientOnlyId);

            setAuthentication(recipientOnlyId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/comments", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createCommentBody("受信者コメント"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.body").value("受信者コメント"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 添付ファイル管理: addAttachment / presignAttachmentUpload / removeAttachment
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("添付ファイル追加(addAttachment)")
    class AddAttachment {

        @Test
        @DisplayName("非作成者かつ非管理者の追加は403")
        void 非作成者かつ非管理者の追加は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/attachments", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createAttachmentBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("作成者本人の追加は201")
        void 作成者本人の追加は201() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/attachments", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createAttachmentBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("当該スコープADMINの追加は201（作成者本人でなくても管理者は可）")
        void スコープADMINの追加は201() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/attachments", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createAttachmentBody())))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("添付アップロードURL発行(presignAttachmentUpload)")
    class PresignAttachmentUpload {

        @Test
        @DisplayName("非作成者かつ非管理者のpresign発行は403")
        void 非作成者かつ非管理者のpresign発行は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/attachments/upload-url", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(presignBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("作成者本人のpresign発行は200")
        void 作成者本人のpresign発行は200() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/circulations/{documentId}/attachments/upload-url", docId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(presignBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.uploadUrl").exists());
        }
    }

    @Nested
    @DisplayName("添付ファイル削除(removeAttachment)")
    class RemoveAttachment {

        @Test
        @DisplayName("非作成者かつ非管理者の削除は403")
        void 非作成者かつ非管理者の削除は403() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");
            Long attachmentId = insertAttachment(docId);

            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/circulations/{documentId}/attachments/{attachmentId}",
                            docId, attachmentId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("作成者本人の削除は204")
        void 作成者本人の削除は204() throws Exception {
            Long docId = insertDocument(teamAId, memberAId, "DRAFT");
            Long attachmentId = insertAttachment(docId);

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/circulations/{documentId}/attachments/{attachmentId}",
                            docId, attachmentId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> createDocumentBody(Long recipientUserId) {
        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("userId", recipientUserId);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "認可契約テスト文書 " + System.nanoTime());
        body.put("body", "本文");
        body.put("recipients", List.of(recipient));
        return body;
    }

    private Map<String, Object> updateDocumentBody(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        return body;
    }

    private Map<String, Object> createCommentBody(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("body", text);
        return body;
    }

    private Map<String, Object> createAttachmentBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileKey", "circulation/TEAM/authz/" + System.nanoTime() + ".pdf");
        body.put("originalFilename", "test.pdf");
        body.put("fileSize", 1024);
        body.put("mimeType", "application/pdf");
        return body;
    }

    private Map<String, Object> presignBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileName", "test.pdf");
        body.put("contentType", "application/pdf");
        body.put("fileSize", 1024);
        return body;
    }

    /**
     * circulation_documents へ直接 INSERT する（scopeType は TEAM 固定）。
     *
     * <p>test profile は {@code ddl-auto=create}（Flyway 無効）でスキーマを Entity から生成するため、
     * Hibernate は {@code @Column(nullable=false)} を <b>SQL DEFAULT 無し</b>の NOT NULL 列として作る
     * （{@code @Builder.Default} の初期値は Java 側でのみ効き、DDL の DEFAULT には反映されない）。
     * よって生 SQL INSERT では NOT NULL 列を全て明示的に埋める必要がある
     * （enum 列は {@code EnumType.STRING} なので enum 名の文字列を投入する）。
     * NULL 許容列（due_date / completed_at / export_* 等）と、
     * Entity に存在しない列（post_announcement_on_start 等・migration のみで Entity 未定義のため
     * ddl-auto=create 下では列自体が存在しない）は投入しない。</p>
     */
    private Long insertDocument(Long scopeId, Long createdBy, String status) {
        String title = "契約テスト文書 " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO circulation_documents "
                                + "(scope_type, scope_id, created_by, title, body, "
                                + "circulation_mode, sequential_count, status, priority, "
                                + "reminder_enabled, reminder_interval_hours, stamp_display_style, "
                                + "total_recipient_count, stamped_count, attachment_count, comment_count, "
                                + "export_status, created_at, updated_at) "
                                + "VALUES ('TEAM', :scopeId, :createdBy, :title, '本文', "
                                + "'SIMULTANEOUS', 0, :status, 'NORMAL', "
                                + "0, 24, 'STANDARD', "
                                + "0, 0, 0, 0, "
                                + "'NOT_GENERATED', NOW(), NOW())")
                .setParameter("scopeId", scopeId)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM circulation_documents WHERE title = :title")
                        .setParameter("title", title)
                        .getSingleResult()).longValue();
    }

    /**
     * circulation_recipients へ受信者を 1 行 INSERT する。
     *
     * <p>{@link #insertDocument} と同じ理由で NOT NULL 列を全て埋める
     * （tilt_angle / is_flipped も {@code @Column(nullable=false)}。
     * is_proxy_confirmed は Entity 側で {@code columnDefinition="TINYINT(1) DEFAULT 0"} を持つため省略可）。</p>
     */
    private void insertRecipient(Long documentId, Long userId) {
        em.createNativeQuery(
                        "INSERT INTO circulation_recipients "
                                + "(document_id, user_id, sort_order, status, tilt_angle, is_flipped, "
                                + "created_at, updated_at) "
                                + "VALUES (:docId, :userId, 0, 'PENDING', 0, 0, NOW(), NOW())")
                .setParameter("docId", documentId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    /** circulation_attachments へ添付を 1 行 INSERT する。 */
    private Long insertAttachment(Long documentId) {
        String fileKey = "circulation/TEAM/seed/" + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO circulation_attachments "
                                + "(document_id, file_key, original_filename, file_size, mime_type, created_at) "
                                + "VALUES (:docId, :fileKey, 'seed.pdf', 100, 'application/pdf', NOW())")
                .setParameter("docId", documentId)
                .setParameter("fileKey", fileKey)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM circulation_attachments WHERE file_key = :fileKey")
                        .setParameter("fileKey", fileKey)
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
                                + "VALUES (:email, 'CIRC契約', 'テスト', 'CIRC契約テスト', 'ACTIVE', "
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
                                + "CONCAT('circ-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}

package com.mannschaft.app.proxyvote;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.3 議決権行使・委任状 認可 API 契約テスト（試練 / 認可根治 Wave 2 トランシェ 2A #5）。
 *
 * <p>正本: {@code docs/features/F08.3_voting_proxy.md}。台帳: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}。
 * proxyvote ドメインは全 EP に認可が無かったため、以下の認可を敷設する:</p>
 * <ul>
 *   <li><b>castVote</b>（議決権行使）: セッションスコープの会員のみ（票の水増し防止）。非会員は 403。</li>
 *   <li><b>getSession / getResults / listSessions</b>（閲覧）: 会員のみ。</li>
 *   <li><b>createSession</b>（作成）/ <b>open/close/finalize/delete/updateSession/addMotion/deleteMotion</b>
 *       （ライフサイクル・議案変更）: 作成者またはスコープ管理者のみ。</li>
 *   <li><b>reviewDelegation</b>（委任状承認/却下）: スコープ管理者のみ。</li>
 *   <li><b>exportResultsCsv / exportMinutesPdf</b>（エクスポート）: 会員のみ。</li>
 * </ul>
 *
 * <p><b>BOLA 厳禁</b>: sessionId / delegationId は必ず entity 由来スコープで認可する。
 * 別チーム（scope）の ADMIN が他チームのセッションを叩いても 403 になることを検証する。</p>
 *
 * <p>金型: {@code TeamAdvertiserScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + ネイティブ SQL フィクスチャ + {@code @Transactional} ロールバック）。
 * ID は AUTO_INCREMENT の生成値を使うため固定 ID 衝突は起きない。roles 等のグローバル参照表は削除しない。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F08.3 議決権行使 認可 API 契約テスト（試練）")
class ProxyVoteAuthzContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long memberAId;   // teamA の一般会員（MEMBER）
    private Long adminAId;    // teamA の ADMIN 兼作成者
    private Long adminBId;    // teamB の ADMIN（teamA から見れば越境者）
    private Long outsiderId;  // どこにも所属しない非会員

    private Long draftSessionId;      // DRAFT（open/update/delete/addMotion/deleteMotion 用）
    private Long openSessionId;       // OPEN（cast/getSession/getResults 用）
    private Long closedSessionId;     // CLOSED（CSV エクスポート用）
    private Long finalizedSessionId;  // FINALIZED（PDF エクスポート用）
    private Long openMotionId;        // openSession の議案（VOTING）
    private Long draftMotionId;       // draftSession の議案（deleteMotion 用）
    private Long submittedDelegationId; // openSession への SUBMITTED 委任状（review 用）

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("PV認可 チームA");
        teamBId = insertTeam("PV認可 チームB");

        memberAId = insertUser("pv-authz-member-a@example.com");
        adminAId = insertUser("pv-authz-admin-a@example.com");
        adminBId = insertUser("pv-authz-admin-b@example.com");
        outsiderId = insertUser("pv-authz-outsider@example.com");

        // 所属（memberships）と権限ロール（user_roles）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);

        // セッション（全て teamA・作成者 adminA）
        draftSessionId = insertSession(teamAId, "DRAFT総会", "WRITTEN", "DRAFT", adminAId);
        openSessionId = insertSession(teamAId, "OPEN総会", "MEETING", "OPEN", adminAId);
        closedSessionId = insertSession(teamAId, "CLOSED総会", "MEETING", "CLOSED", adminAId);
        finalizedSessionId = insertSession(teamAId, "FINALIZED総会", "MEETING", "FINALIZED", adminAId);

        draftMotionId = insertMotion(draftSessionId, "PENDING");
        openMotionId = insertMotion(openSessionId, "VOTING");
        insertMotion(closedSessionId, "VOTED");
        insertMotion(finalizedSessionId, "VOTED");

        // openSession への SUBMITTED 委任状（memberA → adminA）
        submittedDelegationId = insertDelegation(openSessionId, memberAId, adminAId);

        em.flush();
        em.clear();
    }

    // ═══════════════════════════════════════════════════════════
    // castVote（議決権行使）— 会員のみ・票の水増し防止
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("castVote 認可")
    class CastVote {

        @Test
        @DisplayName("非会員（outsider）の投票は 403（票の水増し防止）")
        void 非会員の投票は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/proxy-votes/{id}/cast", openSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(voteBody(openMotionId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チーム ADMIN の投票は 403（BOLA・越境）")
        void 別チームADMINの投票は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/proxy-votes/{id}/cast", openSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(voteBody(openMotionId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("会員（memberA）は投票できる → 201")
        void 会員は投票できる() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/proxy-votes/{id}/cast", openSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(voteBody(openMotionId)))
                    .andExpect(status().isCreated());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 閲覧（getSession / getResults / listSessions）— 会員のみ
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("閲覧系 認可")
    class ReadAuthz {

        @Test
        @DisplayName("非会員のセッション詳細取得は 403")
        void 非会員の詳細は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}", openSessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非会員の投票結果取得は 403")
        void 非会員の結果は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}/results", openSessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非会員の一覧取得は 403")
        void 非会員の一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/proxy-votes")
                            .param("scope_type", "TEAM")
                            .param("team_id", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("会員はセッション詳細を取得できる → 200")
        void 会員は詳細を取得できる() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}", openSessionId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("会員は投票結果を取得できる → 200")
        void 会員は結果を取得できる() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}/results", openSessionId))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 作成・ライフサイクル・議案変更 — 作成者/管理者のみ
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("変更系 認可（BOLA: 別チーム ADMIN → 403）")
    class MutationAuthz {

        @Test
        @DisplayName("非管理者会員のセッション作成は 403")
        void 非管理者の作成は403() throws Exception {
            setAuthentication(memberAId);
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "scopeType", "TEAM",
                    "teamId", teamAId,
                    "resolutionMode", "WRITTEN",
                    "title", "越境作成の試み"));
            mockMvc.perform(post("/api/v1/proxy-votes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チーム ADMIN の受付開始（open）は 403")
        void 別チームADMINのopenは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(patch("/api/v1/proxy-votes/{id}/open", draftSessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チーム ADMIN のセッション更新は 403")
        void 別チームADMINのupdateは403() throws Exception {
            setAuthentication(adminBId);
            // UpdateSessionRequest は title(@NotBlank)/version(@NotNull) 必須。
            // これらを満たさないと @Valid が 400 を返し認可(403)まで到達しないため、両方を含める。
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "title", "乗っ取り", "version", 0));
            mockMvc.perform(put("/api/v1/proxy-votes/{id}", draftSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チーム ADMIN のセッション削除は 403")
        void 別チームADMINのdeleteは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/proxy-votes/{id}", draftSessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チーム ADMIN の結果確定（finalize）は 403")
        void 別チームADMINのfinalizeは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(patch("/api/v1/proxy-votes/{id}/finalize", closedSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チーム ADMIN の議案追加は 403")
        void 別チームADMINのaddMotionは403() throws Exception {
            setAuthentication(adminBId);
            String body = objectMapper.writeValueAsString(java.util.Map.of("title", "越境議案"));
            mockMvc.perform(post("/api/v1/proxy-votes/{id}/motions", draftSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チーム ADMIN の議案削除は 403")
        void 別チームADMINのdeleteMotionは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/proxy-votes/motions/{motionId}", draftMotionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成者（adminA）は DRAFT を受付開始できる → 200")
        void 作成者はopenできる() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/proxy-votes/{id}/open", draftSessionId))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // reviewDelegation（委任状承認/却下）— スコープ管理者のみ
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("reviewDelegation 認可")
    class ReviewDelegation {

        @Test
        @DisplayName("別チーム ADMIN の委任状承認は 403（BOLA）")
        void 別チームADMINのreviewは403() throws Exception {
            setAuthentication(adminBId);
            String body = objectMapper.writeValueAsString(java.util.Map.of("status", "ACCEPTED"));
            mockMvc.perform(patch("/api/v1/proxy-votes/delegations/{delegationId}/review", submittedDelegationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("一般会員の委任状承認は 403（管理者のみ）")
        void 一般会員のreviewは403() throws Exception {
            setAuthentication(memberAId);
            String body = objectMapper.writeValueAsString(java.util.Map.of("status", "ACCEPTED"));
            mockMvc.perform(patch("/api/v1/proxy-votes/delegations/{delegationId}/review", submittedDelegationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // エクスポート（CSV / PDF）— 会員のみ
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("エクスポート 認可")
    class Export {

        @Test
        @DisplayName("別チーム ADMIN の CSV エクスポートは 403（BOLA）")
        void 別チームADMINのCSVは403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}/results/csv", closedSessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非会員の議事録 PDF エクスポートは 403")
        void 非会員のPDFは403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}/minutes-pdf", finalizedSessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("会員は CSV エクスポートできる → 200")
        void 会員はCSVできる() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}/results/csv", closedSessionId))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 委任状提出(delegate)・取り下げ(cancelDelegation) — 認可根治戦役 Wave7
    // 会員のみ（票の水増し防止・castVoteと同一方式）
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("delegate/cancelDelegation 認可（Wave7）")
    class DelegateAuthz {

        @Test
        @DisplayName("非会員（outsider）の委任状提出は403（票の水増し防止）")
        void 非会員の委任提出は403() throws Exception {
            setAuthentication(outsiderId);
            String body = objectMapper.writeValueAsString(java.util.Map.of("isBlank", true));
            mockMvc.perform(post("/api/v1/proxy-votes/{id}/delegate", openSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMINの委任状提出は403（BOLA・越境）")
        void 別チームADMINの委任提出は403() throws Exception {
            setAuthentication(adminBId);
            String body = objectMapper.writeValueAsString(java.util.Map.of("isBlank", true));
            mockMvc.perform(post("/api/v1/proxy-votes/{id}/delegate", openSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("会員(memberA)は委任状を提出できる → 201")
        void 会員は委任提出できる() throws Exception {
            setAuthentication(memberAId);
            String body = objectMapper.writeValueAsString(java.util.Map.of("isBlank", true));
            mockMvc.perform(post("/api/v1/proxy-votes/{id}/delegate", openSessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非会員（outsider）の委任状取り下げは403")
        void 非会員の委任取り下げは403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/proxy-votes/{id}/delegate", openSessionId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 出席・委任状況(getAttendance) — 認可根治戦役 Wave7・会員のみ
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getAttendance 認可（Wave7）")
    class AttendanceAuthz {

        @Test
        @DisplayName("非会員の出席状況取得は403")
        void 非会員の出席状況は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}/attendance", openSessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMINの出席状況取得は403（BOLA）")
        void 別チームADMINの出席状況は403() throws Exception {
            setAuthentication(adminBId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}/attendance", openSessionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("会員は出席状況を取得できる → 200")
        void 会員は出席状況を取得できる() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/proxy-votes/{id}/attendance", openSessionId))
                    .andExpect(status().isOk());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 添付ファイル追加(addSessionAttachment/addMotionAttachment)・削除(deleteAttachment)
    // — 認可根治戦役 Wave7・作成者またはスコープ管理者のみ（checkOwnerOrAdmin）
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("添付ファイル 認可（Wave7）")
    class AttachmentAuthz {

        @Test
        @DisplayName("非管理者会員のセッション添付追加は403")
        void 非管理者のセッション添付追加は403() throws Exception {
            setAuthentication(memberAId);
            MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy".getBytes());
            mockMvc.perform(multipart("/api/v1/proxy-votes/{id}/attachments", draftSessionId)
                            .file(file)
                            .param("attachment_type", "DOCUMENT"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成者(adminA)のセッション添付追加は201")
        void 作成者のセッション添付追加は201() throws Exception {
            setAuthentication(adminAId);
            MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy".getBytes());
            mockMvc.perform(multipart("/api/v1/proxy-votes/{id}/attachments", draftSessionId)
                            .file(file)
                            .param("attachment_type", "DOCUMENT"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非管理者会員の議案添付追加は403")
        void 非管理者の議案添付追加は403() throws Exception {
            setAuthentication(memberAId);
            MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy".getBytes());
            mockMvc.perform(multipart("/api/v1/proxy-votes/motions/{motionId}/attachments", draftMotionId)
                            .file(file))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("作成者(adminA)の添付削除は204、非管理者会員の添付削除は403")
        void 添付削除の認可() throws Exception {
            setAuthentication(adminAId);
            MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "dummy".getBytes());
            String resp = mockMvc.perform(multipart("/api/v1/proxy-votes/{id}/attachments", draftSessionId)
                            .file(file)
                            .param("attachment_type", "DOCUMENT"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            Long attachmentId = objectMapper.readTree(resp).path("data").path("id").asLong();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/proxy-votes/attachments/{attachmentId}", attachmentId))
                    .andExpect(status().isForbidden());

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/proxy-votes/attachments/{attachmentId}", attachmentId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 議案コメント一覧(listComments)・投稿(createComment) — 認可根治戦役 Wave7・会員のみ
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("議案コメント 認可（Wave7）")
    class CommentAuthz {

        @Test
        @DisplayName("非会員のコメント一覧取得は403")
        void 非会員のコメント一覧は403() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/proxy-votes/motions/{motionId}/comments", openMotionId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非会員のコメント投稿は403")
        void 非会員のコメント投稿は403() throws Exception {
            setAuthentication(outsiderId);
            String body = objectMapper.writeValueAsString(java.util.Map.of("body", "越境コメント"));
            mockMvc.perform(post("/api/v1/proxy-votes/motions/{motionId}/comments", openMotionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("会員はコメント一覧取得・投稿ができる → 200/201")
        void 会員はコメント一覧取得投稿ができる() throws Exception {
            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/proxy-votes/motions/{motionId}/comments", openMotionId))
                    .andExpect(status().isOk());

            String body = objectMapper.writeValueAsString(java.util.Map.of("body", "正当コメント"));
            mockMvc.perform(post("/api/v1/proxy-votes/motions/{motionId}/comments", openMotionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.body").value("正当コメント"));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ヘルパー
    // ═══════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String voteBody(Long motionId) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "votes", List.of(java.util.Map.of("motionId", motionId, "voteType", "APPROVE"))));
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
                                + "VALUES (:email, 'PV', 'テスト', 'PV テスト', 'ACTIVE', "
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
                                + "CONCAT('pv-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertSession(Long teamId, String title, String mode, String status, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO proxy_vote_sessions ("
                                + "scope_type, team_id, organization_id, title, resolution_mode, status, "
                                + "is_anonymous, quorum_type, eligible_count, is_auto_accept_delegation, "
                                + "created_by, version, created_at, updated_at) "
                                + "VALUES ('TEAM', :tid, NULL, :title, :mode, :status, "
                                + "0, 'MAJORITY', 10, 0, "
                                + ":createdBy, 0, NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("title", title)
                .setParameter("mode", mode)
                .setParameter("status", status)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM proxy_vote_sessions").getSingleResult()).longValue();
    }

    private Long insertMotion(Long sessionId, String votingStatus) {
        em.createNativeQuery(
                        "INSERT INTO proxy_vote_motions ("
                                + "session_id, motion_number, title, voting_status, required_approval, "
                                + "approve_count, reject_count, abstain_count, created_at, updated_at) "
                                + "VALUES (:sid, 1, '議案1', :vstatus, 'MAJORITY', 0, 0, 0, NOW(), NOW())")
                .setParameter("sid", sessionId)
                .setParameter("vstatus", votingStatus)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM proxy_vote_motions").getSingleResult()).longValue();
    }

    private Long insertDelegation(Long sessionId, Long delegatorId, Long delegateId) {
        em.createNativeQuery(
                        "INSERT INTO proxy_delegations ("
                                + "session_id, delegator_id, delegate_id, is_blank, status, created_at, updated_at) "
                                + "VALUES (:sid, :delegator, :delegate, 0, 'SUBMITTED', NOW(), NOW())")
                .setParameter("sid", sessionId)
                .setParameter("delegator", delegatorId)
                .setParameter("delegate", delegateId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM proxy_delegations").getSingleResult()).longValue();
    }
}

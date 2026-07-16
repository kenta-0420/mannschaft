package com.mannschaft.app.cms;

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
 * 認可根治戦役 Wave3-B7: cms（ブログ）ドメインの書込CRUD・リビジョン(BOLA)・共有(BOLA)・
 * プレビュートークンの API 契約テスト（試練）。
 *
 * <p>正本: 依頼文（Wave3-B7 cms節）・{@code AccessControlService}
 * （{@code checkMembership}=閲覧・checkAdminOrAbove}=変更）・{@code BlogPostService#checkWriteAccess}
 * / {@code BlogPostRevisionService#checkWriteAccess} / {@code BlogPostShareService#checkWriteAccess}
 * （投稿者本人 or スコープADMINの認可を新設）。</p>
 *
 * <p>対象（従来 authz ゼロだった書込EP）:</p>
 * <ul>
 *   <li>{@code BlogPostController}/{@code PersonalBlogController}（共有 {@code BlogPostService}）:
 *       updatePost/deletePost/changeStatus/autoSave/bulkAction/duplicatePost/selfReview</li>
 *   <li>{@code BlogPostRevisionService}: listRevisions（読取＝assertCanView相当）・
 *       restoreRevision（書込認可＋revisionId∈postId のBOLA是正）</li>
 *   <li>{@code BlogPostShareService}: sharePost/revokeShare（revokeShareは既存BOLA是正済み・書込認可を新設）・
 *       issuePreviewToken/revokePreviewToken</li>
 * </ul>
 *
 * <p>金型: {@code GalleryScopeContractIT}（entity 由来 scope で認可判定・ID-only エンドポイントは
 * 越境を 403 に畳み込む。cms は投稿者本人 or スコープADMINという「所有者 or 管理者」二択のため、
 * property/workflow 系の「親子束縛不一致→404」パターンは restoreRevision/revokeShare の
 * BOLA是正にのみ適用する）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("cms ドメイン 書込CRUD/リビジョン/共有/プレビュートークン API 契約テスト（認可根治 Wave3-B7）")
class CmsBlogPostWriteScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;
    private Long personalOwnerId;
    private Long personalOtherId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("CMS認可契約チームA");
        teamBId = insertTeam("CMS認可契約チームB");

        adminAId = insertUser("cms-authz-admin-a@example.com");
        adminBId = insertUser("cms-authz-admin-b@example.com");
        memberAId = insertUser("cms-authz-member-a@example.com");
        outsiderId = insertUser("cms-authz-outsider@example.com");
        personalOwnerId = insertUser("cms-authz-personal-owner@example.com");
        personalOtherId = insertUser("cms-authz-personal-other@example.com");

        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 記事更新(updatePost)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("記事更新(updatePost)")
    class UpdatePost {

        @Test
        @DisplayName("非所有者かつ非ADMINの更新は403(COMMON_002)")
        void 非所有者かつ非ADMINの更新は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(put("/api/v1/blog/posts/{id}", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updatePostBody("乗っ取り"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの更新は403(entity由来scopeで認可判定)")
        void 他チームADMINの更新は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(put("/api/v1/blog/posts/{id}", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updatePostBody("越境更新"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMIN(所有者)の更新は200")
        void 正当ADMINの更新は200() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/blog/posts/{id}", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updatePostBody("改題済"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.title").value("改題済"));
        }

        @Test
        @DisplayName("不在記事の更新は404(CMS_001・IDOR秘匿)")
        void 不在記事の更新は404() throws Exception {
            setAuthentication(adminAId);
            mockMvc.perform(put("/api/v1/blog/posts/{id}", 999_999_999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updatePostBody("改題"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CMS_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 記事削除(deletePost)・公開ステータス変更(changeStatus)・自動保存(autoSave)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("記事削除(deletePost)")
    class DeletePost {

        @Test
        @DisplayName("非所有者かつ非ADMINの削除は403")
        void 非所有者かつ非ADMINの削除は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(delete("/api/v1/blog/posts/{id}", postId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMIN(所有者)の削除は204")
        void 正当ADMINの削除は204() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/blog/posts/{id}", postId))
                    .andExpect(status().isNoContent());
        }
    }

    @Nested
    @DisplayName("公開ステータス変更(changeStatus)")
    class ChangeStatus {

        @Test
        @DisplayName("非所有者かつ非ADMINのステータス変更は403")
        void 非所有者かつ非ADMINのステータス変更は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(patch("/api/v1/blog/posts/{id}/publish", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("PUBLISHED"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMIN(所有者)のステータス変更は200")
        void 正当ADMINのステータス変更は200() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(patch("/api/v1/blog/posts/{id}/publish", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(statusBody("PUBLISHED"))))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("下書き自動保存(autoSave)")
    class AutoSave {

        @Test
        @DisplayName("非所有者かつ非ADMINの自動保存は403")
        void 非所有者かつ非ADMINの自動保存は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("body", "乗っ取り本文");
            mockMvc.perform(patch("/api/v1/blog/posts/{id}/auto-save", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMIN(所有者)の自動保存は200")
        void 正当ADMINの自動保存は200() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("body", "自動保存本文");
            mockMvc.perform(patch("/api/v1/blog/posts/{id}/auto-save", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 一括操作(bulkAction)・複製(duplicatePost)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("一括操作(bulkAction)")
    class BulkAction {

        @Test
        @DisplayName("非所有者かつ非ADMINの一括操作は403(fail-closedで全体中断)")
        void 非所有者かつ非ADMINの一括操作は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", List.of(postId));
            body.put("action", "DELETE");
            mockMvc.perform(patch("/api/v1/blog/posts/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMIN(所有者)の一括操作は200")
        void 正当ADMINの一括操作は200() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ids", List.of(postId));
            body.put("action", "DELETE");
            mockMvc.perform(patch("/api/v1/blog/posts/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.processedCount").value(1));
        }
    }

    @Nested
    @DisplayName("記事複製(duplicatePost)")
    class DuplicatePost {

        @Test
        @DisplayName("非所有者かつ非ADMINの複製は403")
        void 非所有者かつ非ADMINの複製は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/duplicate", postId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMIN(所有者)の複製は201")
        void 正当ADMINの複製は201() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/duplicate", postId))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リビジョン一覧(listRevisions・読取)・復元(restoreRevision・書込+BOLA)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("リビジョン一覧(listRevisions)")
    class ListRevisions {

        @Test
        @DisplayName("非メンバーの一覧取得は403(assertCanView相当)")
        void 非メンバーの一覧取得は403() throws Exception {
            // DRAFT記事は作成者(adminA)以外は閲覧不可（F00 status ガード）のため、
            // ここでは PUBLISHED にして「非メンバーは MEMBERS_ONLY で弾かれる」ことを検証する。
            Long postId = createTeamPostAsAdminA();
            publishAsAdminA(postId);

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/blog/posts/{id}/revisions", postId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバー(非ADMIN)の一覧取得は200(PUBLISHED後はMEMBERS_ONLYでチーム全員が閲覧可)")
        void 正当メンバーの一覧取得は200() throws Exception {
            Long postId = createTeamPostAsAdminA();
            publishAsAdminA(postId);

            setAuthentication(memberAId);
            mockMvc.perform(get("/api/v1/blog/posts/{id}/revisions", postId))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("リビジョン復元(restoreRevision)")
    class RestoreRevision {

        @Test
        @DisplayName("非所有者かつ非ADMINの復元は403")
        void 非所有者かつ非ADMINの復元は403() throws Exception {
            Long postId = createTeamPostAsAdminA();
            Long revisionId = publishUpdateAndGetLatestRevisionId(postId);

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/revisions/{revisionId}/restore", postId, revisionId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他postのrevisionIdを指定した復元は404(CMS_004・BOLA存在秘匿)")
        void 他postのrevisionIdを指定した復元は404() throws Exception {
            Long postAId = createTeamPostAsAdminA();
            Long postBId = createTeamPostAsAdminA();
            Long revisionOfPostB = publishUpdateAndGetLatestRevisionId(postBId);

            setAuthentication(adminAId);
            // postAId の所有者(adminA)が、postBId 配下のrevisionIdを指定して復元しようとする越境アクセス
            mockMvc.perform(post("/api/v1/blog/posts/{id}/revisions/{revisionId}/restore", postAId, revisionOfPostB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CMS_004"));
        }

        @Test
        @DisplayName("正当ADMIN(所有者)の復元は200")
        void 正当ADMINの復元は200() throws Exception {
            Long postId = createTeamPostAsAdminA();
            Long revisionId = publishUpdateAndGetLatestRevisionId(postId);

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/revisions/{revisionId}/restore", postId, revisionId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 個人ブログ共有(sharePost/revokeShare) — PersonalBlogController経由
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("個人記事共有(sharePost)")
    class SharePost {

        @Test
        @DisplayName("非所有者の共有は403(COMMON_002・IDOR対策)")
        void 非所有者の共有は403() throws Exception {
            Long postId = createPersonalPostAs(personalOwnerId);

            setAuthentication(personalOtherId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamId", teamAId);
            mockMvc.perform(post("/api/v1/users/me/blog/posts/{id}/share", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当所有者の共有は201")
        void 正当所有者の共有は201() throws Exception {
            Long postId = createPersonalPostAs(personalOwnerId);

            setAuthentication(personalOwnerId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamId", teamAId);
            mockMvc.perform(post("/api/v1/users/me/blog/posts/{id}/share", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("個人記事共有取消(revokeShare)")
    class RevokeShare {

        @Test
        @DisplayName("他postの共有IDを指定した取消は404(CMS_019・BOLA存在秘匿)")
        void 他postの共有IDを指定した取消は404() throws Exception {
            Long postAId = createPersonalPostAs(personalOwnerId);
            Long postBId = createPersonalPostAs(personalOwnerId);
            Long shareOfPostA = shareAsOwnerAndGetShareId(postAId, personalOwnerId);

            setAuthentication(personalOwnerId);
            // postBId の所有者(personalOwner)が、postAId 配下のshareIdを指定して取消しようとする越境アクセス
            mockMvc.perform(delete("/api/v1/users/me/blog/posts/{id}/shares/{shareId}", postBId, shareOfPostA))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CMS_019"));
        }

        @Test
        @DisplayName("非所有者の取消は403")
        void 非所有者の取消は403() throws Exception {
            Long postId = createPersonalPostAs(personalOwnerId);
            Long shareId = shareAsOwnerAndGetShareId(postId, personalOwnerId);

            setAuthentication(personalOtherId);
            mockMvc.perform(delete("/api/v1/users/me/blog/posts/{id}/shares/{shareId}", postId, shareId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当所有者の取消は204")
        void 正当所有者の取消は204() throws Exception {
            Long postId = createPersonalPostAs(personalOwnerId);
            Long shareId = shareAsOwnerAndGetShareId(postId, personalOwnerId);

            setAuthentication(personalOwnerId);
            mockMvc.perform(delete("/api/v1/users/me/blog/posts/{id}/shares/{shareId}", postId, shareId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // プレビュートークン発行(issuePreviewToken)・無効化(revokePreviewToken)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("プレビュートークン発行(issuePreviewToken)・無効化(revokePreviewToken)")
    class PreviewToken {

        @Test
        @DisplayName("非所有者かつ非ADMINの発行は403")
        void 非所有者かつ非ADMINの発行は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(memberAId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/preview-token", postId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの発行は403")
        void 他チームADMINの発行は403() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/preview-token", postId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMIN(所有者)の発行は200")
        void 正当ADMINの発行は200() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/preview-token", postId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMIN(所有者)の無効化は204")
        void 正当ADMINの無効化は204() throws Exception {
            Long postId = createTeamPostAsAdminA();

            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/blog/posts/{id}/preview-token", postId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // セルフレビュー(selfReview) — PersonalBlogController経由
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("セルフレビュー(selfReview)")
    class SelfReview {

        @Test
        @DisplayName("非所有者のセルフレビューは403")
        void 非所有者のセルフレビューは403() throws Exception {
            Long postId = createPersonalPostAs(personalOwnerId);
            markPendingSelfReview(postId);

            setAuthentication(personalOtherId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "PUBLISH");
            mockMvc.perform(patch("/api/v1/users/me/blog/posts/{id}/self-review", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当所有者のセルフレビューは200")
        void 正当所有者のセルフレビューは200() throws Exception {
            Long postId = createPersonalPostAs(personalOwnerId);
            markPendingSelfReview(postId);

            setAuthentication(personalOwnerId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", "PUBLISH");
            mockMvc.perform(patch("/api/v1/users/me/blog/posts/{id}/self-review", postId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** adminA の認証コンテキストでチームAの記事(DRAFT)を1件作成し、そのIDを返す。 */
    private Long createTeamPostAsAdminA() throws Exception {
        setAuthentication(adminAId);
        String resp = mockMvc.perform(post("/api/v1/blog/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createPostBody())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** 指定ユーザーの認証コンテキストで個人ブログ記事(team/org無し)を1件作成し、そのIDを返す。 */
    private Long createPersonalPostAs(Long userId) throws Exception {
        setAuthentication(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "個人記事 " + System.nanoTime());
        body.put("body", "個人記事本文");
        String resp = mockMvc.perform(post("/api/v1/users/me/blog/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** adminA の認証コンテキストで postId を PUBLISHED にする（DRAFT は作成者以外閲覧不可のため）。 */
    private void publishAsAdminA(Long postId) throws Exception {
        setAuthentication(adminAId);
        mockMvc.perform(patch("/api/v1/blog/posts/{id}/publish", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusBody("PUBLISHED"))))
                .andExpect(status().isOk());
    }

    /**
     * adminA の認証コンテキストで postId をPUBLISHEDにし、続けて更新してリビジョンを1件生成、
     * 最新リビジョンIDを返す（{@code BlogPostService#updatePost} は PUBLISHED 記事の更新時に
     * {@code revisionService.saveRevision} を呼ぶ）。
     */
    private Long publishUpdateAndGetLatestRevisionId(Long postId) throws Exception {
        setAuthentication(adminAId);
        mockMvc.perform(patch("/api/v1/blog/posts/{id}/publish", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(statusBody("PUBLISHED"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/blog/posts/{id}", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePostBody("リビジョン生成用更新"))))
                .andExpect(status().isOk());
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM blog_post_revisions WHERE blog_post_id = :postId ORDER BY id DESC LIMIT 1")
                .setParameter("postId", postId)
                .getSingleResult()).longValue();
    }

    /** ownerUserId の認証コンテキストで postId をチームAに共有し、shareId を返す。 */
    private Long shareAsOwnerAndGetShareId(Long postId, Long ownerUserId) throws Exception {
        setAuthentication(ownerUserId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamAId);
        String resp = mockMvc.perform(post("/api/v1/users/me/blog/posts/{id}/share", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("shareId").asLong();
    }

    /** postId のステータスを PENDING_SELF_REVIEW に直接更新する（セルフレビュー遷移は別バッチ担当のためテストで直接補完）。 */
    private void markPendingSelfReview(Long postId) {
        em.createNativeQuery("UPDATE blog_posts SET status = 'PENDING_SELF_REVIEW' WHERE id = :postId")
                .setParameter("postId", postId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private Map<String, Object> createPostBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamAId.toString());
        body.put("title", "認可契約テスト記事 " + System.nanoTime());
        body.put("body", "本文");
        return body;
    }

    private Map<String, Object> updatePostBody(String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("body", "更新本文");
        return body;
    }

    private Map<String, Object> statusBody(String status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
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
                                + "VALUES (:email, 'CMS契約', 'テスト', 'CMS契約テスト', 'ACTIVE', "
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
                                + "CONCAT('cms-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}

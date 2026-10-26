package com.mannschaft.app.cms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.entity.BlogPostShareEntity;
import com.mannschaft.app.cms.entity.UserBlogSettingsEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostRevisionRepository;
import com.mannschaft.app.cms.repository.BlogPostShareRepository;
import com.mannschaft.app.cms.repository.UserBlogSettingsRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F06.1 個人ブログ（記事共有・プレビュートークン・リビジョン・セルフレビュー設定）ドメインの認可契約テスト。
 *
 * <p>本 IT は「他人の記事に到達・操作できないこと」を固定する。認可根治戦役 第4波ロットE で
 * 監査済の 10 エンドポイント（{@code BlogPostController} 4 / {@code PersonalBlogController} 6）
 * を対象とする。判定の実体は Wave3-B7（旧戦役）で既に {@code BlogPostRevisionService} /
 * {@code BlogPostShareService} / {@code BlogPostService} 内の {@code checkWriteAccess} /
 * 著者ID一致比較に実装済みであり、本 IT はその保証を EP 単位で外形から固定する。</p>
 *
 * <p>対象エンドポイント（{@code Controller#method} 形式）:</p>
 * <ul>
 *   <li>{@code BlogPostController#restoreRevision} — 投稿者本人/スコープADMIN以上のみ。
 *       revisionId が postId 配下でない越境は404（存在秘匿）。</li>
 *   <li>{@code BlogPostController#issuePreviewToken / revokePreviewToken} — 投稿者本人/スコープADMIN以上のみ。</li>
 *   <li>{@code BlogPostController#patchPublicVisible} — 投稿者本人のみ。</li>
 *   <li>{@code PersonalBlogController#getMyPost} — 投稿者本人のみ（{@code findByIdAndAuthorIdAndDeletedAtIsNull}）。</li>
 *   <li>{@code PersonalBlogController#listMyPosts} — 自己スコープ。</li>
 *   <li>{@code PersonalBlogController#sharePost / revokeShare} — 投稿者本人/スコープADMIN以上のみ。
 *       shareId が postId 配下でない越境は404（存在秘匿）。</li>
 *   <li>{@code PersonalBlogController#getSettings / updateSettings} — 自己スコープ。</li>
 * </ul>
 *
 * <p>金型: {@code ChatAuthzScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。未認証は
 * {@code SecurityUtils} の {@code COMMON_000} → 401。個人ブログ記事（teamId/organizationId 共に
 * null）に対する非著者アクセスは {@code checkWriteAccess} がスコープ ADMIN 判定に進めず即
 * {@code CommonErrorCode.COMMON_002}（403）を返すため、チーム/組織のメンバーシップ seed は不要。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.1 個人ブログ 認可契約テスト（他人の記事へ到達・操作できないこと）")
class CmsAuthzScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BlogPostRepository postRepository;

    @Autowired
    private BlogPostRevisionRepository revisionRepository;

    @Autowired
    private BlogPostShareRepository shareRepository;

    @Autowired
    private UserBlogSettingsRepository settingsRepository;

    @PersistenceContext
    private EntityManager em;

    private Long authorId;
    private Long outsiderId;

    /** author の個人ブログ記事（DRAFT）。プレビュートークン発行対象。 */
    private Long draftPostId;
    /** author の個人ブログ記事（PUBLISHED）。公開設定変更・共有・リビジョン対象。 */
    private Long publishedPostId;

    private Long revisionId;
    private Long shareId;

    @BeforeEach
    void setUp() {
        String uniq = Long.toString(System.nanoTime(), 36);

        authorId = insertUser("cmsauthz-author-" + uniq + "@example.com");
        outsiderId = insertUser("cmsauthz-outsider-" + uniq + "@example.com");

        draftPostId = postRepository.save(BlogPostEntity.builder()
                .userId(authorId)
                .authorId(authorId)
                .title("CMSAUTHZ 下書き記事")
                .slug("cmsauthz-draft-" + uniq)
                .body("本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.PRIVATE)
                .status(PostStatus.DRAFT)
                .readingTimeMinutes((short) 1)
                .build()).getId();

        publishedPostId = postRepository.save(BlogPostEntity.builder()
                .userId(authorId)
                .authorId(authorId)
                .title("CMSAUTHZ 公開記事")
                .slug("cmsauthz-published-" + uniq)
                .body("本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.PUBLIC)
                .status(PostStatus.PUBLISHED)
                .readingTimeMinutes((short) 1)
                .build()).getId();

        revisionId = revisionRepository.save(BlogPostRevisionEntity.builder()
                .blogPostId(publishedPostId)
                .revisionNumber(1)
                .title("CMSAUTHZ 旧タイトル")
                .body("旧本文")
                .editorId(authorId)
                .build()).getId();

        shareId = shareRepository.save(BlogPostShareEntity.builder()
                .blogPostId(publishedPostId)
                .teamId(424242L)
                .sharedBy(authorId)
                .build()).getId();

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. BlogPostController#restoreRevision（投稿者本人/スコープADMIN限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. BlogPostController#restoreRevision（リビジョン復元・投稿者本人限定）")
    class RestoreRevision {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/blog/posts/{id}/revisions/{revisionId}/restore",
                            publishedPostId, revisionId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非著者の復元は403（本文は書き換わらない）")
        void 非著者の復元は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/revisions/{revisionId}/restore",
                            publishedPostId, revisionId))
                    .andExpect(status().isForbidden());

            assertThat(postRepository.findById(publishedPostId).orElseThrow().getTitle())
                    .isEqualTo("CMSAUTHZ 公開記事");
        }

        @Test
        @DisplayName("正常系: 投稿者本人は200で復元できる")
        void 投稿者本人は復元できる() throws Exception {
            setAuth(authorId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/revisions/{revisionId}/restore",
                            publishedPostId, revisionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content.title").value("CMSAUTHZ 旧タイトル"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. BlogPostController#issuePreviewToken（投稿者本人/スコープADMIN限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. BlogPostController#issuePreviewToken（プレビュートークン発行・投稿者本人限定）")
    class IssuePreviewToken {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/blog/posts/{id}/preview-token", draftPostId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非著者の発行は403（トークンは発行されない）")
        void 非著者の発行は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/preview-token", draftPostId))
                    .andExpect(status().isForbidden());

            assertThat(postRepository.findById(draftPostId).orElseThrow().getPreviewToken()).isNull();
        }

        @Test
        @DisplayName("正常系: 投稿者本人は200でトークンを発行できる")
        void 投稿者本人は発行できる() throws Exception {
            setAuth(authorId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/preview-token", draftPostId))
                    .andExpect(status().isOk());

            assertThat(postRepository.findById(draftPostId).orElseThrow().getPreviewToken()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. BlogPostController#revokePreviewToken（投稿者本人/スコープADMIN限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. BlogPostController#revokePreviewToken（プレビュートークン無効化・投稿者本人限定）")
    class RevokePreviewToken {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/blog/posts/{id}/preview-token", draftPostId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非著者の無効化は403（既存トークンは残る）")
        void 非著者の無効化は403() throws Exception {
            issueTokenAsAuthor();

            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/blog/posts/{id}/preview-token", draftPostId))
                    .andExpect(status().isForbidden());

            assertThat(postRepository.findById(draftPostId).orElseThrow().getPreviewToken()).isNotNull();
        }

        @Test
        @DisplayName("正常系: 投稿者本人は204で無効化できる")
        void 投稿者本人は無効化できる() throws Exception {
            issueTokenAsAuthor();

            setAuth(authorId);
            mockMvc.perform(delete("/api/v1/blog/posts/{id}/preview-token", draftPostId))
                    .andExpect(status().isNoContent());

            assertThat(postRepository.findById(draftPostId).orElseThrow().getPreviewToken()).isNull();
        }

        private void issueTokenAsAuthor() throws Exception {
            setAuth(authorId);
            mockMvc.perform(post("/api/v1/blog/posts/{id}/preview-token", draftPostId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. BlogPostController#patchPublicVisible（投稿者本人限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. BlogPostController#patchPublicVisible（公開表示切替・投稿者本人限定）")
    class PatchPublicVisible {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/blog/posts/{id}/public-visible", publishedPostId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("publicVisible", false))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非著者の変更は403（フラグは不変）")
        void 非著者の変更は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/blog/posts/{id}/public-visible", publishedPostId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("publicVisible", false))))
                    .andExpect(status().isForbidden());

            assertThat(postRepository.findById(publishedPostId).orElseThrow().isPublicVisible()).isTrue();
        }

        @Test
        @DisplayName("正常系: 投稿者本人は204で変更できる")
        void 投稿者本人は変更できる() throws Exception {
            setAuth(authorId);
            mockMvc.perform(patch("/api/v1/blog/posts/{id}/public-visible", publishedPostId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("publicVisible", false))))
                    .andExpect(status().isNoContent());

            assertThat(postRepository.findById(publishedPostId).orElseThrow().isPublicVisible()).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. PersonalBlogController#getMyPost（投稿者本人限定・BOLA対策）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. PersonalBlogController#getMyPost（自分の記事詳細ID指定・投稿者本人限定）")
    class GetMyPost {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/blog/posts/{id}", draftPostId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非著者が他人の記事IDを指定すると404（IDOR対策・存在秘匿）")
        void 非著者の取得は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/users/me/blog/posts/{id}", draftPostId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("正常系: 投稿者本人は下書きでも200で取得できる")
        void 投稿者本人は下書きでも取得できる() throws Exception {
            setAuth(authorId);
            mockMvc.perform(get("/api/v1/users/me/blog/posts/{id}", draftPostId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(draftPostId.intValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. PersonalBlogController#listMyPosts（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. PersonalBlogController#listMyPosts（自分の記事一覧・自己スコープ）")
    class ListMyPosts {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/blog/posts"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 投稿者本人には自分の下書きを含む一覧が返り、他人には別ユーザーの下書きは混入しない")
        void 投稿者本人には下書き込みで自分の一覧が返る() throws Exception {
            setAuth(authorId);
            mockMvc.perform(get("/api/v1/users/me/blog/posts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id").isNotEmpty());

            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/users/me/blog/posts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PersonalBlogController#sharePost（投稿者本人/スコープADMIN限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. PersonalBlogController#sharePost（記事共有・投稿者本人限定）")
    class SharePost {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/users/me/blog/posts/{id}/share", draftPostId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("teamId", 555555L))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非著者の共有は403（共有行は作られない）")
        void 非著者の共有は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/users/me/blog/posts/{id}/share", draftPostId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("teamId", 555555L))))
                    .andExpect(status().isForbidden());

            assertThat(shareRepository.findByBlogPostIdAndTeamId(draftPostId, 555555L)).isEmpty();
        }

        @Test
        @DisplayName("正常系: 投稿者本人は201で共有できる")
        void 投稿者本人は共有できる() throws Exception {
            setAuth(authorId);
            mockMvc.perform(post("/api/v1/users/me/blog/posts/{id}/share", draftPostId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("teamId", 555555L))))
                    .andExpect(status().isCreated());

            assertThat(shareRepository.findByBlogPostIdAndTeamId(draftPostId, 555555L)).isPresent();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. PersonalBlogController#revokeShare（投稿者本人/スコープADMIN限定・BOLA対策）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PersonalBlogController#revokeShare（共有取消・投稿者本人限定）")
    class RevokeShare {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/users/me/blog/posts/{id}/shares/{shareId}",
                            publishedPostId, shareId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("非著者の取消は403（共有行は残る）")
        void 非著者の取消は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/users/me/blog/posts/{id}/shares/{shareId}",
                            publishedPostId, shareId))
                    .andExpect(status().isForbidden());

            assertThat(shareRepository.findById(shareId)).isPresent();
        }

        @Test
        @DisplayName("正常系: 投稿者本人は204で取り消せる")
        void 投稿者本人は取り消せる() throws Exception {
            setAuth(authorId);
            mockMvc.perform(delete("/api/v1/users/me/blog/posts/{id}/shares/{shareId}",
                            publishedPostId, shareId))
                    .andExpect(status().isNoContent());

            assertThat(shareRepository.findById(shareId)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. PersonalBlogController#getSettings（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. PersonalBlogController#getSettings（セルフレビュー設定取得・自己スコープ）")
    class GetSettings {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/users/me/blog/settings"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 認証ユーザー自身の設定のみ返る（新規なら自動作成）")
        void 認証ユーザー自身の設定が返る() throws Exception {
            setAuth(authorId);
            mockMvc.perform(get("/api/v1/users/me/blog/settings"))
                    .andExpect(status().isOk());

            assertThat(settingsRepository.findByUserId(authorId)).isPresent();
            assertThat(settingsRepository.findByUserId(outsiderId)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. PersonalBlogController#updateSettings（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. PersonalBlogController#updateSettings（セルフレビュー設定更新・自己スコープ）")
    class UpdateSettings {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(put("/api/v1/users/me/blog/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("selfReviewEnabled", true,
                                    "selfReviewStart", "23:00:00", "selfReviewEnd", "06:00:00"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("正常系: 認証ユーザー自身の設定のみ更新され、他人の設定行は作られない")
        void 認証ユーザー自身の設定のみ更新される() throws Exception {
            setAuth(authorId);
            mockMvc.perform(put("/api/v1/users/me/blog/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("selfReviewEnabled", true,
                                    "selfReviewStart", "23:00:00", "selfReviewEnd", "06:00:00"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.selfReviewEnabled").value(true));

            assertThat(settingsRepository.findByUserId(authorId).orElseThrow().getSelfReviewEnabled()).isTrue();
            assertThat(settingsRepository.findByUserId(outsiderId)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（金型 ChatAuthzScopeContractIT より写経）
    // ═════════════════════════════════════════════════════════════════════

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
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
                                + "VALUES (:email, 'CMSAUTHZ', 'テスト', 'CMSAUTHZ テスト', 'ACTIVE', "
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
}

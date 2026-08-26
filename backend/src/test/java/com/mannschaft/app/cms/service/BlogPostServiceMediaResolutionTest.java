package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.publicview.service.PostAuthorSnapshotService;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 会員経路における本文メディア URL 解決の<b>結線</b>テスト（Mockito・Docker 不要）。
 *
 * <h2>表示経路と編集経路の線引き（殿が実物で確定・本テストで固定する）</h2>
 *
 * <table border="1">
 *   <caption>解決の可否</caption>
 *   <tr><th>メソッド</th><th>用途</th><th>解決するか</th></tr>
 *   <tr><td>{@code getBySlug}</td><td>他人が読む記事詳細</td><td>解決する</td></tr>
 *   <tr><td>{@code getBySlugWithPreviewToken}</td><td>下書きプレビュー</td><td>解決する</td></tr>
 *   <tr><td>{@code getMyPostById}</td><td><b>編集専用</b></td><td><b>解決してはならない</b></td></tr>
 * </table>
 *
 * <h2>なぜ編集経路では解決してはならないのか【重要・消さないこと】</h2>
 *
 * <p>署名 URL（presigned URL）には<b>有効期限がある</b>。編集画面が本文を読むときに
 * r2Key を署名 URL へ置換してしまうと、利用者が編集して保存した瞬間に
 * <b>期限付きの署名 URL がそのまま {@code blog_posts.body} へ永続保存される</b>。
 * 数十分後には全て期限切れとなり、記事の画像が恒久的に壊れる。
 * これは今まさに根治しようとしている「壊れた URL が本文へ焼き込まれる」病気の、
 * <b>より悪質な再発</b>である（元の病気は壊れた相対パスだったが、こちらは
 * 「保存直後は正常に見え、時間が経ってから壊れる」ため発見が遅れる）。</p>
 *
 * <p>ゆえに編集入口である {@code getMyPostById} は<b>生の r2Key をそのまま返す</b>のが正しい。
 * 本テストの {@link EditPathGuard} は「一律に解決すればよい」と誤解した者が
 * 結線を足したときに落ちる<b>番人</b>である。解決漏れではないので結線しないこと。</p>
 *
 * <p>根拠（実測）: {@code getMyPostById} の唯一の呼び出し元は
 * {@code PersonalBlogController}（{@code GET /users/me/blog/posts/{id}}）であり、
 * その FE 呼び出し元は {@code useBlogApi.ts} の {@code getMyPost} → 編集画面
 * {@code pages/blog/posts/[id]/edit.vue} ただ 1 箇所。</p>
 *
 * <p>なお {@code SecurityUtils.getCurrentUserIdOrNull()} は SecurityContext 不在時に
 * 安全に {@code null} を返すため、本テストは <b>MockedStatic を使っていない</b>
 * （フルシャードで間欠失敗する既知リスクを回避）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostService — 表示経路は解決し、編集経路は解決しない")
class BlogPostServiceMediaResolutionTest {

    @Mock private BlogPostRepository postRepository;
    @Mock private BlogPostTagRepository postTagRepository;
    @Mock private CmsMapper cmsMapper;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private BlogPostRevisionService revisionService;
    @Mock private BlogPostShareService shareService;
    @Mock private PostAuthorSnapshotService postAuthorSnapshotService;
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private PaymentGateService paymentGateService;
    /** 出陣で BlogPostService へ注入されるべき新規依存。 */
    @Mock private BlogBodyMediaResolver blogBodyMediaResolver;

    @InjectMocks private BlogPostService service;

    private static final Long TEAM_ID = 100L;
    private static final Long POST_ID = 500L;
    private static final Long AUTHOR_ID = 88L;
    private static final String SLUG = "my-slug";

    private static final String RAW_BODY =
            "冒頭\n\n![写真](blog/TEAM/100/aaaa-1111.png)\n\n<video src=\"blog/TEAM/100/bbbb-2222.mp4\"></video>";
    private static final String RESOLVED_BODY =
            "冒頭\n\n![写真](https://r2.example.com/signed-img)\n\n<video src=\"https://r2.example.com/signed-vid\"></video>";

    private BlogPostEntity teamPost() {
        BlogPostEntity post = BlogPostEntity.builder()
                .teamId(TEAM_ID)
                .authorId(AUTHOR_ID)
                .title("タイトル")
                .slug(SLUG)
                .body(RAW_BODY)
                .excerpt("要約")
                .build();
        ReflectionTestUtils.setField(post, "id", POST_ID);
        return post;
    }

    /** cmsMapper が返す DTO（本文は生 r2Key のまま）。 */
    private BlogPostResponse mappedResponse() {
        return BlogPostResponse.builder()
                .id(POST_ID)
                .scope(new BlogPostResponse.BlogPostScopeDto(TEAM_ID, null, null, AUTHOR_ID))
                .content(new BlogPostResponse.BlogPostContentDto(
                        "タイトル", SLUG, RAW_BODY, "要約", null))
                .build();
    }

    // ========================================
    // 表示経路: 解決する
    // ========================================

    @Nested
    @DisplayName("表示経路: 本文の r2Key を署名URLへ解決する")
    class DisplayPath {

        private void stubBySlug() {
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG))
                    .willReturn(Optional.of(teamPost()));
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class)))
                    .willReturn(mappedResponse());
            // 未認証（viewerUserId=null）・ゲート通過で全文が返る状態にする
            lenient().when(paymentGateService.checkAccess(eq(ContentGateType.POST), eq(POST_ID), any()))
                    .thenReturn(new GateCheckResponse(true, false, List.of()));
            lenient().when(blogBodyMediaResolver.resolveBody(any(), any(), any()))
                    .thenReturn(RESOLVED_BODY);
        }

        @Test
        @DisplayName("WIRE-1: getBySlug は resolveBody を呼び、解決済み本文を返す")
        void getBySlugは解決する() {
            stubBySlug();

            BlogPostResponse result = service.getBySlug(TEAM_ID, null, null, SLUG);

            verify(blogBodyMediaResolver).resolveBody(
                    eq(RAW_BODY), eq(StorageScopeType.TEAM), eq(TEAM_ID));
            assertThat(result.getContent().body())
                    .as("表示経路では署名URLへ解決済みの本文が返ること")
                    .isEqualTo(RESOLVED_BODY);
        }

        @Test
        @DisplayName("WIRE-2: getBySlugWithPreviewToken（下書きプレビュー）も解決する")
        void プレビュートークン経路も解決する() {
            stubBySlug();

            BlogPostResponse result = service.getBySlugWithPreviewToken(
                    TEAM_ID, null, null, SLUG, "preview-token-xyz");

            verify(blogBodyMediaResolver).resolveBody(
                    eq(RAW_BODY), eq(StorageScopeType.TEAM), eq(TEAM_ID));
            assertThat(result.getContent().body())
                    .as("下書きプレビューでも画像が表示できること")
                    .isEqualTo(RESOLVED_BODY);
        }

        @Test
        @DisplayName("WIRE-3: ペイウォールで本文がマスクされた場合は解決を呼ばない")
        void マスク時は解決しない() {
            given(postRepository.findByTeamIdAndSlug(TEAM_ID, SLUG))
                    .willReturn(Optional.of(teamPost()));
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class)))
                    .willReturn(mappedResponse());
            given(paymentGateService.checkAccess(eq(ContentGateType.POST), eq(POST_ID), any()))
                    .willReturn(new GateCheckResponse(false, false, List.of()));

            BlogPostResponse result = service.getBySlug(TEAM_ID, null, null, SLUG);

            assertThat(result.getContent().body())
                    .as("未課金のマスクを解決処理で復活させてはならない")
                    .isNull();
            verify(blogBodyMediaResolver, never()).resolveBody(any(), any(), any());
        }
    }

    // ========================================
    // 編集経路: 解決しない（番人）
    // ========================================

    @Nested
    @DisplayName("編集経路の番人: getMyPostById は解決してはならない")
    class EditPathGuard {

        @Test
        @DisplayName("WIRE-4【番人】getMyPostById は resolveBody を呼ばず、生の r2Key を返す")
        void 編集経路は解決しない() {
            given(postRepository.findByIdAndAuthorIdAndDeletedAtIsNull(POST_ID, AUTHOR_ID))
                    .willReturn(Optional.of(teamPost()));
            given(cmsMapper.toBlogPostResponse(any(BlogPostEntity.class)))
                    .willReturn(mappedResponse());

            BlogPostResponse result = service.getMyPostById(POST_ID, AUTHOR_ID);

            // ここで結線を足すと、編集保存時に期限付き署名URLが本文へ永続保存され、
            // 数十分後に記事の画像が恒久的に壊れる（クラス Javadoc 参照）。
            verify(blogBodyMediaResolver, never()).resolveBody(any(), any(), any());

            assertThat(result.getContent().body())
                    .as("編集画面には生の r2Key をそのまま渡すこと（署名URLを焼き込ませない）")
                    .isEqualTo(RAW_BODY);
            assertThat(result.getContent().body())
                    .as("編集経路の本文に署名URL（http(s)）が混入してはならない")
                    .doesNotContain("https://")
                    .doesNotContain("http://");
        }
    }
}

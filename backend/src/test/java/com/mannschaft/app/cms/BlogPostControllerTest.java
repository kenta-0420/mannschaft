package com.mannschaft.app.cms;

import com.mannschaft.app.cms.controller.BlogPostController;
import com.mannschaft.app.cms.dto.AutoSaveRequest;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BulkActionRequest;
import com.mannschaft.app.cms.dto.BulkActionResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.UpdateBlogPostRequest;
import com.mannschaft.app.cms.dto.BlogReactionResponse;
import com.mannschaft.app.cms.service.BlogFeedService;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.cms.service.BlogReactionService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BlogPostController} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogPostController 単体テスト")
class BlogPostControllerTest {

    @Mock
    private BlogPostService postService;

    @Mock
    private BlogFeedService feedService;

    @Mock
    private BlogReactionService reactionService;

    @InjectMocks
    private BlogPostController controller;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long POST_ID = 100L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private BlogPostResponse mockResponse() {
        return new BlogPostResponse(POST_ID, TEAM_ID, null, null, USER_ID,
                "テスト記事", "test-post", "本文", null, null,
                "BLOG", "MEMBERS_ONLY", "NORMAL", "DRAFT",
                null, false, false, 0, (short) 1, 1, null, null,
                List.of(), null, null, false, 0);
    }

    // ========================================
    // listPosts
    // ========================================

    @Nested
    @DisplayName("listPosts")
    class ListPosts {

        @Test
        @DisplayName("正常系: teamIdで記事一覧が返却される")
        void チームID指定_記事一覧_正常() {
            Page<BlogPostResponse> page = new PageImpl<>(List.of(mockResponse()));
            given(postService.listByTeam(eq(TEAM_ID), any())).willReturn(page);

            ResponseEntity<PagedResponse<BlogPostResponse>> result =
                    controller.listPosts(TEAM_ID, null, null, null, null, null, null, null, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: organizationIdで記事一覧が返却される")
        void 組織ID指定_記事一覧_正常() {
            Page<BlogPostResponse> page = new PageImpl<>(List.of(mockResponse()));
            given(postService.listByOrganization(eq(ORG_ID), any())).willReturn(page);

            ResponseEntity<PagedResponse<BlogPostResponse>> result =
                    controller.listPosts(null, ORG_ID, null, null, null, null, null, null, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // getPostBySlug
    // ========================================

    @Nested
    @DisplayName("getPostBySlug")
    class GetPostBySlug {

        @Test
        @DisplayName("正常系: previewTokenなしでslug取得")
        void slug取得_プレビューなし_正常() {
            given(postService.getBySlug(TEAM_ID, null, null, "my-post")).willReturn(mockResponse());
            given(reactionService.getReactionStatus(eq(POST_ID), any()))
                    .willReturn(new BlogReactionResponse(POST_ID, false, 0));

            ResponseEntity<ApiResponse<BlogPostResponse>> result =
                    controller.getPostBySlug("my-post", TEAM_ID, null, null, null);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getTitle()).isEqualTo("テスト記事");
        }

        @Test
        @DisplayName("正常系: previewTokenありでslug取得")
        void slug取得_プレビューあり_正常() {
            given(postService.getBySlugWithPreviewToken(TEAM_ID, null, null, "my-post", "token123"))
                    .willReturn(mockResponse());
            given(reactionService.getReactionStatus(eq(POST_ID), any()))
                    .willReturn(new BlogReactionResponse(POST_ID, false, 0));

            ResponseEntity<ApiResponse<BlogPostResponse>> result =
                    controller.getPostBySlug("my-post", TEAM_ID, null, null, "token123");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // createPost
    // ========================================

    @Nested
    @DisplayName("createPost")
    class CreatePost {

        @Test
        @DisplayName("正常系: 記事が作成される (201)")
        void 記事作成_正常_201() {
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    TEAM_ID, null, null, "新記事", null, "本文",
                    null, null, null, null, null, null, null, null, null, null, null);
            given(postService.createPost(eq(USER_ID), any())).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result = controller.createPost(request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // ========================================
    // updatePost
    // ========================================

    @Nested
    @DisplayName("updatePost")
    class UpdatePost {

        @Test
        @DisplayName("正常系: 記事が更新される")
        void 記事更新_正常() {
            UpdateBlogPostRequest request = new UpdateBlogPostRequest(
                    "更新タイトル", null, "更新本文", null, null, null, null, null, null, null, null, null, null);
            given(postService.updatePost(eq(POST_ID), eq(USER_ID), any())).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result = controller.updatePost(POST_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // deletePost
    // ========================================

    @Nested
    @DisplayName("deletePost")
    class DeletePost {

        @Test
        @DisplayName("正常系: 記事が削除される (204)")
        void 記事削除_正常_204() {
            ResponseEntity<Void> result = controller.deletePost(POST_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(postService).deletePost(POST_ID);
        }
    }

    // ========================================
    // changeStatus
    // ========================================

    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        @Test
        @DisplayName("正常系: ステータス変更される")
        void ステータス変更_正常() {
            PublishRequest request = new PublishRequest("PUBLISHED", null, null);
            given(postService.changeStatus(eq(POST_ID), any())).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result = controller.changeStatus(POST_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // autoSave
    // ========================================

    @Nested
    @DisplayName("autoSave")
    class AutoSave {

        @Test
        @DisplayName("正常系: 自動保存される")
        void 自動保存_正常() {
            AutoSaveRequest request = new AutoSaveRequest("新タイトル", "新本文", null, null);
            given(postService.autoSave(eq(POST_ID), eq(USER_ID), any())).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result = controller.autoSave(POST_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // bulkAction
    // ========================================

    @Nested
    @DisplayName("bulkAction")
    class BulkActionTest {

        @Test
        @DisplayName("正常系: 一括操作が実行される")
        void 一括操作_正常() {
            BulkActionRequest request = new BulkActionRequest(List.of(1L, 2L), "DELETE");
            BulkActionResponse response = new BulkActionResponse(2, List.of(), "DELETE");
            given(postService.bulkAction(any())).willReturn(response);

            ResponseEntity<ApiResponse<BulkActionResponse>> result = controller.bulkAction(request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getProcessedCount()).isEqualTo(2);
        }
    }

    // ========================================
    // getFeed
    // ========================================

    @Nested
    @DisplayName("getFeed")
    class GetFeed {

        @Test
        @DisplayName("正常系: RSSフィードが取得される")
        void RSSフィード取得_正常() {
            given(postService.listPublicPostsForFeed(TEAM_ID, null)).willReturn(List.of());
            given(feedService.generateFeedXml(any(), eq("rss"), eq(TEAM_ID), eq(null)))
                    .willReturn("<rss>...</rss>");

            ResponseEntity<String> result = controller.getFeed(TEAM_ID, null, "rss");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getHeaders().getFirst("Content-Type"))
                    .contains("application/rss+xml");
        }

        @Test
        @DisplayName("正常系: Atomフィードが取得される")
        void Atomフィード取得_正常() {
            given(postService.listPublicPostsForFeed(null, ORG_ID)).willReturn(List.of());
            given(feedService.generateFeedXml(any(), eq("atom"), eq(null), eq(ORG_ID)))
                    .willReturn("<feed>...</feed>");

            ResponseEntity<String> result = controller.getFeed(null, ORG_ID, "atom");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getHeaders().getFirst("Content-Type"))
                    .contains("application/atom+xml");
        }
    }

    // ========================================
    // duplicatePost
    // ========================================

    @Nested
    @DisplayName("duplicatePost")
    class DuplicatePost {

        @Test
        @DisplayName("正常系: 記事が複製される (201)")
        void 記事複製_正常_201() {
            given(postService.duplicatePost(eq(POST_ID), eq(USER_ID))).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result = controller.duplicatePost(POST_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
    }

    // ========================================
    // listRevisions
    // ========================================

    @Nested
    @DisplayName("listRevisions")
    class ListRevisions {

        @Test
        @DisplayName("正常系: リビジョン一覧が返却される")
        void リビジョン一覧_正常() {
            given(postService.listRevisions(POST_ID)).willReturn(List.of());

            ResponseEntity<ApiResponse<List<com.mannschaft.app.cms.dto.RevisionResponse>>> result =
                    controller.listRevisions(POST_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // restoreRevision
    // ========================================

    @Nested
    @DisplayName("restoreRevision")
    class RestoreRevision {

        @Test
        @DisplayName("正常系: リビジョンが復元される")
        void リビジョン復元_正常() {
            given(postService.restoreRevision(eq(POST_ID), eq(5L), eq(USER_ID))).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result = controller.restoreRevision(POST_ID, 5L);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // issuePreviewToken / revokePreviewToken
    // ========================================

    @Nested
    @DisplayName("issuePreviewToken")
    class IssuePreviewToken {

        @Test
        @DisplayName("正常系: プレビュートークンが発行される")
        void プレビュートークン発行_正常() {
            given(postService.issuePreviewToken(POST_ID)).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result = controller.issuePreviewToken(POST_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Nested
    @DisplayName("revokePreviewToken")
    class RevokePreviewToken {

        @Test
        @DisplayName("正常系: プレビュートークンが無効化される (204)")
        void プレビュートークン無効化_正常_204() {
            ResponseEntity<Void> result = controller.revokePreviewToken(POST_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(postService).revokePreviewToken(POST_ID);
        }
    }
}

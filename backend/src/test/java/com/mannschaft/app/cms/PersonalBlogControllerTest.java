package com.mannschaft.app.cms;

import com.mannschaft.app.cms.controller.PersonalBlogController;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BlogSettingsResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
import com.mannschaft.app.cms.dto.UpdateBlogPostRequest;
import com.mannschaft.app.cms.dto.UpdateBlogSettingsRequest;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.cms.service.UserBlogSettingsService;
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
 * {@link PersonalBlogController} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalBlogController 単体テスト")
class PersonalBlogControllerTest {

    @Mock
    private BlogPostService postService;

    @Mock
    private UserBlogSettingsService settingsService;

    @InjectMocks
    private PersonalBlogController controller;

    private static final Long USER_ID = 1L;
    private static final Long POST_ID = 100L;
    private static final Long SHARE_ID = 200L;

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
        return new BlogPostResponse(POST_ID, null, null, USER_ID, USER_ID,
                "個人記事", "my-post", "本文", null, null,
                "BLOG", "PUBLIC", "NORMAL", "PUBLISHED",
                null, false, false, 0, (short) 1, 1, null, null,
                List.of(), null, null);
    }

    // ========================================
    // listUserPosts
    // ========================================

    @Nested
    @DisplayName("listUserPosts")
    class ListUserPosts {

        @Test
        @DisplayName("正常系: ユーザーの記事一覧が返却される")
        void ユーザー記事一覧_正常() {
            Page<BlogPostResponse> page = new PageImpl<>(List.of(mockResponse()));
            given(postService.listByUser(eq(USER_ID), any())).willReturn(page);

            ResponseEntity<PagedResponse<BlogPostResponse>> result =
                    controller.listUserPosts(USER_ID, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData()).hasSize(1);
        }
    }

    // ========================================
    // getUserPost
    // ========================================

    @Nested
    @DisplayName("getUserPost")
    class GetUserPost {

        @Test
        @DisplayName("正常系: ユーザーの記事詳細がslugで返却される")
        void 記事詳細_slug_正常() {
            given(postService.getBySlug(null, null, USER_ID, "my-post")).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result =
                    controller.getUserPost(USER_ID, "my-post");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getTitle()).isEqualTo("個人記事");
        }
    }

    // ========================================
    // createPost
    // ========================================

    @Nested
    @DisplayName("createPost")
    class CreatePost {

        @Test
        @DisplayName("正常系: 個人ブログ記事が作成される (201)")
        void 記事作成_正常_201() {
            CreateBlogPostRequest request = new CreateBlogPostRequest(
                    null, null, null, "新記事", null, "本文",
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
    // sharePost
    // ========================================

    @Nested
    @DisplayName("sharePost")
    class SharePost {

        @Test
        @DisplayName("正常系: 記事が共有される (201)")
        void 記事共有_正常_201() {
            SharePostRequest request = new SharePostRequest(10L, null);
            SharePostResponse response = new SharePostResponse(SHARE_ID, POST_ID, 10L, null);
            given(postService.sharePost(eq(POST_ID), eq(USER_ID), any())).willReturn(response);

            ResponseEntity<ApiResponse<SharePostResponse>> result = controller.sharePost(POST_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(result.getBody().getData().getShareId()).isEqualTo(SHARE_ID);
        }
    }

    // ========================================
    // revokeShare
    // ========================================

    @Nested
    @DisplayName("revokeShare")
    class RevokeShare {

        @Test
        @DisplayName("正常系: 共有が取り消される (204)")
        void 共有取消_正常_204() {
            ResponseEntity<Void> result = controller.revokeShare(POST_ID, SHARE_ID);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(postService).revokeShare(POST_ID, SHARE_ID);
        }
    }

    // ========================================
    // selfReview
    // ========================================

    @Nested
    @DisplayName("selfReview")
    class SelfReview {

        @Test
        @DisplayName("正常系: セルフレビュー結果が処理される")
        void セルフレビュー処理_正常() {
            SelfReviewRequest request = new SelfReviewRequest("PUBLISH");
            given(postService.selfReview(eq(POST_ID), eq(USER_ID), any())).willReturn(mockResponse());

            ResponseEntity<ApiResponse<BlogPostResponse>> result = controller.selfReview(POST_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ========================================
    // getSettings / updateSettings
    // ========================================

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("正常系: セルフレビュー設定が返却される")
        void 設定取得_正常() {
            BlogSettingsResponse settings = new BlogSettingsResponse(true, null, null);
            given(settingsService.getOrCreateSettings(USER_ID)).willReturn(settings);

            ResponseEntity<ApiResponse<BlogSettingsResponse>> result = controller.getSettings();

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getSelfReviewEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("正常系: セルフレビュー設定が更新される")
        void 設定更新_正常() {
            UpdateBlogSettingsRequest request = new UpdateBlogSettingsRequest(false, null, null);
            BlogSettingsResponse settings = new BlogSettingsResponse(false, null, null);
            given(settingsService.updateSettings(eq(USER_ID), any())).willReturn(settings);

            ResponseEntity<ApiResponse<BlogSettingsResponse>> result = controller.updateSettings(request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().getSelfReviewEnabled()).isFalse();
        }
    }
}

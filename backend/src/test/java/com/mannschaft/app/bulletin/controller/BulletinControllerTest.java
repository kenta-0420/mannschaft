package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.dto.ArchiveThreadRequest;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.CreateCategoryRequest;
import com.mannschaft.app.bulletin.dto.CreateReplyRequest;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.DeleteCategoryResponse;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateCategoryRequest;
import com.mannschaft.app.bulletin.dto.UpdateReplyRequest;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.service.BulletinCategoryService;
import com.mannschaft.app.bulletin.service.BulletinReplyService;
import com.mannschaft.app.bulletin.service.BulletinScopeIdResolver;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 掲示板コントローラー群の単体テスト。
 * SecurityContextHolder を設定してコントローラーを直接呼び出す。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("掲示板コントローラー 単体テスト")
class BulletinControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final String SCOPE_ID_STR = "10";
    private static final String SCOPE_TYPE = "TEAM";
    private static final Long CATEGORY_ID = 5L;
    private static final Long THREAD_ID = 100L;
    private static final Long REPLY_ID = 200L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // BulletinCategoryController
    // ========================================

    @Nested
    @DisplayName("BulletinCategoryController")
    class CategoryControllerTests {

        @Mock
        private BulletinCategoryService categoryService;

        @Mock
        private BulletinScopeIdResolver scopeIdResolver;

        @InjectMocks
        private BulletinCategoryController categoryController;

        private CategoryResponse createCategoryResponse() {
            return new CategoryResponse(
                    CATEGORY_ID, "TEAM", SCOPE_ID, "一般", "一般的な話題",
                    1, "#FF5733", "MEMBER", USER_ID, null, null);
        }

        @Test
        @DisplayName("正常系: カテゴリ一覧が200で返る")
        void listCategories_正常_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(categoryService.listCategories(any(), eq(SCOPE_ID), eq(USER_ID)))
                    .willReturn(List.of(createCategoryResponse()));

            // When
            ResponseEntity<ApiResponse<List<CategoryResponse>>> response =
                    categoryController.listCategories(SCOPE_TYPE, SCOPE_ID_STR);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            verify(categoryService).listCategories(any(), eq(SCOPE_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("正常系: カテゴリ詳細が200で返る")
        void getCategory_正常_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(categoryService.getCategory(any(), eq(SCOPE_ID), eq(CATEGORY_ID), eq(USER_ID)))
                    .willReturn(createCategoryResponse());

            // When
            ResponseEntity<ApiResponse<CategoryResponse>> response =
                    categoryController.getCategory(SCOPE_TYPE, SCOPE_ID_STR, CATEGORY_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getName()).isEqualTo("一般");
        }

        @Test
        @DisplayName("正常系: カテゴリ作成が201で返る")
        void createCategory_正常_201() {
            // Given
            CreateCategoryRequest request = new CreateCategoryRequest("新カテゴリ", "説明", 1, "#000000", "MEMBER");
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(categoryService.createCategory(any(), eq(SCOPE_ID), eq(USER_ID), eq(request)))
                    .willReturn(createCategoryResponse());

            // When
            ResponseEntity<ApiResponse<CategoryResponse>> response =
                    categoryController.createCategory(SCOPE_TYPE, SCOPE_ID_STR, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData()).isNotNull();
        }

        @Test
        @DisplayName("正常系: カテゴリ更新が200で返る")
        void updateCategory_正常_200() {
            // Given
            UpdateCategoryRequest request = new UpdateCategoryRequest("更新カテゴリ", "更新説明", 2, "#FFFFFF", "ADMIN");
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(categoryService.updateCategory(any(), eq(SCOPE_ID), eq(CATEGORY_ID), eq(USER_ID), eq(request)))
                    .willReturn(createCategoryResponse());

            // When
            ResponseEntity<ApiResponse<CategoryResponse>> response =
                    categoryController.updateCategory(SCOPE_TYPE, SCOPE_ID_STR, CATEGORY_ID, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
        }

        @Test
        @DisplayName("正常系: カテゴリ削除が200で返り未分類化件数を含む")
        void deleteCategory_正常_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(categoryService.deleteCategory(any(), eq(SCOPE_ID), eq(CATEGORY_ID), eq(USER_ID)))
                    .willReturn(new DeleteCategoryResponse(CATEGORY_ID, 3,
                            "カテゴリを削除しました。3件のスレッドが未分類に移行しました"));

            // When
            ResponseEntity<ApiResponse<DeleteCategoryResponse>> response =
                    categoryController.deleteCategory(SCOPE_TYPE, SCOPE_ID_STR, CATEGORY_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
            assertThat(response.getBody().getData().getAffectedThreadCount()).isEqualTo(3);
            verify(categoryService).deleteCategory(any(), eq(SCOPE_ID), eq(CATEGORY_ID), eq(USER_ID));
        }
    }

    // ========================================
    // BulletinThreadController
    // ========================================

    @Nested
    @DisplayName("BulletinThreadController")
    class ThreadControllerTests {

        @Mock
        private BulletinThreadService threadService;

        @Mock
        private BulletinScopeIdResolver scopeIdResolver;

        @InjectMocks
        private BulletinThreadController threadController;

        private ThreadResponse createThreadResponse() {
            return ThreadResponse.builder()
                    .id(THREAD_ID)
                    .categoryId(CATEGORY_ID)
                    .scopeType("TEAM")
                    .scopeId(SCOPE_ID)
                    .author(new ThreadResponse.AuthorDto(USER_ID, "テストユーザー", null))
                    .title("テストスレッド")
                    .body("本文")
                    .priority("INFO")
                    .readTrackingMode("COUNT_ONLY")
                    .isPinned(false)
                    .isLocked(false)
                    .isArchived(false)
                    .replyCount(0)
                    .readCount(0)
                    .isRead(false)
                    .reactionSummary(java.util.Collections.emptyMap())
                    .myReactions(java.util.Collections.emptyList())
                    .build();
        }

        @Test
        @DisplayName("正常系: スレッド一覧（categoryId指定なし）が200で返る")
        void listThreads_カテゴリ指定なし_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            Page<ThreadResponse> page = new PageImpl<>(
                    List.of(createThreadResponse()), PageRequest.of(0, 20), 1);
            given(threadService.listThreads(any(), eq(SCOPE_ID), eq(USER_ID), any())).willReturn(page);

            // When
            ResponseEntity<PagedResponse<ThreadResponse>> response =
                    threadController.listThreads(SCOPE_TYPE, SCOPE_ID_STR, null, 0, 20);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: スレッド一覧（categoryId指定あり）が200で返る")
        void listThreads_カテゴリ指定あり_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            Page<ThreadResponse> page = new PageImpl<>(
                    List.of(createThreadResponse()), PageRequest.of(0, 20), 1);
            given(threadService.listThreadsByCategory(any(), eq(SCOPE_ID), eq(CATEGORY_ID), eq(USER_ID), any())).willReturn(page);

            // When
            ResponseEntity<PagedResponse<ThreadResponse>> response =
                    threadController.listThreads(SCOPE_TYPE, SCOPE_ID_STR, CATEGORY_ID, 0, 20);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: スレッド詳細が200で返る")
        void getThread_正常_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.getThread(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID)))
                    .willReturn(createThreadResponse());

            // When
            ResponseEntity<ApiResponse<ThreadResponse>> response =
                    threadController.getThread(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getTitle()).isEqualTo("テストスレッド");
        }

        @Test
        @DisplayName("正常系: スレッド検索が200で返る")
        void searchThreads_正常_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            Page<ThreadResponse> page = new PageImpl<>(
                    List.of(createThreadResponse()), PageRequest.of(0, 20), 1);
            given(threadService.searchThreads(any(), eq(SCOPE_ID), eq(USER_ID), eq("テスト"), any())).willReturn(page);

            // When
            ResponseEntity<PagedResponse<ThreadResponse>> response =
                    threadController.searchThreads(SCOPE_TYPE, SCOPE_ID_STR, "テスト", 0, 20);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: スレッド作成が201で返る")
        void createThread_正常_201() {
            // Given
            CreateThreadRequest request = new CreateThreadRequest(
                    CATEGORY_ID, "タイトル", "本文", "INFO", "COUNT_ONLY", null, null);
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.createThread(any(), eq(SCOPE_ID), eq(USER_ID), eq(request)))
                    .willReturn(createThreadResponse());

            // When
            ResponseEntity<ApiResponse<ThreadResponse>> response =
                    threadController.createThread(SCOPE_TYPE, SCOPE_ID_STR, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData()).isNotNull();
        }

        @Test
        @DisplayName("正常系: スレッド更新が200で返る")
        void updateThread_正常_200() {
            // Given
            UpdateThreadRequest request = new UpdateThreadRequest("更新タイトル", "更新本文", "IMPORTANT");
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.updateThread(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(request)))
                    .willReturn(createThreadResponse());

            // When
            ResponseEntity<ApiResponse<ThreadResponse>> response =
                    threadController.updateThread(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: スレッド削除が204で返る")
        void deleteThread_正常_204() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);

            // When
            ResponseEntity<Void> response = threadController.deleteThread(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(threadService).deleteThread(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("正常系: ピン留め切替が200で返る")
        void togglePin_正常_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.togglePin(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID)))
                    .willReturn(createThreadResponse());

            // When
            ResponseEntity<ApiResponse<ThreadResponse>> response =
                    threadController.togglePin(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: ロック切替が200で返る")
        void toggleLock_正常_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.toggleLock(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID)))
                    .willReturn(createThreadResponse());

            // When
            ResponseEntity<ApiResponse<ThreadResponse>> response =
                    threadController.toggleLock(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("正常系: アーカイブ（body無し=後方互換でtrue）が200で返る")
        void archive_body無し_200() {
            // Given: body 無しは後方互換で isArchived=true として委譲される
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.archive(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(true), isNull()))
                    .willReturn(createThreadResponse());

            // When
            ResponseEntity<ApiResponse<ThreadResponse>> response =
                    threadController.archive(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, null);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(threadService).archive(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(true), isNull());
        }

        @Test
        @DisplayName("正常系: is_archived=true でアーカイブが200で返る")
        void archive_true_200() {
            // Given
            ArchiveThreadRequest request = new ArchiveThreadRequest();
            request.setIsArchived(true);
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.archive(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(true), isNull()))
                    .willReturn(createThreadResponse());

            // When
            ResponseEntity<ApiResponse<ThreadResponse>> response =
                    threadController.archive(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(threadService).archive(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(true), isNull());
        }

        @Test
        @DisplayName("正常系: is_archived=false でアーカイブ解除が200で返る")
        void archive_false_解除_200() {
            // Given
            ArchiveThreadRequest request = new ArchiveThreadRequest();
            request.setIsArchived(false);
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.archive(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(false), isNull()))
                    .willReturn(createThreadResponse());

            // When
            ResponseEntity<ApiResponse<ThreadResponse>> response =
                    threadController.archive(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(threadService).archive(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(false), isNull());
        }

        @Test
        @DisplayName("正常系: 検索結果が空の場合も200で返る")
        void searchThreads_空結果_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            Page<ThreadResponse> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 20), 0);
            given(threadService.searchThreads(any(), eq(SCOPE_ID), eq(USER_ID), eq("存在しない"), any())).willReturn(emptyPage);

            // When
            ResponseEntity<PagedResponse<ThreadResponse>> response =
                    threadController.searchThreads(SCOPE_TYPE, SCOPE_ID_STR, "存在しない", 0, 20);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isEmpty();
        }
    }

    // ========================================
    // BulletinReplyController
    // ========================================

    @Nested
    @DisplayName("BulletinReplyController")
    class ReplyControllerTests {

        @Mock
        private BulletinReplyService replyService;

        @Mock
        private BulletinScopeIdResolver scopeIdResolver;

        @InjectMocks
        private BulletinReplyController replyController;

        private ReplyResponse createReplyResponse() {
            return new ReplyResponse(
                    REPLY_ID, THREAD_ID, null, USER_ID,
                    "返信の本文", false, 0, null, null, 0, Collections.emptyList());
        }

        @Test
        @DisplayName("正常系: 返信一覧が200で返る")
        void listReplies_正常_200() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            Page<ReplyResponse> page = new PageImpl<>(
                    List.of(createReplyResponse()), PageRequest.of(0, 20), 1);
            given(replyService.listReplies(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), any())).willReturn(page);

            // When
            ResponseEntity<PagedResponse<ReplyResponse>> response =
                    replyController.listReplies(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, 0, 20);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 返信作成が201で返る")
        void createReply_正常_201() {
            // Given
            CreateReplyRequest request = new CreateReplyRequest(null, "返信本文");
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(replyService.createReply(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(request)))
                    .willReturn(createReplyResponse());

            // When
            ResponseEntity<ApiResponse<ReplyResponse>> response =
                    replyController.createReply(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getBody()).isEqualTo("返信の本文");
        }

        @Test
        @DisplayName("正常系: 返信更新が200で返る")
        void updateReply_正常_200() {
            // Given
            UpdateReplyRequest request = new UpdateReplyRequest("更新された返信");
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(replyService.updateReply(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(REPLY_ID), eq(USER_ID), eq(request)))
                    .willReturn(createReplyResponse());

            // When
            ResponseEntity<ApiResponse<ReplyResponse>> response =
                    replyController.updateReply(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, REPLY_ID, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isNotNull();
        }

        @Test
        @DisplayName("正常系: 返信削除が204で返る")
        void deleteReply_正常_204() {
            // Given
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);

            // When
            ResponseEntity<Void> response =
                    replyController.deleteReply(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, REPLY_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(replyService).deleteReply(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(REPLY_ID), eq(USER_ID));
        }

        @Test
        @DisplayName("正常系: 子返信付き返信作成が201で返る")
        void createReply_子返信_201() {
            // Given
            CreateReplyRequest request = new CreateReplyRequest(REPLY_ID, "子返信本文");
            ReplyResponse childReply = new ReplyResponse(
                    201L, THREAD_ID, REPLY_ID, USER_ID,
                    "子返信本文", false, 0, null, null, 1, Collections.emptyList());
            given(scopeIdResolver.resolve(any(), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(replyService.createReply(any(), eq(SCOPE_ID), eq(THREAD_ID), eq(USER_ID), eq(request)))
                    .willReturn(childReply);

            // When
            ResponseEntity<ApiResponse<ReplyResponse>> response =
                    replyController.createReply(SCOPE_TYPE, SCOPE_ID_STR, THREAD_ID, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getParentId()).isEqualTo(REPLY_ID);
        }
    }
}

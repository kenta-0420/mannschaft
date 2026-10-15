package com.mannschaft.app.bulletin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.DeleteCategoryResponse;
import com.mannschaft.app.bulletin.dto.GlobalCreateCategoryRequest;
import com.mannschaft.app.bulletin.dto.GlobalCreateReplyRequest;
import com.mannschaft.app.bulletin.dto.ReadAllRequest;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.UpdateCategoryRequest;
import com.mannschaft.app.bulletin.dto.UpdateReplyRequest;
import com.mannschaft.app.bulletin.service.BulletinCategoryService;
import com.mannschaft.app.bulletin.service.BulletinReadStatusService;
import com.mannschaft.app.bulletin.service.BulletinReplyService;
import com.mannschaft.app.bulletin.service.BulletinScopeIdResolver;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 掲示板グローバル方式 書込系コントローラー（カテゴリ CRUD / 返信 / 既読）の単体テスト。
 *
 * <p>SecurityContextHolder を設定してコントローラーを直接呼び出す（既存
 * {@code GlobalBulletinControllerTest} と同流儀）。委譲・スコープ分岐・不正引数 400 を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("掲示板グローバル方式 書込系コントローラー 単体テスト")
class GlobalBulletinWriteControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final Long CATEGORY_ID = 5L;
    private static final Long THREAD_ID = 100L;
    private static final Long REPLY_ID = 200L;
    private static final UUID VILLAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private CategoryResponse categoryResponse() {
        return new CategoryResponse(CATEGORY_ID, "VILLAGE", 0L, "一般", "説明",
                1, "#FF5733", "MEMBER", USER_ID, null, null);
    }

    private ReplyResponse replyResponse() {
        return new ReplyResponse(REPLY_ID, THREAD_ID, null, USER_ID, "本文",
                false, 0, LocalDateTime.now(), LocalDateTime.now(), 0, List.of());
    }

    // ========================================================================
    // GlobalBulletinCategoryController — CRUD
    // ========================================================================

    @Nested
    @DisplayName("GlobalBulletinCategoryController CRUD")
    class CategoryCrud {

        @Mock
        private BulletinCategoryService categoryService;

        @Mock
        private BulletinScopeIdResolver scopeIdResolver;

        @InjectMocks
        private GlobalBulletinCategoryController controller;

        private GlobalCreateCategoryRequest createReq(String scopeType, Long scopeId, UUID villageId) {
            GlobalCreateCategoryRequest req = new GlobalCreateCategoryRequest();
            req.setScopeType(scopeType);
            req.setScopeId(scopeId);
            req.setScopeVillageId(villageId);
            req.setName("一般");
            return req;
        }

        @Test
        @DisplayName("VILLAGE作成_村経路へ委譲して201")
        void village作成_201() {
            given(categoryService.createCategoryGlobal(
                    eq(ScopeType.VILLAGE), eq(0L), eq(VILLAGE_ID), eq(USER_ID), any()))
                    .willReturn(categoryResponse());

            ResponseEntity<ApiResponse<CategoryResponse>> response =
                    controller.createCategory(createReq("VILLAGE", 0L, VILLAGE_ID));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().getData().getId()).isEqualTo(CATEGORY_ID);
            verify(categoryService).createCategoryGlobal(
                    eq(ScopeType.VILLAGE), eq(0L), eq(VILLAGE_ID), eq(USER_ID), any());
        }

        @Test
        @DisplayName("TEAM作成_既存scopeId経路へ委譲して201")
        void team作成_委譲_201() {
            given(categoryService.createCategoryGlobal(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), isNull(), eq(USER_ID), any()))
                    .willReturn(categoryResponse());

            ResponseEntity<ApiResponse<CategoryResponse>> response =
                    controller.createCategory(createReq("TEAM", SCOPE_ID, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(categoryService).createCategoryGlobal(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), isNull(), eq(USER_ID), any());
        }

        @Test
        @DisplayName("作成_scope_type不正_COMMON_001（400相当・500を撒かない）")
        void 作成_scope_type不正_400() {
            assertThatThrownBy(() -> controller.createCategory(createReq("INVALID", 0L, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_001));
            verify(categoryService, never()).createCategoryGlobal(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("更新_categoryId経路でグローバル更新へ委譲して200")
        void 更新_200() {
            UpdateCategoryRequest req = new UpdateCategoryRequest("一般", null, 1, "#FF5733", "MEMBER");
            given(categoryService.updateCategoryGlobal(CATEGORY_ID, USER_ID, req))
                    .willReturn(categoryResponse());

            ResponseEntity<ApiResponse<CategoryResponse>> response =
                    controller.updateCategory(CATEGORY_ID, req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(categoryService).updateCategoryGlobal(CATEGORY_ID, USER_ID, req);
        }

        @Test
        @DisplayName("削除_categoryId経路でグローバル削除へ委譲して200（未分類件数を返す）")
        void 削除_200() {
            given(categoryService.deleteCategoryGlobal(CATEGORY_ID, USER_ID))
                    .willReturn(new DeleteCategoryResponse(CATEGORY_ID, 3, "3件のスレッドが未分類に移行しました"));

            ResponseEntity<ApiResponse<DeleteCategoryResponse>> response =
                    controller.deleteCategory(CATEGORY_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getAffectedThreadCount()).isEqualTo(3);
            verify(categoryService).deleteCategoryGlobal(CATEGORY_ID, USER_ID);
        }
    }

    // ========================================================================
    // GlobalBulletinReplyController
    // ========================================================================

    @Nested
    @DisplayName("GlobalBulletinReplyController")
    class ReplyController {

        @Mock
        private BulletinReplyService replyService;

        @InjectMocks
        private GlobalBulletinReplyController controller;

        @Test
        @DisplayName("返信一覧_threadId経路でグローバル取得へ委譲してメタ付き200")
        void 一覧_200() {
            Page<ReplyResponse> page = new PageImpl<>(List.of(replyResponse()), PageRequest.of(0, 20), 1);
            given(replyService.listRepliesGlobal(eq(THREAD_ID), eq(USER_ID), any())).willReturn(page);

            ResponseEntity<PagedResponse<ReplyResponse>> response = controller.listReplies(THREAD_ID, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            assertThat(response.getBody().getMeta().getTotal()).isEqualTo(1L);
            verify(replyService).listRepliesGlobal(eq(THREAD_ID), eq(USER_ID), any());
        }

        @Test
        @DisplayName("返信作成_parentId=nullでグローバル作成へ委譲して201")
        void 作成_201() {
            given(replyService.createReplyGlobal(THREAD_ID, null, USER_ID, "本文")).willReturn(replyResponse());
            GlobalCreateReplyRequest req = new GlobalCreateReplyRequest();
            req.setBody("本文");

            ResponseEntity<ApiResponse<ReplyResponse>> response = controller.createReply(THREAD_ID, req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(replyService).createReplyGlobal(THREAD_ID, null, USER_ID, "本文");
        }

        @Test
        @DisplayName("ネスト返信作成_replyId経路でネスト作成へ委譲して201")
        void ネスト作成_201() {
            given(replyService.createNestedReplyGlobal(REPLY_ID, USER_ID, "返信の返信")).willReturn(replyResponse());
            GlobalCreateReplyRequest req = new GlobalCreateReplyRequest();
            req.setBody("返信の返信");

            ResponseEntity<ApiResponse<ReplyResponse>> response = controller.createNestedReply(REPLY_ID, req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(replyService).createNestedReplyGlobal(REPLY_ID, USER_ID, "返信の返信");
        }

        @Test
        @DisplayName("返信更新_replyId経路でグローバル更新へ委譲して200")
        void 更新_200() {
            given(replyService.updateReplyGlobal(REPLY_ID, USER_ID, "修正")).willReturn(replyResponse());

            ResponseEntity<ApiResponse<ReplyResponse>> response =
                    controller.updateReply(REPLY_ID, new UpdateReplyRequest("修正"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(replyService).updateReplyGlobal(REPLY_ID, USER_ID, "修正");
        }

        @Test
        @DisplayName("返信削除_replyId経路でグローバル削除へ委譲して204")
        void 削除_204() {
            ResponseEntity<Void> response = controller.deleteReply(REPLY_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(replyService).deleteReplyGlobal(REPLY_ID, USER_ID);
        }
    }

    // ========================================================================
    // GlobalBulletinThreadController — 既読系
    // ========================================================================

    @Nested
    @DisplayName("GlobalBulletinThreadController 既読系")
    class ReadStatus {

        @Mock
        private BulletinThreadService threadService;

        @Mock
        private BulletinReadStatusService readStatusService;

        @Mock
        private ObjectMapper objectMapper;

        @Mock
        private BulletinScopeIdResolver scopeIdResolver;

        @InjectMocks
        private GlobalBulletinThreadController controller;

        @Test
        @DisplayName("既読マーク_threadId経路でグローバル既読へ委譲して201")
        void 既読_201() {
            ResponseEntity<Void> response = controller.markRead(THREAD_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            verify(readStatusService).markAsReadGlobal(THREAD_ID, USER_ID);
        }

        @Test
        @DisplayName("一括既読_VILLAGE_村経路へ委譲して件数を返す")
        void 一括既読_village_200() {
            given(readStatusService.markAllAsReadGlobal(ScopeType.VILLAGE, 0L, VILLAGE_ID, USER_ID))
                    .willReturn(4);
            ReadAllRequest req = new ReadAllRequest();
            req.setScopeType("VILLAGE");
            req.setScopeId(0L);
            req.setScopeVillageId(VILLAGE_ID);

            ResponseEntity<ApiResponse<GlobalBulletinThreadController.MarkAllReadResult>> response =
                    controller.markAllRead(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().markedCount()).isEqualTo(4);
            verify(readStatusService).markAllAsReadGlobal(ScopeType.VILLAGE, 0L, VILLAGE_ID, USER_ID);
        }

        @Test
        @DisplayName("一括既読_TEAM_既存scopeId経路へ委譲")
        void 一括既読_team_200() {
            given(readStatusService.markAllAsReadGlobal(ScopeType.TEAM, SCOPE_ID, null, USER_ID))
                    .willReturn(0);
            ReadAllRequest req = new ReadAllRequest();
            req.setScopeType("TEAM");
            req.setScopeId(SCOPE_ID);

            ResponseEntity<ApiResponse<GlobalBulletinThreadController.MarkAllReadResult>> response =
                    controller.markAllRead(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(readStatusService).markAllAsReadGlobal(ScopeType.TEAM, SCOPE_ID, null, USER_ID);
        }

        @Test
        @DisplayName("一括既読_scope_type不正_COMMON_001（400相当）")
        void 一括既読_scope_type不正_400() {
            ReadAllRequest req = new ReadAllRequest();
            req.setScopeType("INVALID");

            assertThatThrownBy(() -> controller.markAllRead(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_001));
            verify(readStatusService, never()).markAllAsReadGlobal(any(), any(), any(), any());
        }

        @Test
        @DisplayName("既読者一覧_threadId経路でグローバル取得へ委譲して200")
        void 既読者一覧_200() {
            given(readStatusService.listReadUsersGlobal(THREAD_ID, USER_ID, null))
                    .willReturn(List.of(new ReadStatusResponse(1L, THREAD_ID, USER_ID, LocalDateTime.now())));

            ResponseEntity<ApiResponse<List<ReadStatusResponse>>> response =
                    controller.listReaders(THREAD_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            verify(readStatusService).listReadUsersGlobal(THREAD_ID, USER_ID, null);
        }

        @Test
        @DisplayName("既読者一覧_filter=unreadをサービスへ透過")
        void 既読者一覧_unread透過() {
            given(readStatusService.listReadUsersGlobal(THREAD_ID, USER_ID, "unread"))
                    .willReturn(List.of());

            ResponseEntity<ApiResponse<List<ReadStatusResponse>>> response =
                    controller.listReaders(THREAD_ID, "unread");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(readStatusService).listReadUsersGlobal(THREAD_ID, USER_ID, "unread");
        }
    }
}

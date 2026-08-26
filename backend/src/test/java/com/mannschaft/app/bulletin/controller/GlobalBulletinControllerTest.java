package com.mannschaft.app.bulletin.controller;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.service.BulletinCategoryService;
import com.mannschaft.app.bulletin.service.BulletinReadStatusService;
import com.mannschaft.app.bulletin.service.BulletinScopeIdResolver;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.PagedResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 掲示板グローバル方式コントローラー（{@link GlobalBulletinCategoryController} /
 * {@link GlobalBulletinThreadController}）の単体テスト。
 *
 * <p>SecurityContextHolder を設定してコントローラーを直接呼び出す（既存
 * {@code BulletinControllerTest} と同流儀）。村経路 / ORG・TEAM 委譲 / 不正引数 400 を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("掲示板グローバル方式コントローラー 単体テスト")
class GlobalBulletinControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final String SCOPE_ID_STR = "10";
    private static final String VILLAGE_SCOPE_ID_STR = "0";
    private static final Long CATEGORY_ID = 5L;
    private static final Long THREAD_ID = 100L;
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

    private ThreadResponse threadResponse() {
        return ThreadResponse.builder()
                .id(THREAD_ID)
                .categoryId(CATEGORY_ID)
                .scopeType("VILLAGE")
                .scopeId(0L)
                .author(new ThreadResponse.AuthorDto(USER_ID, "テストユーザー", null))
                .title("題名")
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

    // ========================================================================
    // GlobalBulletinCategoryController
    // ========================================================================

    @Nested
    @DisplayName("GlobalBulletinCategoryController")
    class CategoryController {

        @Mock
        private BulletinCategoryService categoryService;

        @Mock
        private BulletinScopeIdResolver scopeIdResolver;

        @InjectMocks
        private GlobalBulletinCategoryController controller;

        @Test
        @DisplayName("VILLAGE一覧_村経路へ委譲して200")
        void village一覧_200() {
            given(categoryService.listVillageCategories(VILLAGE_ID, USER_ID))
                    .willReturn(List.of(categoryResponse()));

            ResponseEntity<ApiResponse<List<CategoryResponse>>> response =
                    controller.listCategories("VILLAGE", VILLAGE_SCOPE_ID_STR, VILLAGE_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            verify(categoryService).listVillageCategories(VILLAGE_ID, USER_ID);
        }

        @Test
        @DisplayName("TEAM一覧_既存scopeId経路へ委譲して200")
        void team一覧_委譲_200() {
            given(scopeIdResolver.resolve(eq(ScopeType.TEAM), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(categoryService.listCategories(eq(ScopeType.TEAM), eq(SCOPE_ID), eq(USER_ID)))
                    .willReturn(List.of(categoryResponse()));

            ResponseEntity<ApiResponse<List<CategoryResponse>>> response =
                    controller.listCategories("TEAM", SCOPE_ID_STR, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(categoryService).listCategories(ScopeType.TEAM, SCOPE_ID, USER_ID);
            verify(categoryService, never()).listVillageCategories(any(), any());
        }

        @Test
        @DisplayName("VILLAGEでscope_village_id欠落_COMMON_001（400相当）")
        void village_village_id欠落_400() {
            assertThatThrownBy(() -> controller.listCategories("VILLAGE", VILLAGE_SCOPE_ID_STR, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_001));
            verify(categoryService, never()).listVillageCategories(any(), any());
        }

        @Test
        @DisplayName("scope_type不正値_COMMON_001（400相当・500を撒かない）")
        void scope_type不正_400() {
            assertThatThrownBy(() -> controller.listCategories("INVALID", VILLAGE_SCOPE_ID_STR, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_001));
        }
    }

    // ========================================================================
    // GlobalBulletinThreadController
    // ========================================================================

    @Nested
    @DisplayName("GlobalBulletinThreadController")
    class ThreadController {

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

        private Page<ThreadResponse> page() {
            return new PageImpl<>(List.of(threadResponse()), PageRequest.of(0, 20), 1);
        }

        @Test
        @DisplayName("VILLAGE一覧_村経路へ委譲してメタ付き200")
        void village一覧_200() {
            given(threadService.listVillageThreads(eq(VILLAGE_ID), isNull(), eq(USER_ID), any()))
                    .willReturn(page());

            ResponseEntity<PagedResponse<ThreadResponse>> response =
                    controller.listThreads("VILLAGE", VILLAGE_SCOPE_ID_STR, VILLAGE_ID, null, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            assertThat(response.getBody().getMeta().getTotal()).isEqualTo(1L);
            assertThat(response.getBody().getMeta().getPage()).isEqualTo(0);
            assertThat(response.getBody().getMeta().getSize()).isEqualTo(20);
            verify(threadService).listVillageThreads(eq(VILLAGE_ID), isNull(), eq(USER_ID), any());
        }

        @Test
        @DisplayName("VILLAGE一覧_category_id指定_村経路へカテゴリ渡し")
        void village一覧_カテゴリ指定_200() {
            given(threadService.listVillageThreads(eq(VILLAGE_ID), eq(CATEGORY_ID), eq(USER_ID), any()))
                    .willReturn(page());

            ResponseEntity<PagedResponse<ThreadResponse>> response =
                    controller.listThreads("VILLAGE", VILLAGE_SCOPE_ID_STR, VILLAGE_ID, CATEGORY_ID, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(threadService).listVillageThreads(eq(VILLAGE_ID), eq(CATEGORY_ID), eq(USER_ID), any());
        }

        @Test
        @DisplayName("TEAM一覧_category未指定_既存listThreadsへ委譲")
        void team一覧_委譲_200() {
            given(scopeIdResolver.resolve(eq(ScopeType.TEAM), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.listThreads(eq(ScopeType.TEAM), eq(SCOPE_ID), eq(USER_ID), any()))
                    .willReturn(page());

            ResponseEntity<PagedResponse<ThreadResponse>> response =
                    controller.listThreads("TEAM", SCOPE_ID_STR, null, null, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(threadService).listThreads(ScopeType.TEAM, SCOPE_ID, USER_ID, PageRequest.of(0, 20));
            verify(threadService, never()).listVillageThreads(any(), any(), any(), any());
        }

        @Test
        @DisplayName("TEAM一覧_category指定_既存listThreadsByCategoryへ委譲")
        void team一覧_カテゴリ_委譲_200() {
            given(scopeIdResolver.resolve(eq(ScopeType.TEAM), eq(SCOPE_ID_STR))).willReturn(SCOPE_ID);
            given(threadService.listThreadsByCategory(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), eq(CATEGORY_ID), eq(USER_ID), any()))
                    .willReturn(page());

            ResponseEntity<PagedResponse<ThreadResponse>> response =
                    controller.listThreads("TEAM", SCOPE_ID_STR, null, CATEGORY_ID, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(threadService).listThreadsByCategory(
                    ScopeType.TEAM, SCOPE_ID, CATEGORY_ID, USER_ID, PageRequest.of(0, 20));
        }

        @Test
        @DisplayName("詳細_threadId経路でグローバル詳細へ委譲して200")
        void 詳細_200() {
            given(threadService.getThreadGlobal(THREAD_ID, USER_ID)).willReturn(threadResponse());

            ResponseEntity<ApiResponse<ThreadResponse>> response = controller.getThread(THREAD_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getId()).isEqualTo(THREAD_ID);
            verify(threadService).getThreadGlobal(THREAD_ID, USER_ID);
        }

        @Test
        @DisplayName("VILLAGE一覧_scope_village_id欠落_COMMON_001（400相当）")
        void village一覧_village_id欠落_400() {
            assertThatThrownBy(() -> controller.listThreads("VILLAGE", VILLAGE_SCOPE_ID_STR, null, null, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_001));
            verify(threadService, never()).listVillageThreads(any(), any(), any(), any());
        }

        @Test
        @DisplayName("scope_type不正値_COMMON_001（400相当・500を撒かない）")
        void scope_type不正_400() {
            assertThatThrownBy(() -> controller.listThreads("INVALID", VILLAGE_SCOPE_ID_STR, null, null, 0, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_001));
        }
    }
}

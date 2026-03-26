package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinThreadService} の単体テスト。
 * スレッドのCRUD・検索・状態管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinThreadService 単体テスト")
class BulletinThreadServiceTest {

    @Mock
    private BulletinThreadRepository threadRepository;

    @Mock
    private BulletinCategoryService categoryService;

    @Mock
    private BulletinMapper bulletinMapper;

    @InjectMocks
    private BulletinThreadService bulletinThreadService;

    private static final Long THREAD_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long CATEGORY_ID = 5L;
    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;

    private BulletinThreadEntity createDefaultThread() {
        return BulletinThreadEntity.builder()
                .categoryId(CATEGORY_ID)
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .authorId(USER_ID)
                .title("テストスレッド")
                .body("テスト本文")
                .priority(Priority.INFO)
                .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                .build();
    }

    private ThreadResponse createThreadResponse() {
        return new ThreadResponse(
                THREAD_ID, CATEGORY_ID, "TEAM", SCOPE_ID, USER_ID,
                "テストスレッド", "テスト本文", "INFO", "COUNT_ONLY",
                false, false, false, 0, 0, null, null, null, null, null);
    }

    // ========================================
    // listThreads
    // ========================================

    @Nested
    @DisplayName("listThreads")
    class ListThreads {

        @Test
        @DisplayName("スレッド一覧取得_正常_ページ返却")
        void スレッド一覧取得_正常_ページ返却() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(threadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID, PageRequest.of(0, 10))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            Page<ThreadResponse> result = bulletinThreadService.listThreads(SCOPE_TYPE, SCOPE_ID, PageRequest.of(0, 10));

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // listThreadsByCategory
    // ========================================

    @Nested
    @DisplayName("listThreadsByCategory")
    class ListThreadsByCategory {

        @Test
        @DisplayName("カテゴリ指定一覧取得_正常_ページ返却")
        void カテゴリ指定一覧取得_正常_ページ返却() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(threadRepository.findByCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
                    CATEGORY_ID, PageRequest.of(0, 10))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            Page<ThreadResponse> result = bulletinThreadService.listThreadsByCategory(CATEGORY_ID, PageRequest.of(0, 10));

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // getThread
    // ========================================

    @Nested
    @DisplayName("getThread")
    class GetThread {

        @Test
        @DisplayName("スレッド詳細取得_正常_レスポンス返却")
        void スレッド詳細取得_正常_レスポンス返却() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.getThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("テストスレッド");
        }

        @Test
        @DisplayName("スレッド詳細取得_存在しない_BusinessException")
        void スレッド詳細取得_存在しない_BusinessException() {
            // Given
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.getThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_NOT_FOUND));
        }
    }

    // ========================================
    // searchThreads
    // ========================================

    @Nested
    @DisplayName("searchThreads")
    class SearchThreads {

        @Test
        @DisplayName("全文検索_正常_結果返却")
        void 全文検索_正常_結果返却() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(threadRepository.searchByKeyword("TEAM", SCOPE_ID, "テスト", PageRequest.of(0, 10)))
                    .willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            Page<ThreadResponse> result = bulletinThreadService.searchThreads(
                    SCOPE_TYPE, SCOPE_ID, "テスト", PageRequest.of(0, 10));

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // createThread
    // ========================================

    @Nested
    @DisplayName("createThread")
    class CreateThread {

        @Test
        @DisplayName("スレッド作成_正常_レスポンス返却")
        void スレッド作成_正常_レスポンス返却() {
            // Given
            CreateThreadRequest request = new CreateThreadRequest(
                    CATEGORY_ID, "新規スレッド", "新規本文", "IMPORTANT", "INDIVIDUAL", null, null);

            BulletinCategoryEntity category = BulletinCategoryEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("カテゴリ").build();
            BulletinThreadEntity savedEntity = createDefaultThread();
            ThreadResponse response = createThreadResponse();

            given(categoryService.findCategoryOrThrow(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID)).willReturn(category);
            given(threadRepository.save(any(BulletinThreadEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toThreadResponse(savedEntity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.createThread(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(threadRepository).save(any(BulletinThreadEntity.class));
        }

        @Test
        @DisplayName("スレッド作成_デフォルト優先度_INFOが設定される")
        void スレッド作成_デフォルト優先度_INFOが設定される() {
            // Given
            CreateThreadRequest request = new CreateThreadRequest(
                    CATEGORY_ID, "新規スレッド", "新規本文", null, null, null, null);

            BulletinCategoryEntity category = BulletinCategoryEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("カテゴリ").build();
            BulletinThreadEntity savedEntity = createDefaultThread();
            ThreadResponse response = createThreadResponse();

            given(categoryService.findCategoryOrThrow(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID)).willReturn(category);
            given(threadRepository.save(any(BulletinThreadEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toThreadResponse(savedEntity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.createThread(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updateThread
    // ========================================

    @Nested
    @DisplayName("updateThread")
    class UpdateThread {

        @Test
        @DisplayName("スレッド更新_正常_レスポンス返却")
        void スレッド更新_正常_レスポンス返却() {
            // Given
            UpdateThreadRequest request = new UpdateThreadRequest("更新タイトル", "更新本文", "IMPORTANT");

            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();

            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.updateThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("スレッド更新_他人の投稿_BusinessException")
        void スレッド更新_他人の投稿_BusinessException() {
            // Given
            UpdateThreadRequest request = new UpdateThreadRequest("更新タイトル", "更新本文", null);

            BulletinThreadEntity entity = createDefaultThread();

            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            Long otherUserId = 999L;

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.updateThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, otherUserId, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.NOT_AUTHOR));
        }
    }

    // ========================================
    // deleteThread
    // ========================================

    @Nested
    @DisplayName("deleteThread")
    class DeleteThread {

        @Test
        @DisplayName("スレッド削除_正常_論理削除実行")
        void スレッド削除_正常_論理削除実行() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            bulletinThreadService.deleteThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID);

            // Then
            verify(threadRepository).save(entity);
            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }

    // ========================================
    // togglePin
    // ========================================

    @Nested
    @DisplayName("togglePin")
    class TogglePin {

        @Test
        @DisplayName("ピン切替_正常_状態反転")
        void ピン切替_正常_状態反転() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            bulletinThreadService.togglePin(SCOPE_TYPE, SCOPE_ID, THREAD_ID);

            // Then
            assertThat(entity.getIsPinned()).isTrue();
        }
    }

    // ========================================
    // toggleLock
    // ========================================

    @Nested
    @DisplayName("toggleLock")
    class ToggleLock {

        @Test
        @DisplayName("ロック切替_正常_状態反転")
        void ロック切替_正常_状態反転() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            bulletinThreadService.toggleLock(SCOPE_TYPE, SCOPE_ID, THREAD_ID);

            // Then
            assertThat(entity.getIsLocked()).isTrue();
        }
    }

    // ========================================
    // archive
    // ========================================

    @Nested
    @DisplayName("archive")
    class Archive {

        @Test
        @DisplayName("アーカイブ_正常_状態変更")
        void アーカイブ_正常_状態変更() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            bulletinThreadService.archive(SCOPE_TYPE, SCOPE_ID, THREAD_ID);

            // Then
            assertThat(entity.getIsArchived()).isTrue();
        }
    }
}

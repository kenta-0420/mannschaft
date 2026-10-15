package com.mannschaft.app.queue;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.dto.CategoryResponse;
import com.mannschaft.app.queue.dto.CreateCategoryRequest;
import com.mannschaft.app.queue.dto.UpdateCategoryRequest;
import com.mannschaft.app.queue.entity.QueueCategoryEntity;
import com.mannschaft.app.queue.repository.QueueCategoryRepository;
import com.mannschaft.app.queue.service.QueueCategoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link QueueCategoryService} の単体テスト。
 * カテゴリのCRUD操作を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueCategoryService 単体テスト")
class QueueCategoryServiceTest {

    @Mock
    private QueueCategoryRepository categoryRepository;

    @Mock
    private QueueMapper queueMapper;

    @InjectMocks
    private QueueCategoryService queueCategoryService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long CATEGORY_ID = 1L;
    private static final Long SCOPE_ID = 10L;
    private static final QueueScopeType SCOPE_TYPE = QueueScopeType.TEAM;

    private QueueCategoryEntity createCategoryEntity() {
        return QueueCategoryEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("一般受付")
                .queueMode(QueueMode.INDIVIDUAL)
                .prefixChar("A")
                .maxQueueSize((short) 50)
                .displayOrder((short) 0)
                .build();
    }

    private CategoryResponse createCategoryResponse() {
        return new CategoryResponse(
                CATEGORY_ID, "TEAM", SCOPE_ID, "一般受付",
                "INDIVIDUAL", "A", (short) 50, (short) 0, LocalDateTime.now()
        );
    }

    // ========================================
    // listCategories
    // ========================================

    @Nested
    @DisplayName("listCategories")
    class ListCategories {

        @Test
        @DisplayName("カテゴリ一覧取得_正常_リスト返却")
        void カテゴリ一覧取得_正常_リスト返却() {
            // Given
            QueueCategoryEntity entity = createCategoryEntity();
            List<QueueCategoryEntity> entities = List.of(entity);
            CategoryResponse response = createCategoryResponse();

            given(categoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(entities);
            given(queueMapper.toCategoryResponseList(entities)).willReturn(List.of(response));

            // When
            List<CategoryResponse> result = queueCategoryService.listCategories(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("一般受付");
            verify(categoryRepository).findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("カテゴリ一覧取得_該当なし_空リスト返却")
        void カテゴリ一覧取得_該当なし_空リスト返却() {
            // Given
            given(categoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of());
            given(queueMapper.toCategoryResponseList(List.of())).willReturn(List.of());

            // When
            List<CategoryResponse> result = queueCategoryService.listCategories(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getCategory
    // ========================================

    @Nested
    @DisplayName("getCategory")
    class GetCategory {

        @Test
        @DisplayName("カテゴリ取得_正常_レスポンス返却")
        void カテゴリ取得_正常_レスポンス返却() {
            // Given
            QueueCategoryEntity entity = createCategoryEntity();
            CategoryResponse response = createCategoryResponse();

            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(queueMapper.toCategoryResponse(entity)).willReturn(response);

            // When
            CategoryResponse result = queueCategoryService.getCategory(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getName()).isEqualTo("一般受付");
        }

        @Test
        @DisplayName("カテゴリ取得_存在しない_例外スロー")
        void カテゴリ取得_存在しない_例外スロー() {
            // Given
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueCategoryService.getCategory(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // createCategory
    // ========================================

    @Nested
    @DisplayName("createCategory")
    class CreateCategory {

        @Test
        @DisplayName("カテゴリ作成_正常_レスポンス返却")
        void カテゴリ作成_正常_レスポンス返却() {
            // Given
            CreateCategoryRequest request = new CreateCategoryRequest("一般受付", "INDIVIDUAL", "A", (short) 50, (short) 0);
            QueueCategoryEntity savedEntity = createCategoryEntity();
            CategoryResponse response = createCategoryResponse();

            given(categoryRepository.save(any(QueueCategoryEntity.class))).willReturn(savedEntity);
            given(queueMapper.toCategoryResponse(savedEntity)).willReturn(response);

            // When
            CategoryResponse result = queueCategoryService.createCategory(request, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getName()).isEqualTo("一般受付");
            verify(categoryRepository).save(any(QueueCategoryEntity.class));
        }

        @Test
        @DisplayName("カテゴリ作成_キューモード未指定_デフォルトINDIVIDUAL")
        void カテゴリ作成_キューモード未指定_デフォルトINDIVIDUAL() {
            // Given
            CreateCategoryRequest request = new CreateCategoryRequest("一般受付", null, "A", null, null);
            QueueCategoryEntity savedEntity = createCategoryEntity();
            CategoryResponse response = createCategoryResponse();

            given(categoryRepository.save(any(QueueCategoryEntity.class))).willReturn(savedEntity);
            given(queueMapper.toCategoryResponse(savedEntity)).willReturn(response);

            // When
            CategoryResponse result = queueCategoryService.createCategory(request, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).isNotNull();
            verify(categoryRepository).save(any(QueueCategoryEntity.class));
        }
    }

    // ========================================
    // updateCategory
    // ========================================

    @Nested
    @DisplayName("updateCategory")
    class UpdateCategory {

        @Test
        @DisplayName("カテゴリ更新_正常_レスポンス返却")
        void カテゴリ更新_正常_レスポンス返却() {
            // Given
            UpdateCategoryRequest request = new UpdateCategoryRequest("VIP受付", "SHARED", "V", (short) 30, (short) 1);
            QueueCategoryEntity entity = createCategoryEntity();
            CategoryResponse response = new CategoryResponse(
                    CATEGORY_ID, "TEAM", SCOPE_ID, "VIP受付",
                    "SHARED", "V", (short) 30, (short) 1, LocalDateTime.now()
            );

            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(categoryRepository.save(any(QueueCategoryEntity.class))).willReturn(entity);
            given(queueMapper.toCategoryResponse(entity)).willReturn(response);

            // When
            CategoryResponse result = queueCategoryService.updateCategory(CATEGORY_ID, request, SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getName()).isEqualTo("VIP受付");
            verify(categoryRepository).save(any(QueueCategoryEntity.class));
        }

        @Test
        @DisplayName("カテゴリ更新_存在しない_例外スロー")
        void カテゴリ更新_存在しない_例外スロー() {
            // Given
            UpdateCategoryRequest request = new UpdateCategoryRequest("VIP受付", null, null, null, null);
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueCategoryService.updateCategory(CATEGORY_ID, request, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // deleteCategory
    // ========================================

    @Nested
    @DisplayName("deleteCategory")
    class DeleteCategory {

        @Test
        @DisplayName("カテゴリ削除_正常_論理削除実行")
        void カテゴリ削除_正常_論理削除実行() {
            // Given
            QueueCategoryEntity entity = createCategoryEntity();
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(categoryRepository.save(any(QueueCategoryEntity.class))).willReturn(entity);

            // When
            queueCategoryService.deleteCategory(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID);

            // Then
            verify(categoryRepository).save(any(QueueCategoryEntity.class));
        }

        @Test
        @DisplayName("カテゴリ削除_存在しない_例外スロー")
        void カテゴリ削除_存在しない_例外スロー() {
            // Given
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueCategoryService.deleteCategory(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // findEntityOrThrow
    // ========================================

    @Nested
    @DisplayName("findEntityOrThrow")
    class FindEntityOrThrow {

        @Test
        @DisplayName("エンティティ取得_正常_エンティティ返却")
        void エンティティ取得_正常_エンティティ返却() {
            // Given
            QueueCategoryEntity entity = createCategoryEntity();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(entity));

            // When
            QueueCategoryEntity result = queueCategoryService.findEntityOrThrow(CATEGORY_ID);

            // Then
            assertThat(result.getName()).isEqualTo("一般受付");
        }

        @Test
        @DisplayName("エンティティ取得_存在しない_例外スロー")
        void エンティティ取得_存在しない_例外スロー() {
            // Given
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> queueCategoryService.findEntityOrThrow(CATEGORY_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}

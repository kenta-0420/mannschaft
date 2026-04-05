package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.CreateCategoryRequest;
import com.mannschaft.app.bulletin.dto.UpdateCategoryRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinCategoryService} の単体テスト。
 * カテゴリのCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinCategoryService 単体テスト")
class BulletinCategoryServiceTest {

    @Mock
    private BulletinCategoryRepository categoryRepository;

    @Mock
    private BulletinMapper bulletinMapper;

    @InjectMocks
    private BulletinCategoryService bulletinCategoryService;

    private static final Long CATEGORY_ID = 5L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;

    private BulletinCategoryEntity createDefaultCategory() {
        return BulletinCategoryEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("お知らせ")
                .description("お知らせカテゴリ")
                .displayOrder(1)
                .color("#FF0000")
                .postMinRole("MEMBER_PLUS")
                .createdBy(USER_ID)
                .build();
    }

    private CategoryResponse createCategoryResponse() {
        return new CategoryResponse(CATEGORY_ID, "TEAM", SCOPE_ID, "お知らせ",
                "お知らせカテゴリ", 1, "#FF0000", "MEMBER_PLUS", USER_ID, null, null);
    }

    @Nested
    @DisplayName("listCategories")
    class ListCategories {

        @Test
        @DisplayName("カテゴリ一覧取得_正常_リスト返却")
        void カテゴリ一覧取得_正常_リスト返却() {
            // Given
            BulletinCategoryEntity entity = createDefaultCategory();
            given(categoryRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of(entity));
            given(bulletinMapper.toCategoryResponseList(List.of(entity)))
                    .willReturn(List.of(createCategoryResponse()));

            // When
            List<CategoryResponse> result = bulletinCategoryService.listCategories(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("createCategory")
    class CreateCategory {

        @Test
        @DisplayName("カテゴリ作成_正常_レスポンス返却")
        void カテゴリ作成_正常_レスポンス返却() {
            // Given
            CreateCategoryRequest request = new CreateCategoryRequest(
                    "新カテゴリ", "説明", 1, "#00FF00", null);

            BulletinCategoryEntity savedEntity = createDefaultCategory();
            CategoryResponse response = createCategoryResponse();

            given(categoryRepository.existsByScopeTypeAndScopeIdAndName(SCOPE_TYPE, SCOPE_ID, "新カテゴリ"))
                    .willReturn(false);
            given(categoryRepository.save(any(BulletinCategoryEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toCategoryResponse(savedEntity)).willReturn(response);

            // When
            CategoryResponse result = bulletinCategoryService.createCategory(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("カテゴリ作成_名前重複_BusinessException")
        void カテゴリ作成_名前重複_BusinessException() {
            // Given
            CreateCategoryRequest request = new CreateCategoryRequest(
                    "重複カテゴリ", null, null, null, null);

            given(categoryRepository.existsByScopeTypeAndScopeIdAndName(SCOPE_TYPE, SCOPE_ID, "重複カテゴリ"))
                    .willReturn(true);

            // When & Then
            assertThatThrownBy(() -> bulletinCategoryService.createCategory(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.DUPLICATE_CATEGORY_NAME));
        }
    }

    @Nested
    @DisplayName("updateCategory")
    class UpdateCategory {

        @Test
        @DisplayName("カテゴリ更新_正常_レスポンス返却")
        void カテゴリ更新_正常_レスポンス返却() {
            // Given
            UpdateCategoryRequest request = new UpdateCategoryRequest(
                    "更新カテゴリ", null, null, null, null);

            BulletinCategoryEntity entity = createDefaultCategory();
            CategoryResponse response = createCategoryResponse();

            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(categoryRepository.existsByScopeTypeAndScopeIdAndNameAndIdNot(
                    SCOPE_TYPE, SCOPE_ID, "更新カテゴリ", CATEGORY_ID)).willReturn(false);
            given(categoryRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toCategoryResponse(entity)).willReturn(response);

            // When
            CategoryResponse result = bulletinCategoryService.updateCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("カテゴリ更新_名前重複_BusinessException")
        void カテゴリ更新_名前重複_BusinessException() {
            // Given
            UpdateCategoryRequest request = new UpdateCategoryRequest(
                    "重複名", null, null, null, null);

            BulletinCategoryEntity entity = createDefaultCategory();
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(categoryRepository.existsByScopeTypeAndScopeIdAndNameAndIdNot(
                    SCOPE_TYPE, SCOPE_ID, "重複名", CATEGORY_ID)).willReturn(true);

            // When & Then
            assertThatThrownBy(() -> bulletinCategoryService.updateCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.DUPLICATE_CATEGORY_NAME));
        }
    }

    @Nested
    @DisplayName("deleteCategory")
    class DeleteCategory {

        @Test
        @DisplayName("カテゴリ削除_正常_論理削除実行")
        void カテゴリ削除_正常_論理削除実行() {
            // Given
            BulletinCategoryEntity entity = createDefaultCategory();
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            bulletinCategoryService.deleteCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID);

            // Then
            verify(categoryRepository).save(entity);
            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("カテゴリ削除_存在しない_BusinessException")
        void カテゴリ削除_存在しない_BusinessException() {
            // Given
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bulletinCategoryService.deleteCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.CATEGORY_NOT_FOUND));
        }
    }
}

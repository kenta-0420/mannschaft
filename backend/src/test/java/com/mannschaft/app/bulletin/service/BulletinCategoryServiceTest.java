package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.CreateCategoryRequest;
import com.mannschaft.app.bulletin.dto.DeleteCategoryResponse;
import com.mannschaft.app.bulletin.dto.UpdateCategoryRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
    private BulletinThreadRepository threadRepository;

    @Mock
    private BulletinMapper bulletinMapper;

    @Mock
    private BulletinAccessGuard accessGuard;

    @Mock
    private com.mannschaft.app.village.service.VillageBulletinAccessService villageBulletinAccessService;

    @InjectMocks
    private BulletinCategoryService bulletinCategoryService;

    private static final Long CATEGORY_ID = 5L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;
    private static final java.util.UUID VILLAGE_ID =
            java.util.UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private BulletinCategoryEntity createDefaultCategory() {
        return BulletinCategoryEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name("お知らせ")
                .description("お知らせカテゴリ")
                .displayOrder(1)
                .color("#FF0000")
                .postMinRole("MEMBER")
                .createdBy(USER_ID)
                .build();
    }

    private CategoryResponse createCategoryResponse() {
        return new CategoryResponse(CATEGORY_ID, "TEAM", SCOPE_ID, "お知らせ",
                "お知らせカテゴリ", 1, "#FF0000", "MEMBER", USER_ID, null, null);
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
            List<CategoryResponse> result = bulletinCategoryService.listCategories(SCOPE_TYPE, SCOPE_ID, USER_ID);

            // Then
            assertThat(result).hasSize(1);
            verify(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("カテゴリ一覧取得_非メンバー_403")
        void カテゴリ一覧取得_非メンバー_403() {
            // Given
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinCategoryService.listCategories(SCOPE_TYPE, SCOPE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
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
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("カテゴリ作成_post_min_role未指定_MEMBERが既定値")
        void カテゴリ作成_post_min_role既定値MEMBER() {
            // Given: post_min_role 未指定（MEMBER_PLUS ではなく MEMBER が入ること）
            CreateCategoryRequest request = new CreateCategoryRequest(
                    "新カテゴリ", "説明", 1, "#00FF00", null);
            BulletinCategoryEntity savedEntity = createDefaultCategory();
            given(categoryRepository.existsByScopeTypeAndScopeIdAndName(SCOPE_TYPE, SCOPE_ID, "新カテゴリ"))
                    .willReturn(false);
            given(categoryRepository.save(any(BulletinCategoryEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toCategoryResponse(savedEntity)).willReturn(createCategoryResponse());

            // When
            bulletinCategoryService.createCategory(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then: 保存される Entity の post_min_role は MEMBER
            ArgumentCaptor<BulletinCategoryEntity> captor = ArgumentCaptor.forClass(BulletinCategoryEntity.class);
            verify(categoryRepository).save(captor.capture());
            assertThat(captor.getValue().getPostMinRole()).isEqualTo("MEMBER");
        }

        @Test
        @DisplayName("カテゴリ作成_管理権限なし_403")
        void カテゴリ作成_権限なし_403() {
            // Given
            CreateCategoryRequest request = new CreateCategoryRequest(
                    "新カテゴリ", "説明", 1, "#00FF00", null);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinCategoryService.createCategory(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(categoryRepository, never()).save(any());
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
            CategoryResponse result = bulletinCategoryService.updateCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
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
            assertThatThrownBy(() -> bulletinCategoryService.updateCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID, request))
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
            given(threadRepository.bulkSetCategoryIdNullByCategoryId(CATEGORY_ID)).willReturn(0);

            // When
            bulletinCategoryService.deleteCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID);

            // Then
            verify(categoryRepository).save(entity);
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("カテゴリ削除_正常_配下スレッドを未分類化しスレッドは残す")
        void カテゴリ削除_正常_配下スレッドを未分類化しスレッドは残す() {
            // Given: 配下に 12 件のスレッドが存在する状態
            BulletinCategoryEntity entity = createDefaultCategory();
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.bulkSetCategoryIdNullByCategoryId(CATEGORY_ID)).willReturn(12);

            // When
            DeleteCategoryResponse response =
                    bulletinCategoryService.deleteCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID);

            // Then: 未分類化（category_id = NULL）が呼ばれ、カテゴリは論理削除される。スレッド削除は行わない
            verify(threadRepository).bulkSetCategoryIdNullByCategoryId(CATEGORY_ID);
            verify(categoryRepository).save(entity);
            assertThat(entity.getDeletedAt()).isNotNull();
            assertThat(response.getId()).isEqualTo(CATEGORY_ID);
            assertThat(response.getAffectedThreadCount()).isEqualTo(12);
            assertThat(response.getMessage()).contains("12件");
        }

        @Test
        @DisplayName("カテゴリ削除_存在しない場合は未分類化を呼ばない")
        void カテゴリ削除_存在しない場合は未分類化を呼ばない() {
            // Given
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then: カテゴリが見つからない場合、未分類化処理は実行されない
            assertThatThrownBy(() -> bulletinCategoryService.deleteCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
            verify(threadRepository, never()).bulkSetCategoryIdNullByCategoryId(any());
        }

        @Test
        @DisplayName("カテゴリ削除_存在しない_BusinessException")
        void カテゴリ削除_存在しない_BusinessException() {
            // Given
            given(categoryRepository.findByIdAndScopeTypeAndScopeId(CATEGORY_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bulletinCategoryService.deleteCategory(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.CATEGORY_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("listVillageCategories（村カテゴリ一覧 / F17.1 グローバル方式）")
    class ListVillageCategories {

        @Test
        @DisplayName("村カテゴリ一覧_可視性認可して村スコープのカテゴリ返却")
        void 村カテゴリ一覧_正常() {
            // Given
            BulletinCategoryEntity entity = createDefaultCategory();
            given(categoryRepository.findByScopeVillageIdOrderByDisplayOrderAsc(VILLAGE_ID))
                    .willReturn(List.of(entity));
            given(bulletinMapper.toCategoryResponseList(List.of(entity)))
                    .willReturn(List.of(createCategoryResponse()));

            // When
            List<CategoryResponse> result = bulletinCategoryService.listVillageCategories(VILLAGE_ID, USER_ID);

            // Then
            assertThat(result).hasSize(1);
            verify(villageBulletinAccessService).checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);
            verify(categoryRepository).findByScopeVillageIdOrderByDisplayOrderAsc(VILLAGE_ID);
        }

        @Test
        @DisplayName("村カテゴリ一覧_MEMBERS_ONLY非メンバー_403で弾かれクエリは走らない")
        void 村カテゴリ一覧_認可失敗_403() {
            // Given
            doThrow(new BusinessException(
                    com.mannschaft.app.village.VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN))
                    .when(villageBulletinAccessService)
                    .checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinCategoryService.listVillageCategories(VILLAGE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(com.mannschaft.app.village.VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN));
            verify(categoryRepository, never()).findByScopeVillageIdOrderByDisplayOrderAsc(any());
        }
    }
}

package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.ShoppingItemRequest;
import com.mannschaft.app.family.dto.ShoppingItemResponse;
import com.mannschaft.app.family.dto.ShoppingListRequest;
import com.mannschaft.app.family.dto.ShoppingListResponse;
import com.mannschaft.app.family.entity.ShoppingListEntity;
import com.mannschaft.app.family.entity.ShoppingListItemEntity;
import com.mannschaft.app.family.repository.ShoppingListItemRepository;
import com.mannschaft.app.family.repository.ShoppingListRepository;
import com.mannschaft.app.family.service.ShoppingListService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShoppingListService 単体テスト")
class ShoppingListServiceTest {

    @Mock private ShoppingListRepository shoppingListRepository;
    @Mock private ShoppingListItemRepository shoppingListItemRepository;
    @InjectMocks private ShoppingListService service;

    @Nested
    @DisplayName("createList")
    class CreateList {

        @Test
        @DisplayName("正常系: リストが作成される")
        void 作成_正常_保存() {
            // Given
            given(shoppingListRepository.countByTeamIdAndDeletedAtIsNull(1L)).willReturn(0L);
            ShoppingListEntity saved = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(com.mannschaft.app.family.ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.save(any(ShoppingListEntity.class))).willReturn(saved);

            // When
            ApiResponse<ShoppingListResponse> result = service.createList(1L, 100L,
                    new ShoppingListRequest("食料品", false));

            // Then
            assertThat(result.getData().getName()).isEqualTo("食料品");
        }

        @Test
        @DisplayName("異常系: リスト数上限超過でFAMILY_012例外")
        void 作成_上限超過_例外() {
            // Given
            given(shoppingListRepository.countByTeamIdAndDeletedAtIsNull(1L)).willReturn(10L);

            // When / Then
            assertThatThrownBy(() -> service.createList(1L, 100L,
                    new ShoppingListRequest("テスト", false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_012"));
        }
    }

    @Nested
    @DisplayName("addItem")
    class AddItem {

        @Test
        @DisplayName("異常系: アイテム数上限超過でFAMILY_014例外")
        void 追加_上限超過_例外() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));
            given(shoppingListItemRepository.countByListId(1L)).willReturn(100L);

            // When / Then
            assertThatThrownBy(() -> service.addItem(1L, 1L, 100L,
                    new ShoppingItemRequest("牛乳", "1本", null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_014"));
        }
    }

    @Nested
    @DisplayName("deleteList")
    class DeleteList {

        @Test
        @DisplayName("異常系: 削除権限なしでFAMILY_015例外")
        void 削除_権限なし_例外() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));

            // When / Then
            assertThatThrownBy(() -> service.deleteList(1L, 1L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_015"));
        }

        @Test
        @DisplayName("正常系: 作成者が削除できる")
        void 削除_正常_作成者() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));

            // When
            service.deleteList(1L, 1L, 100L);

            // Then - no exception
        }
    }

    @Nested
    @DisplayName("getLists")
    class GetLists {

        @Test
        @DisplayName("正常系: ステータス指定なしで全リストが返される")
        void 全リスト_返される() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByTeamIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L))
                    .willReturn(List.of(list));

            // When
            ApiResponse<List<ShoppingListResponse>> result = service.getLists(1L, null);

            // Then
            assertThat(result.getData()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ステータス指定でフィルタされたリストが返される")
        void ステータス指定リスト_返される() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByTeamIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                    eq(1L), eq(ShoppingListStatus.ACTIVE)))
                    .willReturn(List.of(list));

            // When
            ApiResponse<List<ShoppingListResponse>> result = service.getLists(1L, "active");

            // Then
            assertThat(result.getData()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("updateList")
    class UpdateList {

        @Test
        @DisplayName("正常系: リスト名が更新される")
        void 更新_正常() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));

            // When
            ApiResponse<ShoppingListResponse> result = service.updateList(1L, 1L,
                    new ShoppingListRequest("新しい名前", null));

            // Then
            assertThat(result.getData().getName()).isEqualTo("新しい名前");
        }
    }

    @Nested
    @DisplayName("archiveList")
    class ArchiveList {

        @Test
        @DisplayName("正常系: リストがアーカイブされる")
        void アーカイブ_正常() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));

            // When
            ApiResponse<ShoppingListResponse> result = service.archiveList(1L, 1L);

            // Then
            assertThat(result.getData().getStatus()).isEqualTo("ARCHIVED");
        }
    }

    @Nested
    @DisplayName("getItems")
    class GetItems {

        @Test
        @DisplayName("正常系: アイテム一覧が返される")
        void アイテム一覧_正常() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));
            given(shoppingListItemRepository.findByListIdOrderByIsCheckedAscSortOrderAsc(1L))
                    .willReturn(List.of());

            // When
            ApiResponse<List<ShoppingItemResponse>> result = service.getItems(1L, 1L);

            // Then
            assertThat(result.getData()).isEmpty();
        }
    }

    @Nested
    @DisplayName("addItem正常系")
    class AddItemSuccess {

        @Test
        @DisplayName("正常系: アイテムが追加される")
        void 追加_正常() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));
            given(shoppingListItemRepository.countByListId(1L)).willReturn(0L);
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").createdBy(100L).build();
            given(shoppingListItemRepository.save(any())).willReturn(item);

            // When
            ApiResponse<ShoppingItemResponse> result = service.addItem(1L, 1L, 100L,
                    new ShoppingItemRequest("牛乳", "1本", null, null, null));

            // Then
            assertThat(result.getData().getName()).isEqualTo("牛乳");
        }
    }

    @Nested
    @DisplayName("updateItem")
    class UpdateItem {

        @Test
        @DisplayName("正常系: アイテムが更新される")
        void 更新_正常() {
            // Given
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").createdBy(100L).build();
            given(shoppingListItemRepository.findById(1L)).willReturn(Optional.of(item));

            // When
            ApiResponse<ShoppingItemResponse> result = service.updateItem(1L, 1L, 1L,
                    new ShoppingItemRequest("豆乳", "2本", "大豆アレルギー対応", null, 1));

            // Then
            assertThat(result.getData().getName()).isEqualTo("豆乳");
        }

        @Test
        @DisplayName("異常系: アイテム不存在でFAMILY_013例外")
        void 更新_不存在_例外() {
            // Given
            given(shoppingListItemRepository.findById(99L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateItem(1L, 1L, 99L,
                    new ShoppingItemRequest("牛乳", "1本", null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_013"));
        }
    }

    @Nested
    @DisplayName("deleteItem")
    class DeleteItem {

        @Test
        @DisplayName("正常系: アイテムが削除される")
        void 削除_正常() {
            // Given
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").createdBy(100L).build();
            given(shoppingListItemRepository.findById(1L)).willReturn(Optional.of(item));

            // When
            service.deleteItem(1L, 1L, 1L);

            // Then
            verify(shoppingListItemRepository).delete(item);
        }
    }

    @Nested
    @DisplayName("toggleCheck")
    class ToggleCheck {

        @Test
        @DisplayName("正常系: チェック状態がトグルされる")
        void チェックトグル_正常() {
            // Given
            ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                    .listId(1L).name("牛乳").createdBy(100L).build();
            given(shoppingListItemRepository.findById(1L)).willReturn(Optional.of(item));

            // When
            ApiResponse<ShoppingItemResponse> result = service.toggleCheck(1L, 1L, 1L, 100L);

            // Then
            assertThat(result.getData()).isNotNull();
        }
    }

    @Nested
    @DisplayName("deleteCheckedItems")
    class DeleteCheckedItems {

        @Test
        @DisplayName("正常系: チェック済みアイテムが削除される")
        void チェック済み削除_正常() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));
            given(shoppingListItemRepository.deleteCheckedItems(1L)).willReturn(3);

            // When
            ApiResponse<Integer> result = service.deleteCheckedItems(1L, 1L);

            // Then
            assertThat(result.getData()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("uncheckAll")
    class UncheckAll {

        @Test
        @DisplayName("正常系: 全チェックが解除される")
        void 全解除_正常() {
            // Given
            ShoppingListEntity list = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(list));
            given(shoppingListItemRepository.uncheckAllItems(1L)).willReturn(5);

            // When
            ApiResponse<Integer> result = service.uncheckAll(1L, 1L);

            // Then
            assertThat(result.getData()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("copyFromTemplate")
    class CopyFromTemplate {

        @Test
        @DisplayName("異常系: テンプレートでないリストからのコピーはFAMILY_021例外")
        void コピー_テンプレートでない_例外() {
            // Given
            ShoppingListEntity nonTemplate = ShoppingListEntity.builder()
                    .teamId(1L).name("食料品").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.of(nonTemplate));

            // When / Then
            assertThatThrownBy(() -> service.copyFromTemplate(1L, 1L, 99L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_021"));
        }

        @Test
        @DisplayName("異常系: アーカイブ済みターゲットへのコピーはFAMILY_022例外")
        void コピー_アーカイブ済み_例外() {
            // Given
            ShoppingListEntity template = ShoppingListEntity.builder()
                    .teamId(1L).name("テンプレ").createdBy(100L)
                    .status(ShoppingListStatus.ACTIVE).isTemplate(true).build();
            ShoppingListEntity archived = ShoppingListEntity.builder()
                    .teamId(1L).name("アーカイブ済").createdBy(100L)
                    .status(ShoppingListStatus.ARCHIVED).build();
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(99L)).willReturn(Optional.of(template));
            given(shoppingListRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(archived));

            // When / Then
            assertThatThrownBy(() -> service.copyFromTemplate(1L, 1L, 99L, 100L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FAMILY_022"));
        }
    }
}

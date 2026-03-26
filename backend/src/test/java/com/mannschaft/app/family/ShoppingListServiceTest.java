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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    }
}

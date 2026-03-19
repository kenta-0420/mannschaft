package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.dto.ShoppingItemRequest;
import com.mannschaft.app.family.dto.ShoppingItemResponse;
import com.mannschaft.app.family.dto.ShoppingListRequest;
import com.mannschaft.app.family.dto.ShoppingListResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * お買い物リストサービス。チーム共有のお買い物リスト管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShoppingListService {

    private static final int MAX_LISTS_PER_TEAM = 10;
    private static final int MAX_ITEMS_PER_LIST = 100;

    private final ShoppingListRepository shoppingListRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;

    /**
     * チームのお買い物リスト一覧を取得する。
     *
     * @param teamId チームID
     * @param status ステータスフィルタ（任意）
     * @return リスト一覧
     */
    public ApiResponse<List<ShoppingListResponse>> getLists(Long teamId, String status) {
        List<ShoppingListEntity> lists;
        if (status != null) {
            ShoppingListStatus s = ShoppingListStatus.valueOf(status.toUpperCase());
            lists = shoppingListRepository.findByTeamIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(teamId, s);
        } else {
            lists = shoppingListRepository.findByTeamIdAndDeletedAtIsNullOrderByCreatedAtDesc(teamId);
        }
        return ApiResponse.of(lists.stream().map(this::toListResponse).toList());
    }

    /**
     * お買い物リストを作成する。
     *
     * @param teamId  チームID
     * @param userId  ユーザーID
     * @param request リクエスト
     * @return 作成されたリスト
     */
    @Transactional
    public ApiResponse<ShoppingListResponse> createList(Long teamId, Long userId, ShoppingListRequest request) {
        long count = shoppingListRepository.countByTeamIdAndDeletedAtIsNull(teamId);
        if (count >= MAX_LISTS_PER_TEAM) {
            throw new BusinessException(FamilyErrorCode.FAMILY_012);
        }

        ShoppingListEntity entity = ShoppingListEntity.builder()
                .teamId(teamId)
                .name(request.getName())
                .isTemplate(request.getIsTemplate() != null ? request.getIsTemplate() : false)
                .createdBy(userId)
                .build();

        return ApiResponse.of(toListResponse(shoppingListRepository.save(entity)));
    }

    /**
     * お買い物リスト名を変更する。
     *
     * @param teamId  チームID
     * @param listId  リストID
     * @param request リクエスト
     * @return 更新されたリスト
     */
    @Transactional
    public ApiResponse<ShoppingListResponse> updateList(Long teamId, Long listId, ShoppingListRequest request) {
        ShoppingListEntity list = findListOrThrow(listId);
        list.rename(request.getName());
        return ApiResponse.of(toListResponse(list));
    }

    /**
     * お買い物リストを削除する（論理削除）。
     *
     * @param teamId チームID
     * @param listId リストID
     * @param userId ユーザーID
     */
    @Transactional
    public void deleteList(Long teamId, Long listId, Long userId) {
        ShoppingListEntity list = findListOrThrow(listId);
        // TODO: ADMIN判定。暫定でcreatedBy一致のみ
        if (!list.getCreatedBy().equals(userId)) {
            throw new BusinessException(FamilyErrorCode.FAMILY_015);
        }
        list.softDelete();
    }

    /**
     * リストをアーカイブする。
     *
     * @param teamId チームID
     * @param listId リストID
     * @return 更新されたリスト
     */
    @Transactional
    public ApiResponse<ShoppingListResponse> archiveList(Long teamId, Long listId) {
        ShoppingListEntity list = findListOrThrow(listId);
        list.archive();
        return ApiResponse.of(toListResponse(list));
    }

    /**
     * テンプレートリストのアイテムをアクティブリストにコピーする。
     *
     * @param teamId     チームID
     * @param listId     コピー先リストID
     * @param templateId テンプレートリストID
     * @param userId     ユーザーID
     * @return コピー先のアイテム一覧
     */
    @Transactional
    public ApiResponse<List<ShoppingItemResponse>> copyFromTemplate(
            Long teamId, Long listId, Long templateId, Long userId) {
        ShoppingListEntity templateList = findListOrThrow(templateId);
        if (!Boolean.TRUE.equals(templateList.getIsTemplate())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_021);
        }

        ShoppingListEntity targetList = findListOrThrow(listId);
        if (ShoppingListStatus.ARCHIVED.equals(targetList.getStatus())) {
            throw new BusinessException(FamilyErrorCode.FAMILY_022);
        }

        List<ShoppingListItemEntity> templateItems = shoppingListItemRepository
                .findByListIdOrderBySortOrderAsc(templateId);

        for (ShoppingListItemEntity templateItem : templateItems) {
            long currentCount = shoppingListItemRepository.countByListId(listId);
            if (currentCount >= MAX_ITEMS_PER_LIST) {
                break;
            }

            ShoppingListItemEntity newItem = ShoppingListItemEntity.builder()
                    .listId(listId)
                    .name(templateItem.getName())
                    .quantity(templateItem.getQuantity())
                    .note(templateItem.getNote())
                    .sortOrder(templateItem.getSortOrder())
                    .createdBy(userId)
                    .build();
            shoppingListItemRepository.save(newItem);
        }

        return getItems(teamId, listId);
    }

    // ─── アイテム操作 ───

    /**
     * アイテム一覧を取得する。
     *
     * @param teamId チームID
     * @param listId リストID
     * @return アイテム一覧
     */
    public ApiResponse<List<ShoppingItemResponse>> getItems(Long teamId, Long listId) {
        findListOrThrow(listId);
        List<ShoppingListItemEntity> items = shoppingListItemRepository
                .findByListIdOrderByIsCheckedAscSortOrderAsc(listId);
        return ApiResponse.of(items.stream().map(this::toItemResponse).toList());
    }

    /**
     * アイテムを追加する。
     *
     * @param teamId  チームID
     * @param listId  リストID
     * @param userId  ユーザーID
     * @param request リクエスト
     * @return 追加されたアイテム
     */
    @Transactional
    public ApiResponse<ShoppingItemResponse> addItem(Long teamId, Long listId, Long userId,
                                                     ShoppingItemRequest request) {
        findListOrThrow(listId);
        long count = shoppingListItemRepository.countByListId(listId);
        if (count >= MAX_ITEMS_PER_LIST) {
            throw new BusinessException(FamilyErrorCode.FAMILY_014);
        }

        ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                .listId(listId)
                .name(request.getName())
                .quantity(request.getQuantity())
                .note(request.getNote())
                .assignedTo(request.getAssignedTo())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .createdBy(userId)
                .build();

        return ApiResponse.of(toItemResponse(shoppingListItemRepository.save(item)));
    }

    /**
     * アイテムを更新する。
     *
     * @param teamId  チームID
     * @param listId  リストID
     * @param itemId  アイテムID
     * @param request リクエスト
     * @return 更新されたアイテム
     */
    @Transactional
    public ApiResponse<ShoppingItemResponse> updateItem(Long teamId, Long listId, Long itemId,
                                                        ShoppingItemRequest request) {
        ShoppingListItemEntity item = findItemOrThrow(itemId);
        item.update(
                request.getName(),
                request.getQuantity(),
                request.getNote(),
                request.getAssignedTo(),
                request.getSortOrder() != null ? request.getSortOrder() : item.getSortOrder()
        );
        return ApiResponse.of(toItemResponse(item));
    }

    /**
     * アイテムを削除する。
     *
     * @param teamId チームID
     * @param listId リストID
     * @param itemId アイテムID
     */
    @Transactional
    public void deleteItem(Long teamId, Long listId, Long itemId) {
        ShoppingListItemEntity item = findItemOrThrow(itemId);
        shoppingListItemRepository.delete(item);
    }

    /**
     * 購入済みチェックをトグルする。
     *
     * @param teamId チームID
     * @param listId リストID
     * @param itemId アイテムID
     * @param userId ユーザーID
     * @return 更新されたアイテム
     */
    @Transactional
    public ApiResponse<ShoppingItemResponse> toggleCheck(Long teamId, Long listId, Long itemId, Long userId) {
        ShoppingListItemEntity item = findItemOrThrow(itemId);
        item.toggleCheck(userId);
        return ApiResponse.of(toItemResponse(item));
    }

    /**
     * チェック済みアイテムを一括削除する。
     *
     * @param teamId チームID
     * @param listId リストID
     * @return 削除件数
     */
    @Transactional
    public ApiResponse<Integer> deleteCheckedItems(Long teamId, Long listId) {
        findListOrThrow(listId);
        int count = shoppingListItemRepository.deleteCheckedItems(listId);
        return ApiResponse.of(count);
    }

    /**
     * 全アイテムのチェックを一括解除する。
     *
     * @param teamId チームID
     * @param listId リストID
     * @return 更新件数
     */
    @Transactional
    public ApiResponse<Integer> uncheckAll(Long teamId, Long listId) {
        findListOrThrow(listId);
        int count = shoppingListItemRepository.uncheckAllItems(listId);
        return ApiResponse.of(count);
    }

    // ─── ヘルパーメソッド ───

    private ShoppingListEntity findListOrThrow(Long listId) {
        return shoppingListRepository.findByIdAndDeletedAtIsNull(listId)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_011));
    }

    private ShoppingListItemEntity findItemOrThrow(Long itemId) {
        return shoppingListItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_013));
    }

    private ShoppingListResponse toListResponse(ShoppingListEntity entity) {
        return new ShoppingListResponse(
                entity.getId(), entity.getTeamId(), entity.getName(),
                Boolean.TRUE.equals(entity.getIsTemplate()),
                entity.getStatus().name(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }

    private ShoppingItemResponse toItemResponse(ShoppingListItemEntity entity) {
        return new ShoppingItemResponse(
                entity.getId(), entity.getListId(), entity.getName(),
                entity.getQuantity(), entity.getNote(), entity.getAssignedTo(),
                Boolean.TRUE.equals(entity.getIsChecked()), entity.getCheckedBy(),
                entity.getCheckedAt(), entity.getSortOrder(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }
}

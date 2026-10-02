package com.mannschaft.app.family.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.FamilyErrorCode;
import com.mannschaft.app.family.ShoppingListStatus;
import com.mannschaft.app.family.dto.ShoppingItemRequest;
import com.mannschaft.app.family.dto.ShoppingItemResponse;
import com.mannschaft.app.family.dto.ShoppingListRequest;
import com.mannschaft.app.family.dto.ShoppingListResponse;
import com.mannschaft.app.family.entity.ShoppingListEntity;
import com.mannschaft.app.family.entity.ShoppingListItemEntity;
import com.mannschaft.app.family.repository.ShoppingListItemRepository;
import com.mannschaft.app.family.repository.ShoppingListRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShoppingListService {

    private static final int MAX_LISTS_PER_TEAM = 10;
    private static final int MAX_ITEMS_PER_LIST = 100;
    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private final ShoppingListRepository shoppingListRepository;
    private final ShoppingListItemRepository shoppingListItemRepository;
    private final AccessControlService accessControlService;

    public ApiResponse<List<ShoppingListResponse>> getLists(Long teamId, Long actorUserId, String status) {
        // 認可根治 Wave2-2C: お買い物リストはチーム内共有データ。非メンバーの閲覧を403で拒否する
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TYPE_TEAM);
        List<ShoppingListEntity> lists;
        if (status != null) {
            ShoppingListStatus s = ShoppingListStatus.valueOf(status.toUpperCase());
            lists = shoppingListRepository.findByTeamIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(teamId, s);
        } else {
            lists = shoppingListRepository.findByTeamIdAndDeletedAtIsNullOrderByCreatedAtDesc(teamId);
        }
        return ApiResponse.of(lists.stream().map(this::toListResponse).toList());
    }

    @Transactional
    public ApiResponse<ShoppingListResponse> createList(Long teamId, Long userId, ShoppingListRequest request) {
        // 認可根治 Wave2-2C: 家族ユーティリティのため作成は全メンバー可（既存仕様）。非メンバーは403
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE_TEAM);
        long count = shoppingListRepository.countByTeamIdAndDeletedAtIsNull(teamId);
        if (count >= MAX_LISTS_PER_TEAM) { throw new BusinessException(FamilyErrorCode.FAMILY_012); }
        ShoppingListEntity entity = ShoppingListEntity.builder()
                .teamId(teamId).name(request.getName())
                .isTemplate(request.getIsTemplate() != null ? request.getIsTemplate() : false)
                .createdBy(userId).build();
        return ApiResponse.of(toListResponse(shoppingListRepository.save(entity)));
    }

    @Transactional
    public ApiResponse<ShoppingListResponse> updateList(Long teamId, Long listId, Long actorUserId,
                                                         ShoppingListRequest request) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(actorUserId, list.getTeamId(), SCOPE_TYPE_TEAM);
        list.rename(request.getName());
        return ApiResponse.of(toListResponse(list));
    }

    @Transactional
    public void deleteList(Long teamId, Long listId, Long userId) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(userId, list.getTeamId(), SCOPE_TYPE_TEAM);
        if (!list.getCreatedBy().equals(userId)) { throw new BusinessException(FamilyErrorCode.FAMILY_015); }
        list.softDelete();
    }

    @Transactional
    public ApiResponse<ShoppingListResponse> archiveList(Long teamId, Long listId, Long actorUserId) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(actorUserId, list.getTeamId(), SCOPE_TYPE_TEAM);
        list.archive();
        return ApiResponse.of(toListResponse(list));
    }

    @Transactional
    public ApiResponse<List<ShoppingItemResponse>> copyFromTemplate(Long teamId, Long listId, Long templateId,
                                                                     Long userId) {
        accessControlService.checkMembership(userId, teamId, SCOPE_TYPE_TEAM);
        ShoppingListEntity templateList = findListInTeamOrThrow(teamId, templateId);
        if (!Boolean.TRUE.equals(templateList.getIsTemplate())) { throw new BusinessException(FamilyErrorCode.FAMILY_021); }
        ShoppingListEntity targetList = findListInTeamOrThrow(teamId, listId);
        if (ShoppingListStatus.ARCHIVED.equals(targetList.getStatus())) { throw new BusinessException(FamilyErrorCode.FAMILY_022); }
        List<ShoppingListItemEntity> templateItems = shoppingListItemRepository.findByListIdOrderBySortOrderAsc(templateId);
        for (ShoppingListItemEntity templateItem : templateItems) {
            long currentCount = shoppingListItemRepository.countByListId(listId);
            if (currentCount >= MAX_ITEMS_PER_LIST) { break; }
            ShoppingListItemEntity newItem = ShoppingListItemEntity.builder()
                    .listId(listId).name(templateItem.getName()).quantity(templateItem.getQuantity())
                    .note(templateItem.getNote()).sortOrder(templateItem.getSortOrder()).createdBy(userId).build();
            shoppingListItemRepository.save(newItem);
        }
        return getItems(teamId, listId, userId);
    }

    public ApiResponse<List<ShoppingItemResponse>> getItems(Long teamId, Long listId, Long actorUserId) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(actorUserId, list.getTeamId(), SCOPE_TYPE_TEAM);
        List<ShoppingListItemEntity> items = shoppingListItemRepository.findByListIdOrderByIsCheckedAscSortOrderAsc(listId);
        return ApiResponse.of(items.stream().map(this::toItemResponse).toList());
    }

    @Transactional
    public ApiResponse<ShoppingItemResponse> addItem(Long teamId, Long listId, Long userId, ShoppingItemRequest request) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(userId, list.getTeamId(), SCOPE_TYPE_TEAM);
        long count = shoppingListItemRepository.countByListId(listId);
        if (count >= MAX_ITEMS_PER_LIST) { throw new BusinessException(FamilyErrorCode.FAMILY_014); }
        ShoppingListItemEntity item = ShoppingListItemEntity.builder()
                .listId(listId).name(request.getName()).quantity(request.getQuantity())
                .note(request.getNote()).assignedTo(request.getAssignedTo())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0).createdBy(userId).build();
        return ApiResponse.of(toItemResponse(shoppingListItemRepository.save(item)));
    }

    @Transactional
    public ApiResponse<ShoppingItemResponse> updateItem(Long teamId, Long listId, Long itemId, Long actorUserId,
                                                         ShoppingItemRequest request) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(actorUserId, list.getTeamId(), SCOPE_TYPE_TEAM);
        ShoppingListItemEntity item = findItemInListOrThrow(listId, itemId);
        item.update(request.getName(), request.getQuantity(), request.getNote(), request.getAssignedTo(),
                request.getSortOrder() != null ? request.getSortOrder() : item.getSortOrder());
        return ApiResponse.of(toItemResponse(item));
    }

    @Transactional
    public void deleteItem(Long teamId, Long listId, Long itemId, Long actorUserId) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(actorUserId, list.getTeamId(), SCOPE_TYPE_TEAM);
        ShoppingListItemEntity item = findItemInListOrThrow(listId, itemId);
        shoppingListItemRepository.delete(item);
    }

    @Transactional
    public ApiResponse<ShoppingItemResponse> toggleCheck(Long teamId, Long listId, Long itemId, Long userId) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(userId, list.getTeamId(), SCOPE_TYPE_TEAM);
        ShoppingListItemEntity item = findItemInListOrThrow(listId, itemId);
        item.toggleCheck(userId);
        return ApiResponse.of(toItemResponse(item));
    }

    @Transactional
    public ApiResponse<Integer> deleteCheckedItems(Long teamId, Long listId, Long actorUserId) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(actorUserId, list.getTeamId(), SCOPE_TYPE_TEAM);
        return ApiResponse.of(shoppingListItemRepository.deleteCheckedItems(listId));
    }

    @Transactional
    public ApiResponse<Integer> uncheckAll(Long teamId, Long listId, Long actorUserId) {
        ShoppingListEntity list = findListInTeamOrThrow(teamId, listId);
        accessControlService.checkMembership(actorUserId, list.getTeamId(), SCOPE_TYPE_TEAM);
        return ApiResponse.of(shoppingListItemRepository.uncheckAllItems(listId));
    }

    /**
     * お買い物リストを取得し、entity 由来の teamId とパス teamId の一致を検証する。
     * 不一致（他チームのリスト ID 指定 = BOLA）は存在秘匿のため FAMILY_011（404）を返す。
     */
    private ShoppingListEntity findListInTeamOrThrow(Long teamId, Long listId) {
        ShoppingListEntity list = shoppingListRepository.findByIdAndDeletedAtIsNull(listId)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_011));
        if (!list.getTeamId().equals(teamId)) {
            throw new BusinessException(FamilyErrorCode.FAMILY_011);
        }
        return list;
    }

    /**
     * アイテムを取得し、entity 由来の listId とパス listId の一致を検証する。
     * 不一致（別リストの itemId 指定 = BOLA）は存在秘匿のため FAMILY_013（404）を返す。
     */
    private ShoppingListItemEntity findItemInListOrThrow(Long listId, Long itemId) {
        ShoppingListItemEntity item = shoppingListItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(FamilyErrorCode.FAMILY_013));
        if (!item.getListId().equals(listId)) {
            throw new BusinessException(FamilyErrorCode.FAMILY_013);
        }
        return item;
    }

    private ShoppingListResponse toListResponse(ShoppingListEntity entity) {
        return new ShoppingListResponse(entity.getId(), entity.getTeamId(), entity.getName(),
                Boolean.TRUE.equals(entity.getIsTemplate()), entity.getStatus().name(),
                entity.getCreatedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private ShoppingItemResponse toItemResponse(ShoppingListItemEntity entity) {
        return new ShoppingItemResponse(entity.getId(), entity.getListId(), entity.getName(),
                entity.getQuantity(), entity.getNote(), entity.getAssignedTo(),
                Boolean.TRUE.equals(entity.getIsChecked()), entity.getCheckedBy(),
                entity.getCheckedAt(), entity.getSortOrder(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}

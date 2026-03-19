package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.dto.AssignFolderItemRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignFolderItemsRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignResultResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderResponse;
import com.mannschaft.app.dashboard.dto.CreateChatFolderRequest;
import com.mannschaft.app.dashboard.dto.UpdateChatFolderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * チャット・連絡先カスタムフォルダの管理サービス。
 * フォルダのCRUD、アイテム割り当て/解除、一括割り当てを担当する。
 * 1ユーザーあたり最大20フォルダ、フォルダ名はユーザー内一意。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ChatFolderService {

    private final ChatContactFolderRepository folderRepository;
    private final ChatContactFolderItemRepository folderItemRepository;
    private final DashboardMapper dashboardMapper;

    /**
     * カスタムフォルダ一覧を取得する。
     */
    public List<ChatFolderResponse> getFolders(Long userId) {
        List<ChatContactFolderEntity> folders = folderRepository.findByUserIdOrderBySortOrder(userId);
        return folders.stream()
                .map(folder -> {
                    List<ChatContactFolderItemEntity> items = folderItemRepository.findByFolderId(folder.getId());
                    return dashboardMapper.toFolderResponse(folder, items);
                })
                .toList();
    }

    /**
     * カスタムフォルダを作成する。
     *
     * @throws BusinessException フォルダ数上限到達 / 同名フォルダ重複時
     */
    @Transactional
    public ChatFolderResponse createFolder(Long userId, CreateChatFolderRequest request) {
        // フォルダ数上限チェック
        long folderCount = folderRepository.countByUserId(userId);
        if (folderCount >= ChatContactFolderEntity.getMaxFoldersPerUser()) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_009);
        }

        // 同名チェック
        if (folderRepository.existsByUserIdAndName(userId, request.getName())) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_008);
        }

        ChatContactFolderEntity folder = ChatContactFolderEntity.builder()
                .userId(userId)
                .name(request.getName())
                .icon(request.getIcon())
                .color(request.getColor())
                .sortOrder((int) folderCount)
                .build();

        folder = folderRepository.save(folder);
        log.info("チャットフォルダ作成 userId={}, folderId={}, name={}", userId, folder.getId(), folder.getName());

        return dashboardMapper.toFolderResponse(folder, List.of());
    }

    /**
     * カスタムフォルダを更新する。
     *
     * @throws BusinessException フォルダ不存在 / 所有者不一致 / 同名重複時
     */
    @Transactional
    public ChatFolderResponse updateFolder(Long userId, Long folderId, UpdateChatFolderRequest request) {
        ChatContactFolderEntity folder = findOwnedFolder(userId, folderId);

        // 同名チェック（自身を除外）
        if (folderRepository.existsByUserIdAndNameAndIdNot(userId, request.getName(), folderId)) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_008);
        }

        folder.update(request.getName(), request.getIcon(), request.getColor(), request.getSortOrder());
        log.info("チャットフォルダ更新 userId={}, folderId={}", userId, folderId);

        List<ChatContactFolderItemEntity> items = folderItemRepository.findByFolderId(folderId);
        return dashboardMapper.toFolderResponse(folder, items);
    }

    /**
     * カスタムフォルダを削除する。配下のアイテムはCASCADE削除される。
     *
     * @throws BusinessException フォルダ不存在 / 所有者不一致時
     */
    @Transactional
    public void deleteFolder(Long userId, Long folderId) {
        ChatContactFolderEntity folder = findOwnedFolder(userId, folderId);
        folderRepository.delete(folder);
        log.info("チャットフォルダ削除 userId={}, folderId={}", userId, folderId);
    }

    /**
     * フォルダにアイテムを割り当てる。既に別フォルダに属している場合は移動。
     *
     * @throws BusinessException フォルダ不存在 / 所有者不一致 / 無効なアイテム種別時
     */
    @Transactional
    public ChatFolderResponse assignItem(Long userId, Long folderId, AssignFolderItemRequest request) {
        ChatContactFolderEntity folder = findOwnedFolder(userId, folderId);
        FolderItemType itemType = parseFolderItemType(request.getItemType());

        // 既存のアイテム割り当てを削除（1アイテム1フォルダの制約）
        folderItemRepository.findByItemTypeAndItemId(itemType, request.getItemId())
                .ifPresent(existing -> folderItemRepository.deleteByItemTypeAndItemId(itemType, request.getItemId()));

        ChatContactFolderItemEntity item = ChatContactFolderItemEntity.builder()
                .folderId(folderId)
                .itemType(itemType)
                .itemId(request.getItemId())
                .build();
        folderItemRepository.save(item);

        log.info("フォルダアイテム割り当て userId={}, folderId={}, itemType={}, itemId={}",
                userId, folderId, itemType, request.getItemId());

        List<ChatContactFolderItemEntity> items = folderItemRepository.findByFolderId(folderId);
        return dashboardMapper.toFolderResponse(folder, items);
    }

    /**
     * フォルダからアイテムを外す（未分類に戻す）。
     */
    @Transactional
    public void removeItem(Long userId, String itemTypeStr, Long itemId) {
        FolderItemType itemType = parseFolderItemType(itemTypeStr);

        // アイテムの所有者検証: アイテムが属するフォルダの所有者がリクエストユーザーであること
        folderItemRepository.findByItemTypeAndItemId(itemType, itemId)
                .ifPresent(item -> {
                    findOwnedFolder(userId, item.getFolderId());
                    folderItemRepository.deleteByItemTypeAndItemId(itemType, itemId);
                    log.info("フォルダアイテム解除 userId={}, itemType={}, itemId={}", userId, itemType, itemId);
                });
    }

    /**
     * フォルダにアイテムを一括割り当てする。不正なアイテムはスキップ。
     *
     * @throws BusinessException フォルダ不存在 / 所有者不一致時
     */
    @Transactional
    public BulkAssignResultResponse bulkAssignItems(Long userId, Long folderId, BulkAssignFolderItemsRequest request) {
        ChatContactFolderEntity folder = findOwnedFolder(userId, folderId);

        int assignedCount = 0;
        int skippedCount = 0;

        for (AssignFolderItemRequest itemRequest : request.getItems()) {
            try {
                FolderItemType itemType = parseFolderItemType(itemRequest.getItemType());

                // 既存割り当ての削除
                folderItemRepository.findByItemTypeAndItemId(itemType, itemRequest.getItemId())
                        .ifPresent(existing -> folderItemRepository.deleteByItemTypeAndItemId(itemType, itemRequest.getItemId()));

                ChatContactFolderItemEntity item = ChatContactFolderItemEntity.builder()
                        .folderId(folderId)
                        .itemType(itemType)
                        .itemId(itemRequest.getItemId())
                        .build();
                folderItemRepository.save(item);
                assignedCount++;
            } catch (BusinessException e) {
                skippedCount++;
                log.debug("一括割り当てスキップ userId={}, folderId={}, itemType={}, itemId={}, reason={}",
                        userId, folderId, itemRequest.getItemType(), itemRequest.getItemId(), e.getMessage());
            }
        }

        log.info("フォルダアイテム一括割り当て userId={}, folderId={}, assigned={}, skipped={}",
                userId, folderId, assignedCount, skippedCount);

        return new BulkAssignResultResponse(assignedCount, skippedCount);
    }

    /**
     * ユーザー所有のフォルダを取得する。不存在/所有者不一致時は例外。
     */
    private ChatContactFolderEntity findOwnedFolder(Long userId, Long folderId) {
        return folderRepository.findByIdAndUserId(folderId, userId)
                .orElseThrow(() -> {
                    if (folderRepository.existsById(folderId)) {
                        return new BusinessException(DashboardErrorCode.DASHBOARD_007);
                    }
                    return new BusinessException(DashboardErrorCode.DASHBOARD_006);
                });
    }

    /**
     * アイテム種別文字列をEnumにパースする。
     */
    private FolderItemType parseFolderItemType(String itemTypeStr) {
        try {
            return FolderItemType.valueOf(itemTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_010);
        }
    }
}

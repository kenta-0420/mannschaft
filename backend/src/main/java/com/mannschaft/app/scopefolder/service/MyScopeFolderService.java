package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.AddFolderItemRequest;
import com.mannschaft.app.scopefolder.dto.CreateFolderRequest;
import com.mannschaft.app.scopefolder.dto.ReorderFoldersRequest;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.dto.UpdateFolderRequest;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * マイスコープフォルダサービス。
 * チームまたは組織のカスタムフォルダのCRUD・アイテム管理を提供する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyScopeFolderService {

    private static final int MAX_FOLDERS_PER_USER = 20;

    private final MyScopeFolderRepository folderRepository;
    private final MyScopeFolderItemRepository itemRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * フォルダ一覧を取得する（アイテムID込み）。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ（TEAM / ORGANIZATION）
     * @return フォルダレスポンスのリスト
     */
    public List<ScopeFolderResponse> getFolders(Long userId, ScopeType scopeType) {
        List<MyScopeFolderEntity> folders =
                folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(userId, scopeType);

        if (folders.isEmpty()) {
            return List.of();
        }

        List<Long> folderIds = folders.stream().map(MyScopeFolderEntity::getId).toList();
        List<MyScopeFolderItemEntity> allItems = itemRepository.findByFolderIdIn(folderIds);

        // フォルダIDでグループ化
        Map<Long, List<Long>> itemsByFolderId = allItems.stream()
                .collect(Collectors.groupingBy(
                        MyScopeFolderItemEntity::getFolderId,
                        Collectors.mapping(MyScopeFolderItemEntity::getScopeId, Collectors.toList())
                ));

        return folders.stream()
                .map(folder -> toResponse(folder, itemsByFolderId.getOrDefault(folder.getId(), List.of())))
                .toList();
    }

    /**
     * フォルダを作成する（上限・同名チェック付き）。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ
     * @param req       作成リクエスト
     * @return 作成されたフォルダのレスポンス
     */
    @Transactional
    public ScopeFolderResponse createFolder(Long userId, ScopeType scopeType, CreateFolderRequest req) {
        // フォルダ数上限チェック
        long count = folderRepository.countByUserIdAndScopeTypeAndDeletedAtIsNull(userId, scopeType);
        if (count >= MAX_FOLDERS_PER_USER) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_LIMIT_EXCEEDED);
        }

        // 同名チェック
        if (folderRepository.existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull(userId, scopeType, req.name())) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NAME_DUPLICATE);
        }

        MyScopeFolderEntity folder = MyScopeFolderEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .name(req.name())
                .color(req.color())
                .sortOrder((int) count) // 末尾に追加
                .build();

        MyScopeFolderEntity saved = folderRepository.save(folder);
        return toResponse(saved, List.of());
    }

    /**
     * フォルダを更新する。
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     * @param req      更新リクエスト
     * @return 更新後のフォルダレスポンス
     */
    @Transactional
    public ScopeFolderResponse updateFolder(Long userId, Long folderId, UpdateFolderRequest req) {
        MyScopeFolderEntity folder = findOwnedFolder(userId, folderId);

        // 同名チェック（自分自身を除く）
        if (folderRepository.existsByUserIdAndScopeTypeAndNameAndIdNotAndDeletedAtIsNull(
                userId, folder.getScopeType(), req.name(), folderId)) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NAME_DUPLICATE);
        }

        folder.update(req.name(), req.color());
        MyScopeFolderEntity saved = folderRepository.save(folder);

        List<Long> itemScopeIds = itemRepository.findByFolderIdOrderBySortOrder(folderId).stream()
                .map(MyScopeFolderItemEntity::getScopeId)
                .toList();

        return toResponse(saved, itemScopeIds);
    }

    /**
     * フォルダを削除する（ソフト削除。アイテムはDB CASCADEでハード削除）。
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     */
    @Transactional
    public void deleteFolder(Long userId, Long folderId) {
        MyScopeFolderEntity folder = findOwnedFolder(userId, folderId);
        folder.softDelete();
        folderRepository.save(folder);
    }

    /**
     * フォルダにアイテムを追加する（1アイテム1フォルダ制約: 既存フォルダから移動してから追加）。
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     * @param req      アイテム追加リクエスト
     * @return 更新後のフォルダレスポンス
     */
    @Transactional
    public ScopeFolderResponse addItem(Long userId, Long folderId, AddFolderItemRequest req) {
        MyScopeFolderEntity folder = findOwnedFolder(userId, folderId);

        // スコープへの所属確認（IDOR・不正追加防止）
        boolean isMember = switch (folder.getScopeType()) {
            case TEAM -> userRoleRepository.existsByUserIdAndTeamId(userId, req.scopeId());
            case ORGANIZATION -> userRoleRepository.existsByUserIdAndOrganizationId(userId, req.scopeId());
        };
        if (!isMember) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_MEMBER);
        }

        // 既存フォルダから削除（1アイテム1フォルダ制約）
        itemRepository.findByUserAndScopeTypeAndScopeId(userId, folder.getScopeType(), req.scopeId())
                .ifPresent(existingItem -> itemRepository.delete(existingItem));

        // 現在のアイテム数を取得してsortOrderを決定
        int currentItemCount = itemRepository.findByFolderIdOrderBySortOrder(folderId).size();

        MyScopeFolderItemEntity newItem = MyScopeFolderItemEntity.builder()
                .folderId(folderId)
                .scopeId(req.scopeId())
                .sortOrder(currentItemCount)
                .build();
        itemRepository.save(newItem);

        List<Long> itemScopeIds = itemRepository.findByFolderIdOrderBySortOrder(folderId).stream()
                .map(MyScopeFolderItemEntity::getScopeId)
                .toList();

        return toResponse(folder, itemScopeIds);
    }

    /**
     * フォルダからアイテムを削除する。
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     * @param scopeId  削除するスコープID
     */
    @Transactional
    public void removeItem(Long userId, Long folderId, Long scopeId) {
        // フォルダの所有者チェック
        findOwnedFolder(userId, folderId);

        itemRepository.findByFolderIdAndScopeId(folderId, scopeId)
                .ifPresent(item -> itemRepository.delete(item));
    }

    /**
     * フォルダの並び順を変更する。
     * orderedIdsに含まれないフォルダIDは無視して残す。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ
     * @param req       並び替えリクエスト
     */
    @Transactional
    public void reorderFolders(Long userId, ScopeType scopeType, ReorderFoldersRequest req) {
        List<MyScopeFolderEntity> folders =
                folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(userId, scopeType);

        // 対象フォルダをIDでマップ化
        Map<Long, MyScopeFolderEntity> folderMap = folders.stream()
                .collect(Collectors.toMap(MyScopeFolderEntity::getId, f -> f));

        // orderedIdsの順序でsortOrderを更新（リストに含まれないフォルダは対象外）
        IntStream.range(0, req.orderedIds().size()).forEach(i -> {
            Long id = req.orderedIds().get(i);
            MyScopeFolderEntity folder = folderMap.get(id);
            if (folder != null) {
                folder.updateSortOrder(i);
                folderRepository.save(folder);
            }
        });
    }

    /**
     * IDOR防止: フォルダの所有者チェック。
     * 自分のフォルダでなければBusinessExceptionを投げる。
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     * @return フォルダエンティティ
     */
    private MyScopeFolderEntity findOwnedFolder(Long userId, Long folderId) {
        return folderRepository.findByIdAndUserIdAndDeletedAtIsNull(folderId, userId)
                .orElseThrow(() -> new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND));
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private ScopeFolderResponse toResponse(MyScopeFolderEntity folder, List<Long> itemScopeIds) {
        return new ScopeFolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getColor(),
                folder.getSortOrder(),
                itemScopeIds
        );
    }
}

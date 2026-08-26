package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.FolderNotificationSummaryDto;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * マイスコープフォルダ読み取り専用クエリサービス。
 *
 * <p>F15.3 §6.2 / §6.4: 通知集計など N+1 を避ける集計ロジックと、
 * クロスドメイン読み取り（notifications テーブル参照）をここに閉じる。</p>
 *
 * <p>{@code @Transactional(readOnly = true)} で読み取り専用に統一。
 * 通知ドメイン → 本ドメインへの依存は片方向（読み取り）でのみ許可。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MyScopeFolderQueryService {

    private final MyScopeFolderRepository folderRepository;
    private final MyScopeFolderItemRepository itemRepository;

    /**
     * フォルダ別未読通知件数集計（タブバッジ用）。
     *
     * <p>単一クエリで集計するため N+1 が発生しない（設計書 §6.4）。
     * 未読 0 件のフォルダも結果に含む（フロントの「すべて」タブ件数集計に必要）。</p>
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ
     * @return フォルダ別未読件数のリスト
     */
    public List<FolderNotificationSummaryDto> getNotificationSummary(Long userId, ScopeType scopeType) {
        List<Object[]> rows = itemRepository.aggregateFolderUnreadCounts(userId, scopeType.name());
        return rows.stream()
                .map(row -> new FolderNotificationSummaryDto(
                        ((Number) row[0]).longValue(),
                        ((Number) row[1]).longValue()
                ))
                .toList();
    }

    /**
     * フォルダ一覧 + フォルダ別未読件数を一括取得する。
     *
     * <p>設計書 §5.1.2 `/me/scope-folders` の `notificationUnreadCount` を含めた
     * レスポンスを返す。クエリは 3 本: folders / items / aggregate。</p>
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ
     * @return 未読件数を含むフォルダ一覧
     */
    public List<ScopeFolderResponse> getFoldersWithUnread(Long userId, ScopeType scopeType) {
        List<MyScopeFolderEntity> folders =
                folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(userId, scopeType);
        if (folders.isEmpty()) {
            return List.of();
        }

        List<Long> folderIds = folders.stream().map(MyScopeFolderEntity::getId).toList();
        List<MyScopeFolderItemEntity> allItems = itemRepository.findByFolderIdIn(folderIds);

        Map<Long, List<Long>> itemsByFolderId = allItems.stream()
                .collect(Collectors.groupingBy(
                        MyScopeFolderItemEntity::getFolderId,
                        Collectors.mapping(MyScopeFolderItemEntity::getScopeId, Collectors.toList())
                ));

        // 未読件数集計
        Map<Long, Long> unreadByFolderId = new HashMap<>();
        for (FolderNotificationSummaryDto summary : getNotificationSummary(userId, scopeType)) {
            unreadByFolderId.put(summary.folderId(), summary.unreadCount());
        }

        return folders.stream()
                .map(folder -> new ScopeFolderResponse(
                        folder.getId(),
                        folder.getName(),
                        folder.getColor(),
                        folder.getIcon(),
                        folder.getSortOrder(),
                        folder.isDefaultFolder(),
                        itemsByFolderId.getOrDefault(folder.getId(), List.of()),
                        unreadByFolderId.getOrDefault(folder.getId(), 0L)
                ))
                .toList();
    }

    /**
     * 指定フォルダ内の scopeId 集合を取得する（通知フィルタ用）。
     *
     * <p>{@code /api/v1/notifications?folderId=} エンドポイントが、
     * folderId に対応する scopeId リストを取得して通知を絞り込む際に使用する
     * （設計書 §5.2.4）。</p>
     *
     * <p>IDOR 防止: folderId が他人所有なら {@code SCOPE_FOLDER_NOT_FOUND}。</p>
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     * @return フォルダ内 scopeId のリスト
     */
    public List<Long> getScopeIdsInFolder(Long userId, Long folderId) {
        MyScopeFolderEntity folder = folderRepository.findByIdAndUserIdAndDeletedAtIsNull(folderId, userId)
                .orElseThrow(() -> new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND));
        return itemRepository.findByFolderIdOrderBySortOrder(folder.getId()).stream()
                .map(MyScopeFolderItemEntity::getScopeId)
                .toList();
    }
}

package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.AddFolderItemRequest;
import com.mannschaft.app.scopefolder.dto.BulkAssignRequest;
import com.mannschaft.app.scopefolder.dto.BulkAssignResponse;
import com.mannschaft.app.scopefolder.dto.CreateFolderRequest;
import com.mannschaft.app.scopefolder.dto.ReorderFoldersRequest;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.dto.UpdateFolderRequest;
import com.mannschaft.app.scopefolder.entity.AssignedVia;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * マイスコープフォルダサービス。
 * チームまたは組織のカスタムフォルダのCRUD・アイテム管理を提供する。
 *
 * <p>F15.3 で追加された主要メソッド:</p>
 * <ul>
 *   <li>{@link #findOrCreateDefault(Long, ScopeType)} — 未分類フォルダ取得（lazy 生成）</li>
 *   <li>{@link #bulkAssign(Long, BulkAssignRequest)} — 一括振り分け</li>
 *   <li>{@link #addItemWithAssignedVia(Long, Long, Long, AssignedVia)} —
 *       監査区分付きアイテム追加（招待画面 / 一括振り分けから使用）</li>
 * </ul>
 *
 * <p>F15.3 サポータ対応: 所属確認は F00.5 の
 * {@link MembershipRepository#existsActiveByUserAndScope} を使用する
 * （設計書 §6.3 第一手段）。これにより MEMBER / SUPPORTER / ADMIN の全 RoleKind が
 * アクティブメンバーシップを持つ限り所属とみなされる。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MyScopeFolderService {

    private static final int MAX_FOLDERS_PER_USER = 20;

    /** 未分類フォルダの sort_order 固定値（末尾固定）。 */
    private static final int DEFAULT_FOLDER_SORT_ORDER = 9999;

    /** 未分類フォルダ名（i18n キーは frontend が解決。バックエンド側は表示用 fallback 文字列）。 */
    private static final String DEFAULT_FOLDER_NAME = "未分類";

    private final MyScopeFolderRepository folderRepository;
    private final MyScopeFolderItemRepository itemRepository;
    private final MembershipRepository membershipRepository;

    /**
     * フォルダ一覧を取得する（アイテムID込み・notificationUnreadCount は 0 で返す）。
     *
     * <p>未読件数を含めたい場合は {@link MyScopeFolderQueryService#getFoldersWithUnread}
     * を使用する（F15.3 §5.1.2）。</p>
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
                .map(folder -> toResponse(folder, itemsByFolderId.getOrDefault(folder.getId(), List.of()), 0L))
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
                .icon(req.icon())
                .isDefault(Boolean.FALSE)
                .sortOrder((int) count) // 末尾に追加
                .build();

        MyScopeFolderEntity saved = folderRepository.save(folder);
        return toResponse(saved, List.of(), 0L);
    }

    /**
     * フォルダを更新する。
     *
     * <p>F15.3: 未分類フォルダ（is_default=TRUE）の改名・色変更を拒否する
     * （設計書 §5.3 / §10）。</p>
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     * @param req      更新リクエスト
     * @return 更新後のフォルダレスポンス
     */
    @Transactional
    public ScopeFolderResponse updateFolder(Long userId, Long folderId, UpdateFolderRequest req) {
        MyScopeFolderEntity folder = findOwnedFolder(userId, folderId);

        // F15.3: 未分類フォルダは改名不可
        if (folder.isDefaultFolder()) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_DEFAULT_IMMUTABLE);
        }

        // 同名チェック（自分自身を除く）
        if (folderRepository.existsByUserIdAndScopeTypeAndNameAndIdNotAndDeletedAtIsNull(
                userId, folder.getScopeType(), req.name(), folderId)) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NAME_DUPLICATE);
        }

        folder.update(req.name(), req.color(), req.icon());
        MyScopeFolderEntity saved = folderRepository.save(folder);

        List<Long> itemScopeIds = itemRepository.findByFolderIdOrderBySortOrder(folderId).stream()
                .map(MyScopeFolderItemEntity::getScopeId)
                .toList();

        return toResponse(saved, itemScopeIds, 0L);
    }

    /**
     * フォルダを削除する（ソフト削除）。
     *
     * <p>F15.3 §13⑩: 削除されるフォルダ内アイテムは CASCADE で消えるが、
     * メンバーシップ自体は維持される。フォルダ内に居たチーム/組織は
     * 次の {@link #getFolders} 呼び出し時にフロント側で「未分類」へ自動再配置される。
     * バックエンドでは {@code findOrCreateDefault} に対する自動再配置補助として
     * 削除直前にアイテムを未分類フォルダへ移動させる。</p>
     *
     * <p>未分類フォルダ（is_default=TRUE）の削除は拒否する（設計書 §5.3 / §10）。</p>
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     */
    @Transactional
    public void deleteFolder(Long userId, Long folderId) {
        MyScopeFolderEntity folder = findOwnedFolder(userId, folderId);

        // F15.3: 未分類フォルダは削除不可
        if (folder.isDefaultFolder()) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_DEFAULT_IMMUTABLE);
        }

        // 削除前にアイテムを未分類フォルダへ自動再配置（設計書 §13⑩）
        List<MyScopeFolderItemEntity> items = itemRepository.findByFolderIdOrderBySortOrder(folderId);
        if (!items.isEmpty()) {
            MyScopeFolderEntity defaultFolder = findOrCreateDefaultInternal(userId, folder.getScopeType());
            int baseSortOrder = itemRepository.findByFolderIdOrderBySortOrder(defaultFolder.getId()).size();
            for (int i = 0; i < items.size(); i++) {
                MyScopeFolderItemEntity moved = MyScopeFolderItemEntity.builder()
                        .folderId(defaultFolder.getId())
                        .scopeId(items.get(i).getScopeId())
                        .sortOrder(baseSortOrder + i)
                        .assignedVia(AssignedVia.DEFAULT)
                        .build();
                itemRepository.save(moved);
            }
        }

        folder.softDelete();
        folderRepository.save(folder);
    }

    /**
     * フォルダにアイテムを追加する（1アイテム1フォルダ制約: 既存フォルダから移動してから追加）。
     *
     * <p>所属確認は F00.5 メンバーシップ基盤を使用（サポータ含む）。</p>
     *
     * @param userId   ユーザーID
     * @param folderId フォルダID
     * @param req      アイテム追加リクエスト
     * @return 更新後のフォルダレスポンス
     */
    @Transactional
    public ScopeFolderResponse addItem(Long userId, Long folderId, AddFolderItemRequest req) {
        return addItemWithAssignedVia(userId, folderId, req.scopeId(), AssignedVia.MANUAL);
    }

    /**
     * 監査区分を明示してフォルダにアイテムを追加する（F15.3 §6.2）。
     *
     * @param userId      ユーザーID
     * @param folderId    フォルダID
     * @param scopeId     スコープID
     * @param assignedVia 割当経路（INVITE / MANUAL / MIGRATION / DEFAULT）
     * @return 更新後のフォルダレスポンス
     */
    @Transactional
    public ScopeFolderResponse addItemWithAssignedVia(
            Long userId, Long folderId, Long scopeId, AssignedVia assignedVia) {
        MyScopeFolderEntity folder = findOwnedFolder(userId, folderId);

        // F00.5 メンバーシップ基盤による所属確認（サポータ含む。設計書 §6.3 第一手段）
        com.mannschaft.app.membership.domain.ScopeType membershipScope =
                toMembershipScopeType(folder.getScopeType());
        boolean isMember = membershipRepository.existsActiveByUserAndScope(
                userId, membershipScope, scopeId);
        if (!isMember) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_MEMBER);
        }

        // 既存フォルダから削除（1アイテム1フォルダ制約）
        itemRepository.findByUserAndScopeTypeAndScopeId(userId, folder.getScopeType(), scopeId)
                .ifPresent(existingItem -> itemRepository.delete(existingItem));

        // 現在のアイテム数を取得して sortOrder を決定
        int currentItemCount = itemRepository.findByFolderIdOrderBySortOrder(folderId).size();

        MyScopeFolderItemEntity newItem = MyScopeFolderItemEntity.builder()
                .folderId(folderId)
                .scopeId(scopeId)
                .sortOrder(currentItemCount)
                .assignedVia(assignedVia)
                .build();
        itemRepository.save(newItem);

        List<Long> itemScopeIds = itemRepository.findByFolderIdOrderBySortOrder(folderId).stream()
                .map(MyScopeFolderItemEntity::getScopeId)
                .toList();

        return toResponse(folder, itemScopeIds, 0L);
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
            // F15.3: 未分類フォルダは末尾固定で並び替え対象外
            if (folder != null && !folder.isDefaultFolder()) {
                folder.updateSortOrder(i);
                folderRepository.save(folder);
            }
        });
    }

    // ============================================================
    // F15.3 追加メソッド
    // ============================================================

    /**
     * 未分類フォルダを取得する（無ければ lazy 生成）。
     *
     * <p>設計書 §5.2.1 / §6.2</p>
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ
     * @return 未分類フォルダレスポンス
     */
    @Transactional
    public ScopeFolderResponse findOrCreateDefault(Long userId, ScopeType scopeType) {
        MyScopeFolderEntity folder = findOrCreateDefaultInternal(userId, scopeType);
        List<Long> itemScopeIds = itemRepository.findByFolderIdOrderBySortOrder(folder.getId()).stream()
                .map(MyScopeFolderItemEntity::getScopeId)
                .toList();
        return toResponse(folder, itemScopeIds, 0L);
    }

    /**
     * 未分類フォルダエンティティを取得する（無ければ lazy 生成）。
     * 内部利用（招待画面・フォルダ削除時の自動再配置等から呼ばれる）。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ
     * @return 未分類フォルダエンティティ
     */
    @Transactional
    public MyScopeFolderEntity findOrCreateDefaultInternal(Long userId, ScopeType scopeType) {
        return folderRepository
                .findByUserIdAndScopeTypeAndIsDefaultTrueAndDeletedAtIsNull(userId, scopeType)
                .orElseGet(() -> createDefaultFolder(userId, scopeType));
    }

    private MyScopeFolderEntity createDefaultFolder(Long userId, ScopeType scopeType) {
        // 上限チェック（未分類は上限を超えても作成可能とする方針: ADHD 配慮で必ず受け皿を用意）。
        // 設計書 §10: 「未分類」は自動・削除不可で「迷子防止」のため、上限制約から除外。
        MyScopeFolderEntity defaultFolder = MyScopeFolderEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .name(DEFAULT_FOLDER_NAME)
                .color(null)
                .icon(null)
                .isDefault(Boolean.TRUE)
                .sortOrder(DEFAULT_FOLDER_SORT_ORDER)
                .build();
        MyScopeFolderEntity saved = folderRepository.save(defaultFolder);
        log.info("未分類フォルダを lazy 生成: userId={}, scopeType={}, folderId={}",
                userId, scopeType, saved.getId());
        return saved;
    }

    /**
     * 既存所属スコープをフォルダへ一括振り分けする（F15.3 §5.2.2）。
     *
     * @param userId ユーザーID
     * @param req    一括振り分けリクエスト
     * @return 一括振り分けレスポンス
     */
    @Transactional
    public BulkAssignResponse bulkAssign(Long userId, BulkAssignRequest req) {
        MyScopeFolderEntity folder = findOwnedFolder(userId, req.folderId());

        // フォルダの scope_type と リクエスト scope_type の整合チェック
        if (folder.getScopeType() != req.scopeType()) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH);
        }

        int assignedCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();

        for (Long scopeId : req.scopeIds()) {
            try {
                addItemWithAssignedVia(userId, req.folderId(), scopeId, AssignedVia.MANUAL);
                assignedCount++;
            } catch (BusinessException ex) {
                // 個別 scope_id ごとのエラーは件数のみ記録（存在漏洩防止: §9.2）
                skippedCount++;
                if (!errors.contains(ex.getErrorCode().getCode())) {
                    errors.add(ex.getErrorCode().getCode());
                }
            }
        }

        log.info("一括振り分け完了: userId={}, folderId={}, assigned={}, skipped={}",
                userId, req.folderId(), assignedCount, skippedCount);
        return new BulkAssignResponse(assignedCount, skippedCount, errors);
    }

    /**
     * 指定ユーザー × scope_type × scope_id のアイテムを物理削除する。
     * MembershipEventListener から呼ばれる（設計書 §6.2 / §9.6）。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープタイプ
     * @param scopeId   スコープID
     */
    @Transactional
    public void handleMembershipEnded(Long userId, ScopeType scopeType, Long scopeId) {
        List<MyScopeFolderItemEntity> items =
                itemRepository.findAllByUserAndScope(userId, scopeType, scopeId);
        if (!items.isEmpty()) {
            itemRepository.deleteAll(items);
            log.info("メンバーシップ終了に伴うフォルダアイテム削除: userId={}, scopeType={}, scopeId={}, count={}",
                    userId, scopeType, scopeId, items.size());
        }
    }

    /**
     * 指定 scope_type × scope_id のアイテムを全ユーザー分物理削除する。
     * Team / Organization 削除イベントから呼ばれる（設計書 §6.2 / §9.5）。
     *
     * @param scopeType スコープタイプ
     * @param scopeId   スコープID
     */
    @Transactional
    public void handleScopeDeleted(ScopeType scopeType, Long scopeId) {
        List<MyScopeFolderItemEntity> items =
                itemRepository.findAllByScope(scopeType, scopeId);
        if (!items.isEmpty()) {
            itemRepository.deleteAll(items);
            log.info("スコープ削除に伴うフォルダアイテム削除: scopeType={}, scopeId={}, count={}",
                    scopeType, scopeId, items.size());
        }
    }

    /**
     * 指定ユーザーの全フォルダ・アイテムを物理削除する（GDPR / 退会匿名化用）。
     *
     * <p>設計書 §9.4: フォルダは個人プリファレンスデータのため匿名化対象外（全消去）。
     * 本フェーズではメソッドのみ提供し、UserAnonymizedEvent への組み込みは
     * 既存リスナーへの後続 PR で行う。</p>
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void deleteAllByUserId(Long userId) {
        itemRepository.deleteAllByUserId(userId);
        // フォルダ本体は物理削除（user_id でまとめて消す）
        List<MyScopeFolderEntity> teamFolders = folderRepository
                .findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(userId, ScopeType.TEAM);
        List<MyScopeFolderEntity> orgFolders = folderRepository
                .findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(userId, ScopeType.ORGANIZATION);
        folderRepository.deleteAll(teamFolders);
        folderRepository.deleteAll(orgFolders);
        log.info("ユーザー退会に伴うフォルダ全削除: userId={}, teamFolders={}, orgFolders={}",
                userId, teamFolders.size(), orgFolders.size());
    }

    // ============================================================
    // ヘルパー（private）
    // ============================================================

    /**
     * IDOR防止: フォルダの所有者チェック。
     * 自分のフォルダでなければBusinessExceptionを投げる。
     */
    private MyScopeFolderEntity findOwnedFolder(Long userId, Long folderId) {
        return folderRepository.findByIdAndUserIdAndDeletedAtIsNull(folderId, userId)
                .orElseThrow(() -> new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND));
    }

    /**
     * scopefolder.entity.ScopeType を membership.domain.ScopeType に変換する。
     * F15.3 §6.3 サポータ対応の第一手段である F00.5 メンバーシップ基盤と
     * インターフェースを揃えるために必要なブリッジ。
     */
    static com.mannschaft.app.membership.domain.ScopeType toMembershipScopeType(ScopeType scopeType) {
        return switch (scopeType) {
            case TEAM -> com.mannschaft.app.membership.domain.ScopeType.TEAM;
            case ORGANIZATION -> com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION;
        };
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private ScopeFolderResponse toResponse(
            MyScopeFolderEntity folder, List<Long> itemScopeIds, long notificationUnreadCount) {
        return new ScopeFolderResponse(
                folder.getId(),
                folder.getName(),
                folder.getColor(),
                folder.getIcon(),
                folder.getSortOrder(),
                folder.isDefaultFolder(),
                itemScopeIds,
                notificationUnreadCount
        );
    }
}

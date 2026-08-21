package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ArchiveFolderResponse;
import com.mannschaft.app.bulletin.dto.ArchiveFolderTreeResponse;
import com.mannschaft.app.bulletin.dto.CreateArchiveFolderRequest;
import com.mannschaft.app.bulletin.dto.DeleteArchiveFolderResponse;
import com.mannschaft.app.bulletin.dto.UpdateArchiveFolderRequest;
import com.mannschaft.app.bulletin.entity.BulletinArchiveFolderEntity;
import com.mannschaft.app.bulletin.repository.BulletinArchiveFolderRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 掲示板 保管庫フォルダサービス（設計書 F05.1 §5 中核ロジック）。
 *
 * <p>フォルダ CRUD・ツリー取得・移動（循環参照防止・深さ検証・depth 再計算）・
 * 削除退避（スレッド NULL 化・子フォルダ繰り上げ）・scope 越境防止・フォルダ上限・
 * 並行性制御（悲観ロック）・認可（{@link BulletinAccessGuard}）を担当する。</p>
 *
 * <p>ネストは隣接リスト + {@code depth} カラム方式。深さ上限が浅い（最大5階層 = depth 0〜4）ため、
 * サブツリー展開・循環判定の再帰は最大5段で打ち止めとなり、スタックオーバーフロー/無限ループの
 * リスクはない（設計書 §5/§6）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BulletinArchiveFolderService {

    /** ネスト最大深さ（depth ≤ MAX_DEPTH = 5 階層）。 */
    static final int MAX_DEPTH = 4;

    /** ネスト最大階層数（メタ表示用）。 */
    static final int MAX_DEPTH_LEVELS = 5;

    /** スコープあたりのフォルダ数上限（設計書 §5）。 */
    static final int MAX_FOLDER_COUNT = 200;

    private final BulletinArchiveFolderRepository folderRepository;
    private final BulletinThreadRepository threadRepository;
    private final BulletinAccessGuard accessGuard;
    private final AuditLogService auditLogService;

    // ====================================================================
    // ツリー取得（閲覧 = メンバーなら可）
    // ====================================================================

    /**
     * スコープの保管庫フォルダをツリー構造で取得する（設計書 §4 GET .../archive/folders）。
     *
     * <p>スコープの全フォルダを 1 クエリで取得し、メモリ上で親子ネストを構築する（N+1 回避）。
     * 各ノードに childCount / threadCount を付与する。閲覧は所属メンバーなら可（MEMBER/SUPPORTER も閲覧可）。</p>
     */
    public ArchiveFolderTreeResponse getFolderTree(ScopeType scopeType, Long scopeId, Long userId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);

        List<BulletinArchiveFolderEntity> folders =
                folderRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(scopeType, scopeId);

        // フォルダ別のアーカイブ済みスレッド数（threadCount）
        Map<UUID, Long> threadCountByFolder = new HashMap<>();
        for (Object[] row : threadRepository.countArchivedThreadsByFolder(scopeType, scopeId)) {
            threadCountByFolder.put((UUID) row[0], (Long) row[1]);
        }

        // 親 ID ごとに子をグルーピング（childCount 算出 + ツリー構築用）
        Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent = new HashMap<>();
        for (BulletinArchiveFolderEntity f : folders) {
            childrenByParent
                    .computeIfAbsent(f.getParentFolderId(), k -> new ArrayList<>())
                    .add(f);
        }

        // ルート（parentFolderId = null）から再帰的にレスポンスを組み立てる
        List<ArchiveFolderResponse> rootNodes = buildChildren(null, childrenByParent, threadCountByFolder);

        long unfiledCount = threadRepository
                .countByScopeTypeAndScopeIdAndIsArchivedTrueAndArchiveFolderIdIsNull(scopeType, scopeId);

        ArchiveFolderTreeResponse.Meta meta = ArchiveFolderTreeResponse.Meta.builder()
                .unfiledThreadCount(unfiledCount)
                .totalFolderCount((long) folders.size())
                .maxDepth(MAX_DEPTH_LEVELS)
                .maxFolderCount(MAX_FOLDER_COUNT)
                .build();

        return ArchiveFolderTreeResponse.builder()
                .data(rootNodes)
                .meta(meta)
                .build();
    }

    /**
     * 指定親の子フォルダ群を再帰的に {@link ArchiveFolderResponse} に変換する。
     */
    private List<ArchiveFolderResponse> buildChildren(
            UUID parentId,
            Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent,
            Map<UUID, Long> threadCountByFolder) {
        List<BulletinArchiveFolderEntity> children = childrenByParent.get(parentId);
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        List<ArchiveFolderResponse> result = new ArrayList<>(children.size());
        for (BulletinArchiveFolderEntity f : children) {
            List<ArchiveFolderResponse> nested =
                    buildChildren(f.getId(), childrenByParent, threadCountByFolder);
            int childCount = childrenByParent.getOrDefault(f.getId(), List.of()).size();
            long threadCount = threadCountByFolder.getOrDefault(f.getId(), 0L);
            result.add(toResponse(f, childCount, (int) threadCount, nested));
        }
        return result;
    }

    // ====================================================================
    // 作成（ADMIN / DEPUTY MANAGE_CONTENT）
    // ====================================================================

    /**
     * 保管庫フォルダを作成する（設計書 §4 POST / §5）。
     *
     * <p>depth = 親.depth + 1（ルート 0）、depth &gt; 4 で 400。フォルダ数上限 200 超過で 409。
     * 親フォルダの scope 越境で 404（不在と同一ステータスにして他テナントのフォルダ UUID の実在を秘匿）。並行性: スコープ行をロックして件数を計数し上限すり抜けを防ぐ。</p>
     */
    @Transactional
    public ArchiveFolderResponse createFolder(
            ScopeType scopeType, Long scopeId, Long userId, CreateArchiveFolderRequest request) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);

        int depth = 0;
        UUID parentId = request.getParentFolderId();
        if (parentId != null) {
            BulletinArchiveFolderEntity parent = findFolderForUpdateOrThrow(parentId);
            verifyScope(parent, scopeType, scopeId);
            depth = parent.getDepth() + 1;
            if (depth > MAX_DEPTH) {
                throw new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_DEPTH_EXCEEDED);
            }
        }

        // フォルダ数上限（ロック付き計数で同時すり抜けを防ぐ）
        long activeCount = folderRepository.countByScopeForUpdate(scopeType, scopeId);
        if (activeCount >= MAX_FOLDER_COUNT) {
            throw new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_LIMIT_EXCEEDED);
        }

        int nextOrder = folderRepository.findMaxDisplayOrder(scopeType, scopeId, parentId) + 1;

        BulletinArchiveFolderEntity entity = BulletinArchiveFolderEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .parentFolderId(parentId)
                .name(request.getName())
                .color(request.getColor())
                .icon(request.getIcon())
                .depth(depth)
                .displayOrder(nextOrder)
                .createdBy(userId)
                .build();

        BulletinArchiveFolderEntity saved = folderRepository.save(entity);
        log.info("保管庫フォルダ作成: scopeType={}, scopeId={}, folderId={}, depth={}",
                scopeType, scopeId, saved.getId(), depth);
        recordAudit(AuditEventType.BULLETIN_ARCHIVE_FOLDER_CREATED, scopeType, scopeId, userId, saved.getId());
        return toResponse(saved, 0, 0, List.of());
    }

    // ====================================================================
    // 更新・移動（ADMIN / DEPUTY MANAGE_CONTENT）
    // ====================================================================

    /**
     * 保管庫フォルダを更新・移動する（設計書 §4 PUT / §5）。
     *
     * <p>name/color/icon/displayOrder は指定フィールドのみ更新。parentFolderId が明示された場合は移動:
     * 循環参照（自分自身・子孫）で 400、移動後サブツリー最大深さ &gt; 4 で 400、対象 + サブツリーの depth 再計算。
     * 並行性: スコープ全フォルダを悲観ロックして循環判定・depth 再計算を 1 トランザクションで実行する。</p>
     */
    @Transactional
    public ArchiveFolderResponse updateFolder(
            ScopeType scopeType, Long scopeId, Long userId, UUID folderId, UpdateArchiveFolderRequest request) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);

        // スコープ全フォルダを悲観ロック取得（移動時のサブツリー展開・循環判定・depth 再計算を安全化）
        List<BulletinArchiveFolderEntity> all = folderRepository.findByScopeForUpdate(scopeType, scopeId);
        Map<UUID, BulletinArchiveFolderEntity> byId = new HashMap<>();
        Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent = new HashMap<>();
        for (BulletinArchiveFolderEntity f : all) {
            byId.put(f.getId(), f);
            childrenByParent.computeIfAbsent(f.getParentFolderId(), k -> new ArrayList<>()).add(f);
        }

        BulletinArchiveFolderEntity target = byId.get(folderId);
        if (target == null) {
            throw new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND);
        }

        // メタ更新（name/color/icon/displayOrder）
        target.updateMeta(request.getName(), request.getColor(), request.getIcon(), request.getDisplayOrder());

        // 移動操作（parentFolderId が JSON に明示された場合のみ）
        if (request.isParentFolderIdPresent()) {
            UUID newParentId = request.getParentFolderId();
            moveFolder(target, newParentId, byId, childrenByParent);
        }

        folderRepository.save(target);
        log.info("保管庫フォルダ更新: folderId={}, moved={}", folderId, request.isParentFolderIdPresent());
        recordAudit(AuditEventType.BULLETIN_ARCHIVE_FOLDER_UPDATED, scopeType, scopeId, userId, folderId);

        int childCount = childrenByParent.getOrDefault(folderId, List.of()).size();
        return toResponse(target, childCount, 0, List.of());
    }

    /**
     * フォルダ移動の実処理（循環参照防止・深さ検証・サブツリー depth 再計算）。
     *
     * @param target          移動対象フォルダ（ロック済み）
     * @param newParentId     新しい親 ID（NULL = ルートへ移動）
     * @param byId            スコープ全フォルダ（ロック済み）
     * @param childrenByParent 親 ID → 子リスト
     */
    private void moveFolder(
            BulletinArchiveFolderEntity target,
            UUID newParentId,
            Map<UUID, BulletinArchiveFolderEntity> byId,
            Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent) {

        int newDepth = 0;
        if (newParentId != null) {
            BulletinArchiveFolderEntity newParent = byId.get(newParentId);
            if (newParent == null) {
                throw new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND);
            }
            // 循環参照防止: 移動先が自分自身 or 自分の子孫なら 400
            //  対象サブツリー（子孫集合）を深さ上限 5 段で展開し、その中に newParentId が含まれるか判定。
            if (target.getId().equals(newParentId)
                    || collectSubtreeIds(target.getId(), childrenByParent).contains(newParentId)) {
                throw new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_CYCLE);
            }
            newDepth = newParent.getDepth() + 1;
        }

        // 移動後サブツリー全体の最大深さ ≤ 4 検証
        //  「新しい depth + サブツリー内の相対最大深さ」が MAX_DEPTH を超えないか
        int relativeMaxDepth = subtreeRelativeMaxDepth(target, childrenByParent);
        if (newDepth + relativeMaxDepth > MAX_DEPTH) {
            throw new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_DEPTH_EXCEEDED);
        }

        // 対象 + サブツリーの depth を再計算（新しい親基準で再採番）
        target.moveTo(newParentId, newDepth);
        recomputeSubtreeDepth(target, newDepth, childrenByParent);
    }

    // ====================================================================
    // 削除（論理削除 + 退避）（ADMIN / DEPUTY MANAGE_CONTENT）
    // ====================================================================

    /**
     * 保管庫フォルダを論理削除し、配下を退避する（設計書 §4 DELETE / §5 退避ロジック）。
     *
     * <p>1 トランザクション内で:
     * <ol>
     *   <li>直下スレッドの archive_folder_id を NULL（保管庫直下）へ退避（is_archived は TRUE 維持）</li>
     *   <li>直下子フォルダを削除フォルダの親（なければルート）へ繰り上げ + サブツリー depth 再計算</li>
     *   <li>削除対象に deleted_at をセット</li>
     * </ol>
     * 並行性: スコープ全フォルダを悲観ロックして退避・繰り上げ・depth 再計算を安全化する。</p>
     */
    @Transactional
    public DeleteArchiveFolderResponse deleteFolder(
            ScopeType scopeType, Long scopeId, Long userId, UUID folderId) {
        accessGuard.checkMembership(userId, scopeType, scopeId);
        accessGuard.requireManageContent(userId, scopeType, scopeId);

        List<BulletinArchiveFolderEntity> all = folderRepository.findByScopeForUpdate(scopeType, scopeId);
        Map<UUID, BulletinArchiveFolderEntity> byId = new HashMap<>();
        Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent = new HashMap<>();
        for (BulletinArchiveFolderEntity f : all) {
            byId.put(f.getId(), f);
            childrenByParent.computeIfAbsent(f.getParentFolderId(), k -> new ArrayList<>()).add(f);
        }

        BulletinArchiveFolderEntity target = byId.get(folderId);
        if (target == null) {
            throw new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND);
        }

        // 1. 直下スレッドの archive_folder_id を NULL（保管庫直下）へ退避
        int movedThreadCount = threadRepository.bulkClearArchiveFolderId(folderId);

        // 2. 直下子フォルダを削除フォルダの親へ繰り上げ + サブツリー depth 再計算
        UUID promoteTargetParentId = target.getParentFolderId(); // 親（ルートなら null）
        int promoteBaseDepth = (promoteTargetParentId == null)
                ? 0
                : byId.get(promoteTargetParentId).getDepth() + 1;
        List<BulletinArchiveFolderEntity> directChildren =
                childrenByParent.getOrDefault(folderId, List.of());
        int promotedFolderCount = directChildren.size();
        for (BulletinArchiveFolderEntity child : directChildren) {
            child.moveTo(promoteTargetParentId, promoteBaseDepth);
            recomputeSubtreeDepth(child, promoteBaseDepth, childrenByParent);
            folderRepository.save(child);
        }

        // 3. 削除対象を論理削除
        target.softDelete();
        folderRepository.save(target);

        log.info("保管庫フォルダ削除: folderId={}, movedThreads={}, promotedFolders={}",
                folderId, movedThreadCount, promotedFolderCount);
        recordAudit(AuditEventType.BULLETIN_ARCHIVE_FOLDER_DELETED, scopeType, scopeId, userId, folderId);

        String message = String.format(
                "フォルダを削除しました。%d件のスレッドを保管庫直下に移動し、%d件の子フォルダを繰り上げました",
                movedThreadCount, promotedFolderCount);
        return DeleteArchiveFolderResponse.builder()
                .id(folderId)
                .deletedAt(target.getDeletedAt())
                .movedThreadCount(movedThreadCount)
                .promotedFolderCount(promotedFolderCount)
                .message(message)
                .build();
    }

    // ====================================================================
    // フォルダ存在 + scope 検証ヘルパー（archive 拡張 / スレッド振り分けから利用）
    // ====================================================================

    /**
     * フォルダの存在 + scope 一致を検証して返す（スレッド振り分け・archive 拡張から呼ぶ）。
     *
     * @throws BusinessException 不存在（404）/ scope 越境（404・存在秘匿のため不在と同一ステータス）
     */
    BulletinArchiveFolderEntity validateFolderInScope(ScopeType scopeType, Long scopeId, UUID folderId) {
        BulletinArchiveFolderEntity folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND));
        verifyScope(folder, scopeType, scopeId);
        return folder;
    }

    // ====================================================================
    // 内部ユーティリティ
    // ====================================================================

    private BulletinArchiveFolderEntity findFolderForUpdateOrThrow(UUID folderId) {
        return folderRepository.findByIdForUpdate(folderId)
                .orElseThrow(() -> new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND));
    }

    private void verifyScope(BulletinArchiveFolderEntity folder, ScopeType scopeType, Long scopeId) {
        // 越境は不在と同じ 404 に畳む（ARCHIVE_FOLDER_SCOPE_MISMATCH は 404 でマップ登録済み）。
        // ステータスを割ると応答差から他テナントのフォルダ UUID の実在が判別できる（存在オラクル）。
        if (folder.getScopeType() != scopeType || !folder.getScopeId().equals(scopeId)) {
            throw new BusinessException(BulletinErrorCode.ARCHIVE_FOLDER_SCOPE_MISMATCH);
        }
    }

    /**
     * 指定フォルダの子孫 ID 集合を深さ上限 5 段で展開する（循環参照判定用）。
     */
    private java.util.Set<UUID> collectSubtreeIds(
            UUID rootId, Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent) {
        java.util.Set<UUID> result = new java.util.HashSet<>();
        collectSubtreeIdsRecursive(rootId, childrenByParent, result, 0);
        return result;
    }

    private void collectSubtreeIdsRecursive(
            UUID parentId,
            Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent,
            java.util.Set<UUID> acc,
            int depthGuard) {
        if (depthGuard > MAX_DEPTH_LEVELS) {
            return; // 深さ上限で打ち止め（無限ループ防止）
        }
        for (BulletinArchiveFolderEntity child : childrenByParent.getOrDefault(parentId, List.of())) {
            if (acc.add(child.getId())) {
                collectSubtreeIdsRecursive(child.getId(), childrenByParent, acc, depthGuard + 1);
            }
        }
    }

    /**
     * 対象フォルダを基準としたサブツリー内の相対最大深さを返す（対象自身 = 0）。
     */
    private int subtreeRelativeMaxDepth(
            BulletinArchiveFolderEntity root,
            Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent) {
        return subtreeRelativeMaxDepthRecursive(root.getId(), childrenByParent, 0);
    }

    private int subtreeRelativeMaxDepthRecursive(
            UUID parentId,
            Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent,
            int relativeDepth) {
        int max = relativeDepth;
        if (relativeDepth >= MAX_DEPTH_LEVELS) {
            return max; // 深さ上限で打ち止め
        }
        for (BulletinArchiveFolderEntity child : childrenByParent.getOrDefault(parentId, List.of())) {
            max = Math.max(max,
                    subtreeRelativeMaxDepthRecursive(child.getId(), childrenByParent, relativeDepth + 1));
        }
        return max;
    }

    /**
     * 対象フォルダのサブツリー（子孫）の depth を新しい基準で再採番する。
     * 対象自身の depth は呼び出し元で設定済み（{@code baseDepth}）であること。
     */
    private void recomputeSubtreeDepth(
            BulletinArchiveFolderEntity root,
            int baseDepth,
            Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent) {
        recomputeSubtreeDepthRecursive(root.getId(), baseDepth, childrenByParent, 0);
    }

    private void recomputeSubtreeDepthRecursive(
            UUID parentId,
            int parentDepth,
            Map<UUID, List<BulletinArchiveFolderEntity>> childrenByParent,
            int depthGuard) {
        if (depthGuard > MAX_DEPTH_LEVELS) {
            return;
        }
        for (BulletinArchiveFolderEntity child : childrenByParent.getOrDefault(parentId, List.of())) {
            child.setDepthValue(parentDepth + 1);
            folderRepository.save(child);
            recomputeSubtreeDepthRecursive(child.getId(), parentDepth + 1, childrenByParent, depthGuard + 1);
        }
    }

    private ArchiveFolderResponse toResponse(
            BulletinArchiveFolderEntity f, int childCount, int threadCount, List<ArchiveFolderResponse> children) {
        return ArchiveFolderResponse.builder()
                .id(f.getId())
                .parentId(f.getParentFolderId())
                .name(f.getName())
                .color(f.getColor())
                .icon(f.getIcon())
                .depth(f.getDepth())
                .displayOrder(f.getDisplayOrder())
                .childCount(childCount)
                .threadCount(threadCount)
                .children(children)
                .build();
    }

    private void recordAudit(
            AuditEventType eventType, ScopeType scopeType, Long scopeId, Long actorUserId, UUID folderId) {
        Long teamId = scopeType == ScopeType.TEAM ? scopeId : null;
        Long organizationId = scopeType == ScopeType.ORGANIZATION ? scopeId : null;
        String metadata = String.format(
                "{\"source\":\"BULLETIN\",\"archive_folder_id\":\"%s\",\"scope_type\":\"%s\",\"scope_id\":%d}",
                folderId, scopeType.name(), scopeId);
        auditLogService.record(eventType.name(), actorUserId, null,
                teamId, organizationId, null, null, null, metadata);
    }
}

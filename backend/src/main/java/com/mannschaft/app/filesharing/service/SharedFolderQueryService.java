package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderDetailResponse;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F05.5 フォルダ詳細／一覧／作成のクエリ・コマンドサービス（{@code /api/v1/files/folders}）。
 *
 * <p>本サービスは {@link SharedFolderService#getFolder} の<b>認可素通り問題</b>を回避するために新設した。
 * 既存 {@code getFolder} は {@link FolderScopeAccessGuard} を呼ぶのみで、大会以外（TEAM/ORG/PERSONAL）の
 * スコープでは認可が一切効かず、フォルダ ID を渡すだけで他チーム・他人のフォルダ内容が取得できる
 * 情報漏洩があった。本サービスは folderId / scope からスコープを解決し、スコープ別に自前で認可を当てる。</p>
 *
 * <p>認可マトリクス:</p>
 * <ul>
 *   <li>PERSONAL: 所有者本人以外は {@code FOLDER_NOT_FOUND}（404・存在隠蔽）</li>
 *   <li>TEAM: {@link AccessControlService#checkMembership} で TEAM メンバー必須（403 COMMON_002）</li>
 *   <li>ORGANIZATION: 同上 ORGANIZATION メンバー必須（403）</li>
 *   <li>TOURNAMENT / TOURNAMENT_DIVISION: {@link FolderScopeAccessGuard} へ委譲（連絡スペース閲覧認可流用）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFolderQueryService {

    private final SharedFolderRepository folderRepository;
    private final SharedFileRepository fileRepository;
    private final FolderScopeAccessGuard folderScopeAccessGuard;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;
    private final SharedFileQuotaService sharedFileQuotaService;

    /** パンくず構築時の祖先探索の深さ上限（循環・異常データ防御）。 */
    private static final int MAX_BREADCRUMB_DEPTH = 50;

    /** カスケード削除時に走査するフォルダ数の上限（循環・異常データ防御の安全弁）。 */
    private static final int MAX_DELETE_FOLDER_COUNT = 10_000;

    // ========================================
    // 詳細
    // ========================================

    /**
     * フォルダ詳細を取得する（スコープ別認可つき）。
     *
     * @param folderId フォルダ ID
     * @param userId   操作ユーザー ID
     * @return フォルダ詳細レスポンス
     */
    public FolderDetailResponse getFolderDetail(Long folderId, Long userId) {
        SharedFolderEntity folder = findFolderOrThrow(folderId);
        authorizeView(folder, userId);

        List<SharedFolderEntity> subfolders = folderRepository.findByParentIdOrderByNameAsc(folderId);
        List<SharedFileEntity> files = fileRepository.findByFolderIdOrderByNameAsc(folderId);
        List<FolderDetailResponse.BreadcrumbItem> breadcrumbs = buildBreadcrumbs(folder);

        // 表示名は N+1 を避けて一括解決する（フォルダ作成者・サブフォルダ作成者・ファイルアップロード者）。
        Map<Long, String> nameMap = resolveDisplayNames(folder, subfolders, files);

        List<FolderDetailResponse.FolderSummary> subSummaries = new ArrayList<>(subfolders.size());
        for (SharedFolderEntity sub : subfolders) {
            subSummaries.add(toFolderSummary(sub, (int) fileRepository.countByFolderId(sub.getId()), nameMap));
        }
        List<FolderDetailResponse.FileSummary> fileSummaries = new ArrayList<>(files.size());
        for (SharedFileEntity f : files) {
            fileSummaries.add(toFileSummary(f, nameMap));
        }

        return new FolderDetailResponse(
                folder.getId(),
                folder.getScopeType().name(),
                resolveScopeId(folder),
                folder.getParentId(),
                folder.getName(),
                folder.getDescription(),
                toUserRef(folder.getCreatedBy(), nameMap),
                files.size(),
                subfolders.size(),
                folder.getCreatedAt(),
                folder.getUpdatedAt(),
                subSummaries,
                fileSummaries,
                breadcrumbs);
    }

    // ========================================
    // 一覧
    // ========================================

    /**
     * スコープのフォルダ一覧を取得する（ルート or サブ）。
     *
     * <p>{@code parentId} 指定時はその親フォルダのスコープで認可し直下のサブフォルダを返す
     * （IDOR 対策・親が他チームでも folderId を渡すだけでは覗けない）。null のときは
     * {@code (scopeType, scopeId)} で認可しルートフォルダを返す。</p>
     *
     * @param scopeType スコープ種別（TEAM / ORGANIZATION / PERSONAL）
     * @param scopeId   スコープ ID（teamId / organizationId / userId の文字列）
     * @param parentId  親フォルダ ID（null ならルート）
     * @param userId    操作ユーザー ID
     * @return フォルダ要約リスト
     */
    public List<FolderDetailResponse.FolderSummary> listFolders(
            String scopeType, String scopeId, Long parentId, Long userId) {
        List<SharedFolderEntity> folders;
        if (parentId != null) {
            // 親フォルダ実体のスコープで認可してから直下を返す。
            SharedFolderEntity parent = findFolderOrThrow(parentId);
            authorizeView(parent, userId);
            folders = folderRepository.findByParentIdOrderByNameAsc(parentId);
        } else {
            authorizeScopeView(scopeType, scopeId, userId);
            folders = listRootFolders(scopeType, scopeId, userId);
        }

        Map<Long, String> nameMap = resolveDisplayNames(null, folders, List.of());
        List<FolderDetailResponse.FolderSummary> result = new ArrayList<>(folders.size());
        for (SharedFolderEntity f : folders) {
            result.add(toFolderSummary(f, (int) fileRepository.countByFolderId(f.getId()), nameMap));
        }
        return result;
    }

    // ========================================
    // 作成
    // ========================================

    /**
     * フォルダを作成する（スコープ別認可つき）。
     *
     * @param request 作成リクエスト（scopeType / scopeId / parentId / name）
     * @param scopeId スコープ ID（teamId / organizationId、PERSONAL では無視）
     * @param userId  操作ユーザー ID
     * @return 作成されたフォルダ要約
     */
    @Transactional
    public FolderDetailResponse.FolderSummary createFolder(
            CreateFolderRequest request, String scopeId, Long userId) {
        String scopeType = request.getScopeType();
        authorizeScopeView(scopeType, scopeId, userId);
        validateFolderNameUnique(request.getParentId(), request.getName());

        FileScopeType type = FileScopeType.valueOf(scopeType);
        var builder = SharedFolderEntity.builder()
                .scopeType(type)
                .parentId(request.getParentId())
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(userId);
        switch (type) {
            case TEAM -> builder.teamId(parseScopeId(scopeId));
            case ORGANIZATION -> builder.organizationId(parseScopeId(scopeId));
            case PERSONAL -> builder.userId(userId);
            default -> throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }

        SharedFolderEntity saved = folderRepository.save(builder.build());
        log.info("フォルダ作成(汎用EP): scopeType={}, scopeId={}, folderId={}", scopeType, scopeId, saved.getId());

        Map<Long, String> nameMap = resolveDisplayNames(saved, List.of(), List.of());
        return toFolderSummary(saved, 0, nameMap);
    }

    // ========================================
    // 削除
    // ========================================

    /**
     * フォルダをカスケード論理削除する（スコープ別認可＋容量戻しつき）。
     *
     * <p>従来 {@code SharedFolderController} に DELETE マッピングが無く、FE の
     * {@code DELETE /api/v1/files/folders/{id}} が非存在ルート → 500 になっていた根治。
     * 既存 {@link SharedFolderService#deleteFolder} は (1) 認可が大会スコープの guard のみで
     * PERSONAL/TEAM/ORG が素通り（漏洩）、(2) フォルダ単体しか soft-delete せず配下ファイル・
     * サブフォルダが孤児化、(3) 容量戻しが無く {@code usedBytes} が減らない、という三重の欠陥が
     * あったため流用しない。本メソッドは:</p>
     * <ul>
     *   <li><b>認可</b>: {@link #authorizeView} と同一のスコープ別ポリシー（他人個人=404・非所属=403・
     *       大会=連絡スペース認可）を当て、IDOR / クロススコープ削除を防ぐ。</li>
     *   <li><b>カスケード</b>: 当該フォルダを根とする部分木（自身＋全サブフォルダ）を再帰収集し、
     *       配下の全ファイルと全フォルダを soft-delete する。循環・異常データに備え
     *       {@value #MAX_DELETE_FOLDER_COUNT} 件の安全弁と訪問済み集合で無限ループを防ぐ。</li>
     *   <li><b>容量戻し</b>: ファイルごとに {@link SharedFileQuotaService#recordFileDeletion} を呼び、
     *       各ファイルが属するフォルダのスコープで {@code usedBytes} を減算する（保存・取り出し・
     *       容量を整合させる本機能の肝）。</li>
     * </ul>
     *
     * @param folderId 削除対象フォルダ ID
     * @param userId   操作ユーザー ID（未認証は呼び出し側で 401 済み）
     */
    @Transactional
    public void deleteFolder(Long folderId, Long userId) {
        SharedFolderEntity root = findFolderOrThrow(folderId);
        // 認可は走査・削除の前に当てる（弾かれた場合は何も削除しない）。
        authorizeView(root, userId);

        List<SharedFolderEntity> subtree = collectSubtree(root);
        int deletedFiles = 0;
        for (SharedFolderEntity folder : subtree) {
            // 各フォルダ配下のファイルを soft-delete し、フォルダ自身のスコープで容量を戻す。
            for (SharedFileEntity file : fileRepository.findByFolderIdOrderByNameAsc(folder.getId())) {
                file.softDelete();
                fileRepository.save(file);
                sharedFileQuotaService.recordFileDeletion(folder, file.getId(), file.getFileSize(), userId);
                deletedFiles++;
            }
            folder.softDelete();
            folderRepository.save(folder);
        }
        log.info("フォルダ削除(カスケード): folderId={}, scopeType={}, deletedFolders={}, deletedFiles={}, userId={}",
                folderId, root.getScopeType(), subtree.size(), deletedFiles, userId);
    }

    /**
     * 与えられた根フォルダを含む部分木（根 → サブフォルダの幅優先）を収集する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により削除済みフォルダは
     * {@link SharedFolderRepository#findByParentIdOrderByNameAsc} に現れず自然に除外される。
     * 異常データ（親子循環）に備え訪問済み ID 集合で再訪を断ち、走査件数が
     * {@value #MAX_DELETE_FOLDER_COUNT} を超えたら {@link IllegalStateException} で打ち切る。</p>
     */
    private List<SharedFolderEntity> collectSubtree(SharedFolderEntity root) {
        List<SharedFolderEntity> result = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<SharedFolderEntity> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            SharedFolderEntity current = queue.poll();
            if (!visited.add(current.getId())) {
                continue; // 循環・重複は無視
            }
            if (result.size() >= MAX_DELETE_FOLDER_COUNT) {
                throw new IllegalStateException(
                        "フォルダ削除の走査件数が上限を超えました: rootFolderId=" + root.getId());
            }
            result.add(current);
            queue.addAll(folderRepository.findByParentIdOrderByNameAsc(current.getId()));
        }
        return result;
    }

    // ========================================
    // 認可
    // ========================================

    /**
     * フォルダ ID からスコープを解決して閲覧認可を当てる（外部ドメイン入口からの再利用用）。
     *
     * <p>ファイル単位のダウンロード URL 発行など、ファイル → フォルダの順で解決したうえで
     * フォルダスコープ別の閲覧認可を当てたいケースで {@link SharedFileService} から呼ぶ。
     * {@link #authorizeView} と同一の漏洩防止ポリシー（PERSONAL=本人以外404 /
     * TEAM・ORG=checkMembership 403 / 大会=連絡スペース認可）を適用する。</p>
     *
     * @param folderId フォルダ ID
     * @param userId   操作ユーザー ID（未認証は null。null は所有者不一致扱いで PERSONAL 404・TEAM/ORG 403）
     */
    public void authorizeFolderViewById(Long folderId, Long userId) {
        SharedFolderEntity folder = findFolderOrThrow(folderId);
        authorizeView(folder, userId);
    }

    /**
     * フォルダ実体のスコープに応じて閲覧認可を当てる（漏洩防止の核）。
     */
    private void authorizeView(SharedFolderEntity folder, Long userId) {
        switch (folder.getScopeType()) {
            case PERSONAL -> {
                if (folder.getUserId() == null || !folder.getUserId().equals(userId)) {
                    // 他人の個人フォルダは存在を漏らさず 404。
                    throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
                }
            }
            case TEAM -> accessControlService.checkMembership(userId, folder.getTeamId(), "TEAM");
            case ORGANIZATION ->
                    accessControlService.checkMembership(userId, folder.getOrganizationId(), "ORGANIZATION");
            case TOURNAMENT, TOURNAMENT_DIVISION ->
                    folderScopeAccessGuard.checkFolderViewByFolderId(folder.getId(), userId);
        }
    }

    /**
     * {@code (scopeType, scopeId)} だけで（フォルダ実体なしに）閲覧認可を当てる（ルート一覧・作成用）。
     */
    private void authorizeScopeView(String scopeType, String scopeId, Long userId) {
        FileScopeType type = FileScopeType.valueOf(scopeType);
        switch (type) {
            case PERSONAL -> {
                if (!String.valueOf(userId).equals(scopeId)) {
                    throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
                }
            }
            case TEAM -> accessControlService.checkMembership(userId, parseScopeId(scopeId), "TEAM");
            case ORGANIZATION -> accessControlService.checkMembership(userId, parseScopeId(scopeId), "ORGANIZATION");
            // 大会スコープのルート一覧／作成は本汎用 EP の対象外（各専用 EP・連絡スペース認可を使う）。
            default -> throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }
    }

    private List<SharedFolderEntity> listRootFolders(String scopeType, String scopeId, Long userId) {
        return switch (FileScopeType.valueOf(scopeType)) {
            case TEAM -> folderRepository.findByTeamIdAndParentIdIsNullOrderByNameAsc(parseScopeId(scopeId));
            case ORGANIZATION ->
                    folderRepository.findByOrganizationIdAndParentIdIsNullOrderByNameAsc(parseScopeId(scopeId));
            case PERSONAL ->
                    folderRepository.findByUserIdAndScopeTypeAndParentIdIsNullOrderByNameAsc(userId, FileScopeType.PERSONAL);
            default -> List.of();
        };
    }

    // ========================================
    // パンくず
    // ========================================

    /**
     * ルート→当該フォルダの順でパンくずを構築する。
     *
     * <p>{@code parentId} を {@link SharedFolderRepository#findById} で辿り {@link LinkedList#addFirst} で
     * 先頭に積む。深さ上限 {@value #MAX_BREADCRUMB_DEPTH} で循環・異常データを防ぐ。
     * {@code @SQLRestriction("deleted_at IS NULL")} により削除済みの祖先は findById が空を返して
     * 自動的に欠落する（途中で打ち切り）。</p>
     */
    private List<FolderDetailResponse.BreadcrumbItem> buildBreadcrumbs(SharedFolderEntity current) {
        LinkedList<FolderDetailResponse.BreadcrumbItem> crumbs = new LinkedList<>();
        SharedFolderEntity cursor = current;
        int depth = 0;
        while (cursor != null && depth < MAX_BREADCRUMB_DEPTH) {
            crumbs.addFirst(new FolderDetailResponse.BreadcrumbItem(cursor.getId(), cursor.getName()));
            Long parentId = cursor.getParentId();
            if (parentId == null) {
                break;
            }
            cursor = folderRepository.findById(parentId).orElse(null); // 削除済み祖先は空 → 打ち切り
            depth++;
        }
        return crumbs;
    }

    // ========================================
    // 組み立てヘルパー
    // ========================================

    private FolderDetailResponse.FolderSummary toFolderSummary(
            SharedFolderEntity folder, int fileCount, Map<Long, String> nameMap) {
        return new FolderDetailResponse.FolderSummary(
                folder.getId(),
                folder.getScopeType().name(),
                resolveScopeId(folder),
                folder.getParentId(),
                folder.getName(),
                folder.getDescription(),
                toUserRef(folder.getCreatedBy(), nameMap),
                fileCount,
                null, // 孫フォルダ数は画面未使用・N+1 回避のため解決しない
                folder.getCreatedAt(),
                folder.getUpdatedAt());
    }

    private FolderDetailResponse.FileSummary toFileSummary(SharedFileEntity file, Map<Long, String> nameMap) {
        return new FolderDetailResponse.FileSummary(
                file.getId(),
                file.getFolderId(),
                file.getName(),
                file.getName(), // originalFileName 専用カラムは無いため name を流用
                file.getFileSize(),
                file.getContentType(),
                file.getDescription(),
                toUserRef(file.getCreatedBy(), nameMap),
                file.getCurrentVersion(),
                null, // currentVersionId は本詳細では解決しない
                List.of(),
                0,
                file.getCreatedAt(),
                file.getUpdatedAt());
    }

    private FolderDetailResponse.UserRef toUserRef(Long userId, Map<Long, String> nameMap) {
        if (userId == null) {
            return null;
        }
        return new FolderDetailResponse.UserRef(userId, nameMap.get(userId));
    }

    /**
     * フォルダ作成者・サブフォルダ作成者・ファイルアップロード者の表示名を一括解決する（N+1 回避）。
     */
    private Map<Long, String> resolveDisplayNames(
            SharedFolderEntity folder, List<SharedFolderEntity> subfolders, List<SharedFileEntity> files) {
        Set<Long> userIds = new LinkedHashSet<>();
        if (folder != null && folder.getCreatedBy() != null) {
            userIds.add(folder.getCreatedBy());
        }
        for (SharedFolderEntity sub : subfolders) {
            if (sub.getCreatedBy() != null) {
                userIds.add(sub.getCreatedBy());
            }
        }
        for (SharedFileEntity f : files) {
            if (f.getCreatedBy() != null) {
                userIds.add(f.getCreatedBy());
            }
        }
        return nameResolverService.resolveUserDisplayNames(userIds);
    }

    /**
     * スコープに応じた scopeId 文字列を返す（FE 契約: {@code SharedFolder.scopeId} は string）。
     */
    private String resolveScopeId(SharedFolderEntity folder) {
        return switch (folder.getScopeType()) {
            case TEAM -> toStr(folder.getTeamId());
            case ORGANIZATION -> toStr(folder.getOrganizationId());
            case PERSONAL -> toStr(folder.getUserId());
            case TOURNAMENT, TOURNAMENT_DIVISION -> toStr(folder.getScopeRefId());
        };
    }

    private static String toStr(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long parseScopeId(String scopeId) {
        if (scopeId == null || scopeId.isBlank()) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }
        try {
            return Long.valueOf(scopeId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }
    }

    private SharedFolderEntity findFolderOrThrow(Long folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND));
    }

    private void validateFolderNameUnique(Long parentId, String name) {
        if (folderRepository.existsByParentIdAndName(parentId, name)) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NAME_DUPLICATE);
        }
    }
}

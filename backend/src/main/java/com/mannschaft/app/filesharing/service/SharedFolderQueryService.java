package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileVisibilityRole;
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
 * <p>本サービスは folderId / scope からスコープを解決し、スコープ別に自前で認可を当てる
 * （大会以外の TEAM/ORG/PERSONAL スコープを {@link FolderScopeAccessGuard} に委ねない独立実装として新設した）。</p>
 *
 * <p><b>認可根治 Wave7 での更新:</b> {@link SharedFolderService} 側にも同一ポリシーの認可
 * （{@code checkFolderViewAccess} / {@code checkFolderManageAccess}）を敷設したため、両者は
 * <b>同一の認可マトリクス</b>を持つ。本サービスは引き続き汎用 EP（{@code /api/v1/files/folders}）の
 * 窓口として、パンくず・カスケード削除・容量戻し・最低可視ロール（B）といった付加ロジックを担当する。</p>
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
    private final AccessControlService accessControlService;
    private final SharedFolderAccessGuard folderAccessGuard;
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
        // B: ファイル個別の最低可視ロールは一覧経路（listFiles）と同一の絞り込みをクエリ段階で当てる。
        // 詳細経路だけが絞り込みを欠くと、同じ利用者に対して一覧と詳細で可視範囲が食い違う。
        List<SharedFileEntity> files = findVisibleFiles(folder, userId);
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
                folder.getMinVisibleRole(),
                folder.getDownloadDisabled(),
                folder.getCreatedAt(),
                folder.getUpdatedAt(),
                subSummaries,
                fileSummaries,
                breadcrumbs);
    }

    /**
     * フォルダ内のファイルのうち、呼出ユーザーが<b>最低可視ロール（B）を満たすもの</b>だけをクエリ段階で取得する。
     *
     * <p>絞り込み条件の解決は一覧経路（{@link SharedFileService#listFilesPaged}）と同一の
     * {@link #resolveVisibleFileLevels(SharedFolderEntity, Long)} を用いる。判定結果を
     * レスポンスの表示用フィールドに載せるだけでは、より厳しい最低可視ロールを持つファイルの
     * メタ情報（名称・サイズ・作成者）が応答本文に含まれてしまうため、取得そのものを絞る。</p>
     *
     * @param folder 対象フォルダ（閲覧認可は呼び出し側で通過済み）
     * @param userId 操作ユーザー ID
     * @return 可視なファイル一覧（名称昇順）
     */
    private List<SharedFileEntity> findVisibleFiles(SharedFolderEntity folder, Long userId) {
        Set<FileVisibilityRole> allowedLevels = folderAccessGuard.resolveVisibleFileLevels(folder, userId);
        if (allowedLevels == null) {
            return fileRepository.findByFolderIdOrderByNameAsc(folder.getId());
        }
        if (allowedLevels.isEmpty()) {
            return fileRepository.findByFolderIdAndMinVisibleRoleIsNullOrderByNameAsc(folder.getId());
        }
        return fileRepository.findVisibleByFolderIdAndLevels(folder.getId(), allowedLevels);
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
        // 認可根治 Wave7: parentId 指定時は親が同一スコープであることを検証する（接ぎ木封鎖）。
        checkParentWithinScope(request.getParentId(), FileScopeType.valueOf(scopeType), scopeId, userId);
        validateFolderNameUnique(request.getParentId(), request.getName());

        FileScopeType type = FileScopeType.valueOf(scopeType);
        var builder = SharedFolderEntity.builder()
                .scopeType(type)
                .parentId(request.getParentId())
                .name(request.getName())
                .description(request.getDescription())
                // B/C: 最低可視ロール・DL 禁止フラグ（未指定は NULL / false = 従来挙動）。
                .minVisibleRole(request.getMinVisibleRole())
                .downloadDisabled(Boolean.TRUE.equals(request.getDownloadDisabled()))
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
     * {@link SharedFolderService#deleteFolder} は (1) 認可がスコープ別に個別実装されていない、
     * (2) フォルダ単体しか soft-delete せず配下ファイル・サブフォルダが孤児化する、
     * (3) 容量戻しが無く {@code usedBytes} が減らない、という理由で流用しない。
     *（(1) は認可根治 Wave7 で {@code SharedFolderService#checkFolderManageAccess} により
     * 是正済み。(2)(3) は依然として本メソッドのみが満たすため、FE の汎用削除 EP は
     * 引き続き本メソッドを使う。）本メソッドは:</p>
     * <ul>
     *   <li><b>認可</b>: {@link #authorizeDelete} で「閲覧より強い」削除権限を当てる
     *       （他人個人=404・TEAM/ORG は<b>管理者(ADMIN/DEPUTY_ADMIN)のみ</b>・一般メンバーは 403・
     *       大会=編集認可）。IDOR / クロススコープ削除に加え、一般メンバーによる破壊操作も防ぐ。</li>
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
        // 削除は閲覧より強い権限を要求する（TEAM/ORG は管理者以上）→ authorizeDelete。
        authorizeDelete(root, userId);

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
        folderAccessGuard.authorizeView(folder, userId);
    }

    /**
     * ファイル ID からファイル → フォルダを解決し、ファイル単位の閲覧認可を当てる（B: 最低可視ロール対応）。
     *
     * <p>{@link SharedFileService#getFile} / {@link SharedFileService#presignDownload} から呼ぶ。
     * 認可は「①フォルダスコープの基本認可（PERSONAL 本人以外404 / TEAM・ORG 非メンバー403 / 大会 連絡スペース認可）
     * ②B: 最低可視ロール（ファイル値優先 → フォルダ継承）」の順で当てる。ファイル個別値が {@code NULL} なら
     * フォルダ値を継承し、フォルダも {@code NULL} なら判定スキップ（所属者全員可視＝従来挙動）。</p>
     *
     * @param fileId ファイル ID（不在は {@code FILE_NOT_FOUND} → 404）
     * @param userId 操作ユーザー ID（未認証は null）
     */
    public void authorizeFileViewById(Long fileId, Long userId) {
        SharedFileEntity file = findFileOrThrow(fileId);
        SharedFolderEntity folder = findFolderOrThrow(file.getFolderId());
        folderAccessGuard.authorizeFileView(folder, file, userId);
    }

    /**
     * ファイル単位の<b>ダウンロード認可</b>を当てる（B: 最低可視ロール ＋ C: DL 禁止フラグ）。
     *
     * <p>{@link SharedFileService#presignDownload} から呼ぶ。まず {@link #authorizeFileViewById} で閲覧認可
     * （B 含む）を通し、その後 C: DL 禁止フラグを評価する。実効禁止 = フォルダ.downloadDisabled OR
     * ファイル.downloadDisabled（禁止は単調・ファイルで解除不可）。{@code true} なら {@code DOWNLOAD_DISABLED}
     * （403）をスローし DL URL を発行させない。SYSTEM_ADMIN は B/C を貫通する（全可視・全 DL 可）。</p>
     *
     * <p><b>設計上の限界</b>: ブラウザ表示できる以上、完全な DL 防止は原理的に不可。本フラグは運用上の抑止に留まる。</p>
     *
     * @param fileId ファイル ID
     * @param userId 操作ユーザー ID
     */
    public void authorizeDownload(Long fileId, Long userId) {
        SharedFileEntity file = findFileOrThrow(fileId);
        SharedFolderEntity folder = findFolderOrThrow(file.getFolderId());
        folderAccessGuard.authorizeDownload(folder, file, userId);
    }

    /**
     * PR-D: ファイルの<b>公開リンク管理認可</b>（発行 / 一覧 / 削除）を当てる。
     *
     * <p>マスター確定仕様: 公開リンクは未認証・非会員にファイルを開く capability を配る強力な操作のため、
     * 発行・一覧・削除は<b>管理者（ADMIN / DEPUTY_ADMIN）限定</b>とする（一般 MEMBER は 403）。
     * これは削除認可 {@link #authorizeDelete} と同じ「閲覧より強い」権限で、fileId → folder を解決して当てる。
     * {@link SharedFileLinkService} からはこのメソッドを介して全スコープに一貫適用する。</p>
     *
     * <ul>
     *   <li>PERSONAL: 所有者本人のみ。他人は {@code FOLDER_NOT_FOUND}（404・存在隠蔽）。</li>
     *   <li>TEAM / ORGANIZATION: {@link AccessControlService#checkAdminOrAbove}（ADMIN / DEPUTY_ADMIN のみ・一般は 403）。</li>
     *   <li>TOURNAMENT / TOURNAMENT_DIVISION: 既存の編集認可（実質管理者）に委譲。</li>
     * </ul>
     *
     * @param fileId 対象ファイル ID
     * @param userId 操作ユーザー ID
     */
    public void authorizeLinkManageByFileId(Long fileId, Long userId) {
        SharedFileEntity file = findFileOrThrow(fileId);
        SharedFolderEntity folder = findFolderOrThrow(file.getFolderId());
        // 公開リンク管理は削除と同一の「閲覧より強い」権限（管理者 / 所有者限定）を要求する。
        folderAccessGuard.authorizeDelete(folder, userId);
    }

    /**
     * PR-D: 公開リンク経由 DL の <b>C: ダウンロード禁止フラグ（download_disabled）</b>のみを評価する。
     *
     * <p>公開リンクはトークンが capability のためフォルダスコープ認可（membership / role）は<b>通さない</b>が、
     * C: DL 禁止フラグ（実効 = フォルダ OR ファイル）は公開リンクでも<b>必ず</b>貫通防御する。
     * これにより「リンクで download_allowed=true にしても、ファイル/フォルダが DL 禁止なら DL 不可」
     * （C 優先の AND 評価）を保証する。禁止なら {@code DOWNLOAD_DISABLED}（403）。</p>
     *
     * @param fileId 対象ファイル ID
     */
    public void checkDownloadDisabledForSharedLink(Long fileId) {
        SharedFileEntity file = findFileOrThrow(fileId);
        SharedFolderEntity folder = findFolderOrThrow(file.getFolderId());
        folderAccessGuard.requireDownloadEnabled(folder, file);
    }

    /**
     * フォルダ実体のスコープに応じて閲覧認可を当てる（漏洩防止の核 ＋ B: フォルダ最低可視ロール）。
     */
    private void authorizeView(SharedFolderEntity folder, Long userId) {
        folderAccessGuard.authorizeView(folder, userId);
    }

    /**
     * B: 一覧経路（{@code list}）用に、ユーザーが満たす<b>ファイル最低可視ロールのレベル集合</b>を解決する。
     *
     * <p>{@link com.mannschaft.app.filesharing.service.SharedFileService#listFiles} /
     * {@code listFilesPaged} から、フォルダ閲覧認可（{@link #authorizeFolderViewById}）通過後に呼ぶ。
     * 返り値をリポジトリのクエリ段階の絞り込みに使うことで、フォルダより厳しいファイル個別 min role の
     * メタ（ファイル名等）が下位ロールの一覧に露出するのを防ぐ（ページング総件数も SQL 段階で整合）。</p>
     *
     * <p>返り値の意味:</p>
     * <ul>
     *   <li>{@code null} … <b>全許可</b>（フィルタ不要）。PERSONAL スコープ（所有者のみ＝基本認可で担保）
     *       または SYSTEM_ADMIN（B/C 貫通）。呼び出し側は従来の絞り無しクエリを使う。</li>
     *   <li>空集合 … 非 NULL レベルを 1 つも満たさない。呼び出し側は {@code min_visible_role IS NULL} の
     *       ファイルのみ返す。</li>
     *   <li>非空集合 … 満たす {@link FileVisibilityRole} 群。呼び出し側は {@code IS NULL} ＋ この集合で絞る。</li>
     * </ul>
     *
     * <p>NULL の {@code min_visible_role} を持つファイルは「フォルダ継承（フォルダ認可を通過した時点で可視）」
     * のため、この集合には含めず、呼び出し側クエリで常に返す（NULL ファイルを誤って隠さない）。</p>
     *
     * @param folder 対象フォルダ（スコープ解決に使う）
     * @param userId 操作ユーザー ID
     * @return 満たすレベル集合（全許可なら {@code null}）
     */
    public Set<FileVisibilityRole> resolveVisibleFileLevels(SharedFolderEntity folder, Long userId) {
        return folderAccessGuard.resolveVisibleFileLevels(folder, userId);
    }

    /**
     * B: {@link #resolveVisibleFileLevels(SharedFolderEntity, Long)} の folderId 受け口。
     * フォルダを読み込んでレベル集合を解決する（同一トランザクション内の一次キャッシュで再取得は DB 往復なし）。
     *
     * @param folderId フォルダ ID（不在は {@code FOLDER_NOT_FOUND} → 404）
     * @param userId   操作ユーザー ID
     * @return 満たすレベル集合（全許可なら {@code null}）
     */
    public Set<FileVisibilityRole> resolveVisibleFileLevels(Long folderId, Long userId) {
        return resolveVisibleFileLevels(findFolderOrThrow(folderId), userId);
    }

    /** ファイル実体を取得する。不在は {@code FILE_NOT_FOUND}（404）。 */
    private SharedFileEntity findFileOrThrow(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FILE_NOT_FOUND));
    }

    /**
     * フォルダ実体のスコープに応じて<b>削除認可</b>を当てる（閲覧より強い権限を要求）。
     *
     * <p>マスター御裁可: チーム/組織のフォルダ削除は配下ファイルごと消える破壊操作のため
     * <b>管理者（ADMIN / DEPUTY_ADMIN=副長）限定</b>とし、一般メンバー（MEMBER）は実行不可とする。
     * 個人フォルダは従来どおり本人のみ。{@link #authorizeView} との差分:</p>
     * <ul>
     *   <li>PERSONAL: 本人以外は {@code FOLDER_NOT_FOUND}（404・存在隠蔽）。view と同一。</li>
     *   <li>TEAM / ORGANIZATION: view は {@link AccessControlService#checkMembership}（メンバーなら可）だが、
     *       delete は {@link AccessControlService#checkAdminOrAbove} に引き上げる。
     *       {@code checkAdminOrAbove} は内部の {@code ADMIN_ROLES = {"ADMIN","DEPUTY_ADMIN"}} に基づき
     *       <b>ADMIN と DEPUTY_ADMIN の両方を許可</b>し、一般 MEMBER 以下は 403（COMMON_002）で弾く。</li>
     *   <li>TOURNAMENT / TOURNAMENT_DIVISION: 既存どおり編集認可
     *       {@link FolderScopeAccessGuard#checkFolderPostByFolderId}（実質管理者）に委譲。</li>
     * </ul>
     */
    private void authorizeDelete(SharedFolderEntity folder, Long userId) {
        folderAccessGuard.authorizeDelete(folder, userId);
    }

    /**
     * 親フォルダが作成先スコープと同一であることを検証する（認可根治 Wave7・接ぎ木封鎖）。
     *
     * <p>{@code parentId} が null（ルート直下）なら何もしない。非 null のとき、親が存在しない／
     * スコープ種別が違う／同種でもスコープ ID が違う場合はいずれも {@code FOLDER_NOT_FOUND}（404）とし、
     * 他スコープの folderId の存在有無を漏らさない。PERSONAL は操作者本人の所有であることを要求する。</p>
     *
     * @param parentId  リクエスト由来の親フォルダ ID（null 可）
     * @param type      作成先スコープ種別
     * @param scopeId   作成先スコープ ID（teamId / organizationId の文字列。PERSONAL では未使用）
     * @param userId    操作ユーザー ID（PERSONAL の所有者判定に使う）
     */
    private void checkParentWithinScope(Long parentId, FileScopeType type, String scopeId, Long userId) {
        if (parentId == null) {
            return;
        }
        SharedFolderEntity parent = findFolderOrThrow(parentId);
        Long expectedScopeId = switch (type) {
            case TEAM, ORGANIZATION -> parseScopeId(scopeId);
            case PERSONAL -> userId;
            case TOURNAMENT, TOURNAMENT_DIVISION -> null;
        };
        folderAccessGuard.requireParentWithinScope(parent, type, expectedScopeId);
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
                folder.getMinVisibleRole(),
                folder.getDownloadDisabled(),
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
                file.getMinVisibleRole(),
                file.getDownloadDisabled(),
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

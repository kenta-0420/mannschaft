package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.CreateFileRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.SharedFileDownloadUrlResponse;
import com.mannschaft.app.filesharing.dto.SharedFilePresignRequest;
import com.mannschaft.app.filesharing.dto.SharedFilePresignResponse;
import com.mannschaft.app.filesharing.dto.UpdateFileRequest;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 共有ファイルサービス。ファイルのCRUDを担当する。
 *
 * <p>F13 Phase 4-ε でアップロード前の {@link SharedFileQuotaService#checkFileQuota} 呼び出しと、
 * DB 登録完了後の {@link SharedFileQuotaService#recordFileUpload}、
 * 論理削除後の {@link SharedFileQuotaService#recordFileDeletion} を組み込んだ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFileService {

    /** F13 Phase 5-a: presigned URL 発行に使用。 */
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);

    /** ダウンロード用 Presigned GET URL の有効期限。 */
    private static final Duration PRESIGN_DOWNLOAD_TTL = Duration.ofMinutes(15);

    private final SharedFileRepository fileRepository;
    private final SharedFileVersionRepository versionRepository;
    private final FileSharingMapper fileSharingMapper;
    private final SharedFolderService folderService;
    private final SharedFileQuotaService quotaService;
    /** F13 Phase 5-a: R2 presigned URL 発行に使用。 */
    private final R2StorageService r2StorageService;
    /**
     * ダウンロード URL 発行時のスコープ別閲覧認可に使用。
     * ファイル → フォルダ → スコープの順に解決し、PERSONAL=本人以外404 / TEAM・ORG=非メンバー403 /
     * 大会=連絡スペース認可を当てる（fileId を渡すだけで他チーム・他人のファイルを落とせないこと）。
     */
    private final SharedFolderQueryService folderQueryService;
    /**
     * F08.7.1 / 04: 大会・ディビジョンスコープのフォルダ／ファイルに対する横断認可ゲート。
     * 大会以外（TEAM/ORG/PERSONAL）のスコープでは no-op（既存挙動を変えない）。
     */
    private final FolderScopeAccessGuard folderScopeAccessGuard;

    /**
     * ファイルアップロード用の Presigned PUT URL を発行する。
     *
     * <p><b>F13 Phase 5-a</b>: クライアントが fileKey を自前生成する代わりに、
     * サーバー側で新統一パス命名規則 {@code files/{scopeType}/{scopeId}/{uuid}.{ext}}
     * に従った fileKey を生成する。クライアントは返却された {@code uploadUrl} を使って
     * R2 に直接 PUT し、完了後に {@code fileKey} を {@code createFile} API に渡す。</p>
     *
     * @param folderId フォルダ ID（スコープ解決の基準）
     * @param actorId  操作者ユーザー ID
     * @param req      presign リクエスト
     * @return presign レスポンス（uploadUrl / fileKey / expiresInSeconds）
     */
    @Transactional(readOnly = true)
    public SharedFilePresignResponse presignUpload(Long folderId, Long actorId, SharedFilePresignRequest req) {
        // F08.7.1 / 04 §5: 大会フォルダはアップロード認可（チーム代表＋主催者）を通す。
        folderScopeAccessGuard.checkFolderPostByFolderId(folderId, actorId);
        // 1. フォルダ取得
        SharedFolderEntity folder = folderService.findFolderOrThrow(folderId);

        // 2. クォータ事前チェック
        long fileSize = req.fileSize() != null ? req.fileSize() : 0L;
        quotaService.checkFileQuota(folder, fileSize);

        // 3. スコープ解決（物理パス用 scopeId）
        // F08.7.1 §3.1: 大会/ディビジョンは scope_ref_id（tournament_id / division_id）を物理パスに使い、
        // 大会単位の容量内訳を可視化できるようにする（クォータ計量は §6 で主催組織に集約・別レイヤ）。
        FileScopeType fileScopeType = folder.getScopeType();
        String scopeTypeStr = fileScopeType.name(); // TEAM / ORGANIZATION / PERSONAL / TOURNAMENT(_DIVISION)
        Long scopeId = switch (fileScopeType) {
            case TEAM -> folder.getTeamId();
            case ORGANIZATION -> folder.getOrganizationId();
            case PERSONAL -> folder.getUserId();
            case TOURNAMENT, TOURNAMENT_DIVISION -> folder.getScopeRefId();
        };

        // 4. fileKey 生成: files/{scopeType}/{scopeId}/{uuid}.{ext}
        String ext = resolveExtension(req.contentType());
        String fileKey = "files/" + scopeTypeStr + "/" + scopeId + "/" + UUID.randomUUID() + "." + ext;

        // 5. presigned URL 発行
        PresignedUploadResult result = r2StorageService.generateUploadUrl(
                fileKey, req.contentType(), PRESIGN_TTL);

        log.info("ファイル共有 presign-upload 発行: folderId={}, actorId={}, scope={}/{}, fileKey={}",
                folderId, actorId, scopeTypeStr, scopeId, fileKey);

        return new SharedFilePresignResponse(result.uploadUrl(), fileKey, result.expiresInSeconds());
    }

    /**
     * ファイルダウンロード用の Presigned GET URL を発行する。
     *
     * <p><b>根治</b>: FE {@code useFileSharingApi.getDownloadUrl(fileId)} が叩く
     * {@code GET /api/v1/files/{fileId}/download-url} に対応する EP がこれまで存在せず、
     * 非存在ルート → NoResourceFound → catch-all で 500 になっていた。本メソッドで実装する。</p>
     *
     * <p><b>認可（漏洩防止の核）</b>: file → folder を解決し、
     * {@link SharedFolderQueryService#authorizeFolderViewById} でフォルダスコープ別の閲覧認可を当てる。
     * PERSONAL は所有者本人以外 404（存在隠蔽）・TEAM/ORG は非メンバー 403・大会は連絡スペース認可。
     * fileId を渡すだけで他チーム・他人のファイルを落とせないことを保証する。
     * 認可を通過した場合のみ R2 Presigned GET URL を発行する。</p>
     *
     * @param fileId  ファイル ID
     * @param actorId 操作者ユーザー ID（未認証は呼び出し元 Controller で 401 となるため非 null 想定）
     * @return ダウンロード URL レスポンス（downloadUrl / expiresInSeconds）
     */
    public SharedFileDownloadUrlResponse presignDownload(Long fileId, Long actorId) {
        // 1. ファイル取得（存在しなければ FILE_NOT_FOUND → 404）
        SharedFileEntity file = findFileOrThrow(fileId);

        // 2. 認可: file → folder → スコープ別閲覧認可（PERSONAL 404 / TEAM・ORG 403 / 大会 連絡スペース認可）
        //    認可が通らなければここで例外が飛び、URL は一切発行されない（漏洩防止）。
        folderQueryService.authorizeFolderViewById(file.getFolderId(), actorId);

        // 3. R2 Presigned GET URL 発行
        String downloadUrl = r2StorageService.generateDownloadUrl(file.getFileKey(), PRESIGN_DOWNLOAD_TTL);

        log.info("ファイル共有 download-url 発行: fileId={}, actorId={}, fileKey={}",
                fileId, actorId, file.getFileKey());

        return new SharedFileDownloadUrlResponse(downloadUrl, PRESIGN_DOWNLOAD_TTL.toSeconds());
    }

    /**
     * フォルダ内のファイル一覧を取得する。
     *
     * <p><b>IDOR 封鎖（情報漏洩根治）</b>: 先頭で {@link SharedFolderQueryService#authorizeFolderViewById}
     * を通し、フォルダスコープ別の閲覧認可を当てる。従来は {@link FolderScopeAccessGuard} のみを呼んでおり、
     * 大会以外（TEAM / ORGANIZATION / PERSONAL）では認可が no-op（素通り）だったため、folderId を渡す
     * だけで他チーム・他人のファイルメタが取得できた。QueryService へ一本化することで、
     * PERSONAL=本人以外404（存在隠蔽）/ TEAM・ORG=非メンバー403 / 大会=連絡スペース認可（guard 委譲）を
     * 適用する。</p>
     *
     * @param folderId フォルダID
     * @param userId   操作ユーザーID（未認証は呼び出し元 Controller で 401 となるため非 null 想定）
     * @return ファイルレスポンスリスト
     */
    public List<FileResponse> listFiles(Long folderId, Long userId) {
        // IDOR 封鎖: フォルダスコープ別の閲覧認可（PERSONAL 本人以外404 / TEAM・ORG 非メンバー403 / 大会 連絡スペース認可）。
        // authorizeFolderViewById は内部で大会スコープを FolderScopeAccessGuard へ委譲するため、大会の従来挙動は不変。
        folderQueryService.authorizeFolderViewById(folderId, userId);
        List<SharedFileEntity> files = fileRepository.findByFolderIdOrderByNameAsc(folderId);
        return fileSharingMapper.toFileResponseList(files);
    }

    /**
     * フォルダ内のファイル一覧をページングで取得する。
     *
     * <p><b>IDOR 封鎖（情報漏洩根治）</b>: {@link #listFiles} と同じくフォルダスコープ別の閲覧認可を
     * 先頭で通す（folderId を渡すだけで他チーム・他人のファイルメタを列挙できないことを保証する）。</p>
     *
     * @param folderId フォルダID
     * @param userId   操作ユーザーID
     * @param pageable ページング情報
     * @return ファイルレスポンスのページ
     */
    public Page<FileResponse> listFilesPaged(Long folderId, Long userId, Pageable pageable) {
        // IDOR 封鎖: フォルダスコープ別の閲覧認可（PERSONAL 本人以外404 / TEAM・ORG 非メンバー403 / 大会 連絡スペース認可）。
        folderQueryService.authorizeFolderViewById(folderId, userId);
        Page<SharedFileEntity> page = fileRepository.findByFolderIdOrderByNameAsc(folderId, pageable);
        return page.map(fileSharingMapper::toFileResponse);
    }

    /**
     * ファイル詳細を取得する。
     *
     * <p><b>IDOR 封鎖（情報漏洩根治）</b>: まず {@link #findFileOrThrow} でファイル実在を確認（不在は 404）し、
     * 次に解決した folderId で {@link SharedFolderQueryService#authorizeFolderViewById} を通す。順序は
     * 「fileId 実在確認（404）→ フォルダ認可（TEAM/ORG 非メンバー403 / 他人 PERSONAL 404）」を保ち、存在秘匿の
     * 一貫性を担保する。従来は {@link FolderScopeAccessGuard} のみで大会以外が素通りしていた漏洩の根治。</p>
     *
     * @param fileId ファイルID
     * @param userId 操作ユーザーID
     * @return ファイルレスポンス
     */
    public FileResponse getFile(Long fileId, Long userId) {
        // 1. fileId 実在確認（不在は FILE_NOT_FOUND → 404）。
        SharedFileEntity entity = findFileOrThrow(fileId);
        // 2. file → folder を解決し、フォルダスコープ別の閲覧認可を当てる（IDOR 封鎖）。
        folderQueryService.authorizeFolderViewById(entity.getFolderId(), userId);
        return fileSharingMapper.toFileResponse(entity);
    }

    /**
     * 共有リンク経由でファイル詳細を取得する（フォルダスコープ認可を <b>通さない</b>内部用）。
     *
     * <p>共有リンクはトークン自体が capability（所持と任意のパスワードで閲覧可）であり、
     * 正当に発行されたリンクからの取得はフォルダスコープ認可の対象外とする。
     * {@link SharedFileLinkService#accessLink} からのみ呼ぶこと。</p>
     *
     * @param fileId ファイル ID
     * @return ファイルレスポンス
     */
    public FileResponse getFileForSharedLink(Long fileId) {
        SharedFileEntity entity = findFileOrThrow(fileId);
        return fileSharingMapper.toFileResponse(entity);
    }

    /**
     * ファイルを作成する。
     *
     * <p>F13 Phase 4-ε: DB 登録前に {@link SharedFileQuotaService#checkFileQuota} でクォータを確認し、
     * 登録完了後に {@link SharedFileQuotaService#recordFileUpload} で使用量を加算する。</p>
     *
     * @param userId  作成者ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたファイルレスポンス
     */
    @Transactional
    public FileResponse createFile(Long userId, CreateFileRequest request) {
        // F08.7.1 / 04 §5: 大会フォルダはアップロード認可を通す。
        folderScopeAccessGuard.checkFolderPostByFolderId(request.getFolderId(), userId);
        // F13 Phase 4-ε: クォータ事前チェック
        SharedFolderEntity folder = folderService.findFolderOrThrow(request.getFolderId());
        long fileSize = request.getFileSize() != null ? request.getFileSize() : 0L;
        quotaService.checkFileQuota(folder, fileSize);

        SharedFileEntity entity = SharedFileEntity.builder()
                .folderId(request.getFolderId())
                .name(request.getName())
                .fileKey(request.getFileKey())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .description(request.getDescription())
                .createdBy(userId)
                .build();

        SharedFileEntity saved = fileRepository.save(entity);

        SharedFileVersionEntity version = SharedFileVersionEntity.builder()
                .fileId(saved.getId())
                .versionNumber(1)
                .fileKey(request.getFileKey())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .uploadedBy(userId)
                .comment("初回アップロード")
                .build();
        versionRepository.save(version);

        // F13 Phase 4-ε: 使用量加算
        quotaService.recordFileUpload(folder, saved.getId(), fileSize, userId);

        log.info("ファイル作成: fileId={}, folderId={}", saved.getId(), request.getFolderId());
        return fileSharingMapper.toFileResponse(saved);
    }

    /**
     * ファイルを更新する。
     *
     * @param fileId  ファイルID
     * @param request 更新リクエスト
     * @return 更新されたファイルレスポンス
     */
    @Transactional
    public FileResponse updateFile(Long fileId, UpdateFileRequest request) {
        // F08.7.1 / 04 §5: 大会フォルダ配下のファイル更新は編集認可を通す。
        Long actorId = SecurityUtils.getCurrentUserIdOrNull();
        folderScopeAccessGuard.checkFolderPostByFileId(fileId, actorId);
        // 別フォルダへ移動する場合は移動先フォルダの投稿認可も通す（大会フォルダへの持ち込み防止）。
        if (request.getFolderId() != null) {
            folderScopeAccessGuard.checkFolderPostByFolderId(request.getFolderId(), actorId);
        }
        SharedFileEntity entity = findFileOrThrow(fileId);

        if (request.getName() != null) {
            entity.changeName(request.getName());
        }
        if (request.getDescription() != null) {
            entity.changeDescription(request.getDescription());
        }
        if (request.getFolderId() != null) {
            entity.moveToFolder(request.getFolderId());
        }

        SharedFileEntity saved = fileRepository.save(entity);
        log.info("ファイル更新: fileId={}", fileId);
        return fileSharingMapper.toFileResponse(saved);
    }

    /**
     * ファイルを論理削除する。
     *
     * <p>F13 Phase 4-ε: 論理削除後に {@link SharedFileQuotaService#recordFileDeletion} で
     * 使用量を減算する。削除者ユーザーIDは呼び出し元が保持するため、ここでは SecurityUtils
     * から取得する（SharedFileController と同じ方法）。</p>
     *
     * @param fileId  ファイルID
     * @param actorId 操作者ユーザーID
     */
    @Transactional
    public void deleteFile(Long fileId, Long actorId) {
        // F08.7.1 / 04 §5: 大会フォルダ配下のファイル削除は編集認可を通す。
        folderScopeAccessGuard.checkFolderPostByFileId(fileId, actorId);
        SharedFileEntity entity = findFileOrThrow(fileId);
        long fileSize = entity.getFileSize() != null ? entity.getFileSize() : 0L;

        // フォルダ情報を取得してスコープを解決する
        SharedFolderEntity folder = folderService.findFolderOrThrow(entity.getFolderId());

        entity.softDelete();
        fileRepository.save(entity);

        // F13 Phase 4-ε: 使用量減算
        quotaService.recordFileDeletion(folder, fileId, fileSize, actorId);

        log.info("ファイル削除: fileId={}", fileId);
    }

    /**
     * ファイルを取得する。存在しない場合は例外をスローする。
     */
    public SharedFileEntity findFileOrThrow(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FILE_NOT_FOUND));
    }

    /**
     * Content-Type から拡張子を解決する。
     *
     * @param contentType MIME タイプ
     * @return 拡張子（ドットなし）
     */
    private String resolveExtension(String contentType) {
        if (contentType == null) return "bin";
        return switch (contentType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/heic" -> "heic";
            case "application/pdf" -> "pdf";
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            case "video/quicktime" -> "mov";
            case "application/zip" -> "zip";
            case "application/x-tar" -> "tar";
            case "application/gzip" -> "gz";
            case "text/plain" -> "txt";
            case "text/csv" -> "csv";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            default -> "bin";
        };
    }
}

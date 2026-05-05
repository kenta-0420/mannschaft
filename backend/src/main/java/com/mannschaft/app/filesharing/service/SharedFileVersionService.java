package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.CreateVersionRequest;
import com.mannschaft.app.filesharing.dto.FileVersionResponse;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ファイルバージョンサービス。ファイルのバージョン管理を担当する。
 *
 * <p>F13 Phase 4-ε でバージョン追加時の {@link SharedFileQuotaService#checkFileQuota} 呼び出しと、
 * 登録完了後の {@link SharedFileQuotaService#recordVersionUpload} を組み込んだ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFileVersionService {

    private final SharedFileVersionRepository versionRepository;
    private final SharedFileService fileService;
    private final SharedFolderService folderService;
    private final FileSharingMapper fileSharingMapper;
    private final SharedFileQuotaService quotaService;

    /**
     * ファイルの全バージョンを取得する。
     *
     * @param fileId ファイルID
     * @return バージョンレスポンスリスト
     */
    public List<FileVersionResponse> listVersions(Long fileId) {
        List<SharedFileVersionEntity> versions = versionRepository.findByFileIdOrderByVersionNumberDesc(fileId);
        return fileSharingMapper.toVersionResponseList(versions);
    }

    /**
     * ファイルの特定バージョンを取得する。
     *
     * @param fileId        ファイルID
     * @param versionNumber バージョン番号
     * @return バージョンレスポンス
     */
    public FileVersionResponse getVersion(Long fileId, Integer versionNumber) {
        SharedFileVersionEntity entity = versionRepository.findByFileIdAndVersionNumber(fileId, versionNumber)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.VERSION_NOT_FOUND));
        return fileSharingMapper.toVersionResponse(entity);
    }

    /**
     * 新しいバージョンをアップロードする。
     *
     * <p>F13 Phase 4-ε: DB 登録前に {@link SharedFileQuotaService#checkFileQuota} でクォータを確認し、
     * 登録完了後に {@link SharedFileQuotaService#recordVersionUpload} で使用量を加算する。</p>
     *
     * @param fileId  ファイルID
     * @param userId  アップロードユーザーID
     * @param request バージョン作成リクエスト
     * @return 作成されたバージョンレスポンス
     */
    @Transactional
    public FileVersionResponse createVersion(Long fileId, Long userId, CreateVersionRequest request) {
        SharedFileEntity fileEntity = fileService.findFileOrThrow(fileId);

        // F13 Phase 4-ε: クォータ事前チェック（フォルダからスコープを解決）
        SharedFolderEntity folder = folderService.findFolderOrThrow(fileEntity.getFolderId());
        long fileSize = request.getFileSize() != null ? request.getFileSize() : 0L;
        quotaService.checkFileQuota(folder, fileSize);

        int nextVersion = fileEntity.getCurrentVersion() + 1;

        SharedFileVersionEntity version = SharedFileVersionEntity.builder()
                .fileId(fileId)
                .versionNumber(nextVersion)
                .fileKey(request.getFileKey())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .uploadedBy(userId)
                .comment(request.getComment())
                .build();

        SharedFileVersionEntity saved = versionRepository.save(version);

        fileEntity.updateToNewVersion(
                request.getFileKey(), request.getFileSize(), request.getContentType(), nextVersion);
        // fileEntity はトランザクション内で dirty checking により自動保存される

        // F13 Phase 4-ε: 使用量加算
        quotaService.recordVersionUpload(folder, saved.getId(), fileSize, userId);

        log.info("ファイルバージョン作成: fileId={}, version={}", fileId, nextVersion);
        return fileSharingMapper.toVersionResponse(saved);
    }
}

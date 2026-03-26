package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.CreateFileRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.UpdateFileRequest;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 共有ファイルサービス。ファイルのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFileService {

    private final SharedFileRepository fileRepository;
    private final SharedFileVersionRepository versionRepository;
    private final FileSharingMapper fileSharingMapper;

    /**
     * フォルダ内のファイル一覧を取得する。
     *
     * @param folderId フォルダID
     * @return ファイルレスポンスリスト
     */
    public List<FileResponse> listFiles(Long folderId) {
        List<SharedFileEntity> files = fileRepository.findByFolderIdOrderByNameAsc(folderId);
        return fileSharingMapper.toFileResponseList(files);
    }

    /**
     * フォルダ内のファイル一覧をページングで取得する。
     *
     * @param folderId フォルダID
     * @param pageable ページング情報
     * @return ファイルレスポンスのページ
     */
    public Page<FileResponse> listFilesPaged(Long folderId, Pageable pageable) {
        Page<SharedFileEntity> page = fileRepository.findByFolderIdOrderByNameAsc(folderId, pageable);
        return page.map(fileSharingMapper::toFileResponse);
    }

    /**
     * ファイル詳細を取得する。
     *
     * @param fileId ファイルID
     * @return ファイルレスポンス
     */
    public FileResponse getFile(Long fileId) {
        SharedFileEntity entity = findFileOrThrow(fileId);
        return fileSharingMapper.toFileResponse(entity);
    }

    /**
     * ファイルを作成する。
     *
     * @param userId  作成者ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたファイルレスポンス
     */
    @Transactional
    public FileResponse createFile(Long userId, CreateFileRequest request) {
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
     * @param fileId ファイルID
     */
    @Transactional
    public void deleteFile(Long fileId) {
        SharedFileEntity entity = findFileOrThrow(fileId);
        entity.softDelete();
        fileRepository.save(entity);
        log.info("ファイル削除: fileId={}", fileId);
    }

    /**
     * ファイルを取得する。存在しない場合は例外をスローする。
     */
    public SharedFileEntity findFileOrThrow(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FILE_NOT_FOUND));
    }
}

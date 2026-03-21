package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileSharingMapper;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.dto.UpdateFolderRequest;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 共有フォルダサービス。フォルダのCRUDと階層管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SharedFolderService {

    private final SharedFolderRepository folderRepository;
    private final FileSharingMapper fileSharingMapper;

    /**
     * チームのルートフォルダ一覧を取得する。
     *
     * @param teamId チームID
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listTeamRootFolders(Long teamId) {
        List<SharedFolderEntity> folders = folderRepository.findByTeamIdAndParentIdIsNullOrderByNameAsc(teamId);
        return fileSharingMapper.toFolderResponseList(folders);
    }

    /**
     * 組織のルートフォルダ一覧を取得する。
     *
     * @param organizationId 組織ID
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listOrgRootFolders(Long organizationId) {
        List<SharedFolderEntity> folders = folderRepository.findByOrganizationIdAndParentIdIsNullOrderByNameAsc(organizationId);
        return fileSharingMapper.toFolderResponseList(folders);
    }

    /**
     * 個人のルートフォルダ一覧を取得する。
     *
     * @param userId ユーザーID
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listPersonalRootFolders(Long userId) {
        List<SharedFolderEntity> folders = folderRepository.findByUserIdAndScopeTypeAndParentIdIsNullOrderByNameAsc(
                userId, FileScopeType.PERSONAL);
        return fileSharingMapper.toFolderResponseList(folders);
    }

    /**
     * 子フォルダ一覧を取得する。
     *
     * @param folderId 親フォルダID
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listChildFolders(Long folderId) {
        List<SharedFolderEntity> folders = folderRepository.findByParentIdOrderByNameAsc(folderId);
        return fileSharingMapper.toFolderResponseList(folders);
    }

    /**
     * フォルダ詳細を取得する。
     *
     * @param folderId フォルダID
     * @return フォルダレスポンス
     */
    public FolderResponse getFolder(Long folderId) {
        SharedFolderEntity entity = findFolderOrThrow(folderId);
        return fileSharingMapper.toFolderResponse(entity);
    }

    /**
     * チーム用フォルダを作成する。
     *
     * @param teamId  チームID
     * @param userId  作成者ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createTeamFolder(Long teamId, Long userId, CreateFolderRequest request) {
        validateFolderNameUnique(request.getParentId(), request.getName());

        SharedFolderEntity entity = SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM)
                .teamId(teamId)
                .parentId(request.getParentId())
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(userId)
                .build();

        SharedFolderEntity saved = folderRepository.save(entity);
        log.info("チームフォルダ作成: teamId={}, folderId={}", teamId, saved.getId());
        return fileSharingMapper.toFolderResponse(saved);
    }

    /**
     * 組織用フォルダを作成する。
     *
     * @param organizationId 組織ID
     * @param userId         作成者ユーザーID
     * @param request        作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createOrgFolder(Long organizationId, Long userId, CreateFolderRequest request) {
        validateFolderNameUnique(request.getParentId(), request.getName());

        SharedFolderEntity entity = SharedFolderEntity.builder()
                .scopeType(FileScopeType.ORGANIZATION)
                .organizationId(organizationId)
                .parentId(request.getParentId())
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(userId)
                .build();

        SharedFolderEntity saved = folderRepository.save(entity);
        log.info("組織フォルダ作成: organizationId={}, folderId={}", organizationId, saved.getId());
        return fileSharingMapper.toFolderResponse(saved);
    }

    /**
     * 個人フォルダを作成する。
     *
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createPersonalFolder(Long userId, CreateFolderRequest request) {
        validateFolderNameUnique(request.getParentId(), request.getName());

        SharedFolderEntity entity = SharedFolderEntity.builder()
                .scopeType(FileScopeType.PERSONAL)
                .userId(userId)
                .parentId(request.getParentId())
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(userId)
                .build();

        SharedFolderEntity saved = folderRepository.save(entity);
        log.info("個人フォルダ作成: userId={}, folderId={}", userId, saved.getId());
        return fileSharingMapper.toFolderResponse(saved);
    }

    /**
     * フォルダを更新する。
     *
     * @param folderId フォルダID
     * @param request  更新リクエスト
     * @return 更新されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse updateFolder(Long folderId, UpdateFolderRequest request) {
        SharedFolderEntity entity = findFolderOrThrow(folderId);

        if (request.getName() != null) {
            entity.changeName(request.getName());
        }
        if (request.getDescription() != null) {
            entity.changeDescription(request.getDescription());
        }
        if (request.getParentId() != null) {
            entity.moveToParent(request.getParentId());
        }

        SharedFolderEntity saved = folderRepository.save(entity);
        log.info("フォルダ更新: folderId={}", folderId);
        return fileSharingMapper.toFolderResponse(saved);
    }

    /**
     * フォルダを論理削除する。
     *
     * @param folderId フォルダID
     */
    @Transactional
    public void deleteFolder(Long folderId) {
        SharedFolderEntity entity = findFolderOrThrow(folderId);
        entity.softDelete();
        folderRepository.save(entity);
        log.info("フォルダ削除: folderId={}", folderId);
    }

    /**
     * フォルダを取得する。存在しない場合は例外をスローする。
     */
    SharedFolderEntity findFolderOrThrow(Long folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND));
    }

    /**
     * 同一親配下のフォルダ名重複をチェックする。
     */
    private void validateFolderNameUnique(Long parentId, String name) {
        if (folderRepository.existsByParentIdAndName(parentId, name)) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NAME_DUPLICATE);
        }
    }
}

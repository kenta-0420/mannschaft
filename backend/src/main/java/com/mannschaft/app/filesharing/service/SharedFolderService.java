package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
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
import org.springframework.dao.DataIntegrityViolationException;
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
     * F08.7.1 / 04: 大会・ディビジョンスコープのフォルダに対する横断認可ゲート。
     * 大会以外（TEAM/ORG/PERSONAL）のスコープでは no-op（既存挙動を変えない）。
     */
    private final FolderScopeAccessGuard folderScopeAccessGuard;

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
        // F08.7.1 / 04 §3: 大会フォルダ配下は閲覧認可を通す（親フォルダが大会スコープなら子も同スコープ）。
        folderScopeAccessGuard.checkFolderViewByFolderId(folderId, SecurityUtils.getCurrentUserIdOrNull());
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
        // F08.7.1 / 04 §3: 大会フォルダは閲覧認可を通す。
        folderScopeAccessGuard.checkFolderViewByFolderId(folderId, SecurityUtils.getCurrentUserIdOrNull());
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
                .minVisibleRole(request.getMinVisibleRole())
                .downloadDisabled(Boolean.TRUE.equals(request.getDownloadDisabled()))
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
                .minVisibleRole(request.getMinVisibleRole())
                .downloadDisabled(Boolean.TRUE.equals(request.getDownloadDisabled()))
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
                .minVisibleRole(request.getMinVisibleRole())
                .downloadDisabled(Boolean.TRUE.equals(request.getDownloadDisabled()))
                .createdBy(userId)
                .build();

        SharedFolderEntity saved = folderRepository.save(entity);
        log.info("個人フォルダ作成: userId={}, folderId={}", userId, saved.getId());
        return fileSharingMapper.toFolderResponse(saved);
    }

    /**
     * F08.7.1: 大会／ディビジョンスコープのルートフォルダ一覧を取得する。
     *
     * @param scopeType  フォルダスコープ種別（TOURNAMENT / TOURNAMENT_DIVISION）
     * @param scopeRefId 大会 ID / ディビジョン ID
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listTournamentScopedRootFolders(FileScopeType scopeType, Long scopeRefId) {
        List<SharedFolderEntity> folders =
                folderRepository.findByScopeTypeAndScopeRefIdAndParentIdIsNullOrderByNameAsc(scopeType, scopeRefId);
        return fileSharingMapper.toFolderResponseList(folders);
    }

    /**
     * F08.7.1: 大会／ディビジョンスコープのフォルダを作成する（設計書 §2.1 / §3）。
     *
     * <p>クォータ帰属は主催組織に集約するため {@code organizationId}（主催組織）を保持し、
     * 大会／ディビジョンの実 ID は {@code scopeRefId} に保持する。</p>
     *
     * @param scopeType      フォルダスコープ種別（TOURNAMENT / TOURNAMENT_DIVISION）
     * @param organizationId 主催組織 ID（クォータ帰属）
     * @param scopeRefId     大会 ID / ディビジョン ID
     * @param userId         作成者ユーザー ID
     * @param request        作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createTournamentScopedFolder(FileScopeType scopeType, Long organizationId,
                                                       Long scopeRefId, Long userId, CreateFolderRequest request) {
        validateFolderNameUnique(request.getParentId(), request.getName());

        SharedFolderEntity entity = SharedFolderEntity.builder()
                .scopeType(scopeType)
                .organizationId(organizationId)
                .scopeRefId(scopeRefId)
                .parentId(request.getParentId())
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(userId)
                .build();

        SharedFolderEntity saved = folderRepository.save(entity);
        log.info("大会フォルダ作成: scopeType={}, orgId={}, scopeRefId={}, folderId={}",
                scopeType, organizationId, scopeRefId, saved.getId());
        return fileSharingMapper.toFolderResponse(saved);
    }

    /**
     * F08.7.1: 大会／ディビジョン作成時のデフォルトフォルダ自動付帯（冪等・設計書 §4）。
     *
     * <p>{@code (scope_type, scope_ref_id, parent_id=NULL, name)} の組で既存チェックし、
     * なければ作成する。同時実行で UNIQUE 相当の競合が起きても {@link DataIntegrityViolationException}
     * を catch して再取得し、巻き添えで大会作成全体を失敗させない。</p>
     *
     * @param scopeType      フォルダスコープ種別
     * @param organizationId 主催組織 ID
     * @param scopeRefId     大会 ID / ディビジョン ID
     * @param userId         作成者（主催者）ユーザー ID
     * @param name           デフォルトフォルダ名（例: 「大会要項」「規約」）
     */
    @Transactional
    public void provisionDefaultFolder(FileScopeType scopeType, Long organizationId,
                                       Long scopeRefId, Long userId, String name) {
        if (folderRepository
                .findByScopeTypeAndScopeRefIdAndParentIdIsNullAndName(scopeType, scopeRefId, name)
                .isPresent()) {
            log.debug("大会デフォルトフォルダ既存: scopeType={}, scopeRefId={}, name={}", scopeType, scopeRefId, name);
            return;
        }
        try {
            SharedFolderEntity entity = SharedFolderEntity.builder()
                    .scopeType(scopeType)
                    .organizationId(organizationId)
                    .scopeRefId(scopeRefId)
                    .parentId(null)
                    .name(name)
                    .createdBy(userId)
                    .build();
            folderRepository.save(entity);
            log.info("大会デフォルトフォルダ払い出し: scopeType={}, orgId={}, scopeRefId={}, name={}",
                    scopeType, organizationId, scopeRefId, name);
        } catch (DataIntegrityViolationException e) {
            // 同時実行で重複作成された場合は再取得（冪等・連絡スペース provision と同方針）。
            log.warn("大会デフォルトフォルダ払い出し競合（再取得）: scopeType={}, scopeRefId={}, name={}",
                    scopeType, scopeRefId, name);
            folderRepository
                    .findByScopeTypeAndScopeRefIdAndParentIdIsNullAndName(scopeType, scopeRefId, name)
                    .orElseThrow(() -> e);
        }
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
        // F08.7.1 / 04 §5: 大会フォルダの更新は編集認可を通す。
        folderScopeAccessGuard.checkFolderPostByFolderId(folderId, SecurityUtils.getCurrentUserIdOrNull());
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
        // B/C: 指定時のみ更新（PATCH 意味論。未指定は現状維持）。
        if (request.getMinVisibleRole() != null) {
            entity.changeMinVisibleRole(request.getMinVisibleRole());
        }
        if (request.getDownloadDisabled() != null) {
            entity.changeDownloadDisabled(request.getDownloadDisabled());
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
        // F08.7.1 / 04 §5: 大会フォルダの削除は編集認可を通す。
        folderScopeAccessGuard.checkFolderPostByFolderId(folderId, SecurityUtils.getCurrentUserIdOrNull());
        SharedFolderEntity entity = findFolderOrThrow(folderId);
        entity.softDelete();
        folderRepository.save(entity);
        log.info("フォルダ削除: folderId={}", folderId);
    }

    /**
     * フォルダを取得する。存在しない場合は例外をスローする。
     */
    public SharedFolderEntity findFolderOrThrow(Long folderId) {
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

package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link FolderScopeAccessGuard} の tournament ドメイン側実装（F08.7.1 / 04 §3 / §5）。
 *
 * <p>filesharing ドメインが宣言した認可ポートを、連絡スペース（BULLETIN）の
 * {@link TournamentContactAccessService#checkView} / {@link TournamentContactAccessService#checkPost}
 * を流用して満たす。依存方向は tournament → filesharing（依存性逆転）であり、循環依存を生まない。</p>
 *
 * <p><b>循環依存回避</b>: 本ガードは filesharing の <em>Service</em>（{@code SharedFolderService} /
 * {@code SharedFileService}）ではなく <em>Repository</em> に直接依存する。Service 側がこのガードを
 * inject するため、Service ↔ ガードの相互依存（循環）を避ける必要があるためである。</p>
 *
 * <p>フォルダのスコープが大会／ディビジョン<b>以外</b>（TEAM / ORGANIZATION / PERSONAL）の場合は
 * 何もしない（既存 F05.5 の挙動を保つ。フラット認可是正は別 Issue）。大会／ディビジョンの場合のみ、
 * フォルダの {@code scope_ref_id}（= 大会 ID / ディビジョン ID）を解決し、連絡スペース認可を通す。</p>
 *
 * <p>ファイル ID 経路は fileId → folderId → folder へと辿り、その後フォルダ経路と同一の判定を行う。
 * 存在しないファイル／フォルダは一律 404、連絡スペース不在・大会／ディビジョン不在も
 * {@link TournamentContactAccessService} 側で 404（IDOR 対策・存在を漏らさない）。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/04_file_storage.md §3 / §5</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentFolderScopeAccessGuard implements FolderScopeAccessGuard {

    private final SharedFolderRepository folderRepository;
    private final SharedFileRepository fileRepository;
    private final TournamentContactAccessService accessService;

    @Override
    public void checkFolderViewByFolderId(Long folderId, Long userId) {
        checkView(resolveFolderOrThrow(folderId), userId);
    }

    @Override
    public void checkFolderPostByFolderId(Long folderId, Long userId) {
        checkPost(resolveFolderOrThrow(folderId), userId);
    }

    @Override
    public void checkFolderViewByFileId(Long fileId, Long userId) {
        checkView(resolveFolderOfFile(fileId), userId);
    }

    @Override
    public void checkFolderPostByFileId(Long fileId, Long userId) {
        checkPost(resolveFolderOfFile(fileId), userId);
    }

    // ========================================================================
    // 内部ヘルパー
    // ========================================================================

    private SharedFolderEntity resolveFolderOrThrow(Long folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND));
    }

    private SharedFolderEntity resolveFolderOfFile(Long fileId) {
        // ファイル不在は 404、その後フォルダ不在も 404（存在を漏らさない）。
        SharedFileEntity file = fileRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FILE_NOT_FOUND));
        return resolveFolderOrThrow(file.getFolderId());
    }

    /**
     * フォルダが大会／ディビジョンスコープなら閲覧認可を通す。それ以外は何もしない。
     */
    private void checkView(SharedFolderEntity folder, Long userId) {
        ContactSpaceScopeType scopeType = toContactScopeType(folder.getScopeType());
        if (scopeType == null) {
            return; // TEAM / ORGANIZATION / PERSONAL は既存挙動のまま（本ガード対象外）
        }
        accessService.checkView(scopeType, folder.getScopeRefId(), ContactSpaceKind.BULLETIN, userId);
    }

    /**
     * フォルダが大会／ディビジョンスコープならアップロード／編集認可を通す。それ以外は何もしない。
     */
    private void checkPost(SharedFolderEntity folder, Long userId) {
        ContactSpaceScopeType scopeType = toContactScopeType(folder.getScopeType());
        if (scopeType == null) {
            return;
        }
        accessService.checkPost(scopeType, folder.getScopeRefId(), userId);
    }

    /**
     * filesharing の {@link FileScopeType} を連絡スペースの {@link ContactSpaceScopeType} に対応付ける。
     * 大会／ディビジョン以外（本ガード対象外）は null を返す。
     */
    private ContactSpaceScopeType toContactScopeType(FileScopeType scopeType) {
        return switch (scopeType) {
            case TOURNAMENT -> ContactSpaceScopeType.TOURNAMENT;
            case TOURNAMENT_DIVISION -> ContactSpaceScopeType.TOURNAMENT_DIVISION;
            case TEAM, ORGANIZATION, PERSONAL -> null;
        };
    }
}

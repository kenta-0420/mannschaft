package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.AccessControlService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 共有フォルダサービス。フォルダのCRUDと階層管理を担当する。
 *
 * <p><b>認可（認可根治 Wave7 — F05.5 フラット認可の是正）:</b> 従来、本サービスは
 * {@link AccessControlService} を<b>一切参照しておらず</b>、唯一の依存ガード
 * {@link FolderScopeAccessGuard} は大会／ディビジョン以外（TEAM / ORGANIZATION / PERSONAL）で
 * no-op（素通り）だった。そのため folderId / teamId / organizationId を渡すだけで
 * 任意のチーム・組織のフォルダ階層を閲覧・作成・更新・削除できた。</p>
 *
 * <p>本改修で、同一ドメインで既に根治済みの
 * {@code SharedFolderQueryService#authorizeView} / {@code #authorizeDelete}（および
 * {@code SharedFileService} が通す {@code authorizeFolderViewById}）と<b>同一のポリシー</b>を
 * 本サービスにも敷設した:</p>
 * <ul>
 *   <li><b>PERSONAL</b>: 所有者本人のみ。他人は {@code FOLDER_NOT_FOUND}（404・存在秘匿）</li>
 *   <li><b>TEAM / ORGANIZATION</b>: 閲覧＝{@code checkMembership}（非メンバー 403）、
 *       更新・削除＝{@code checkAdminOrAbove}（一般メンバー 403）</li>
 *   <li><b>TOURNAMENT / TOURNAMENT_DIVISION</b>: 従来どおり {@link FolderScopeAccessGuard} に委譲</li>
 * </ul>
 *
 * <p><b>BOLA / 接ぎ木封鎖:</b> {@code parentId} を受け取る全経路で、親フォルダのスコープが
 * 作成先スコープと一致することを検証する（他チーム配下に自分のフォルダを接ぎ木させない）。
 * 不一致は 404（存在秘匿）。</p>
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
     * Wave7 以降、本ガードは<b>大会／ディビジョンスコープの分岐からのみ</b>呼ぶ
     * （TEAM / ORGANIZATION / PERSONAL は下記 {@link AccessControlService} 経路で認可する）。
     */
    private final FolderScopeAccessGuard folderScopeAccessGuard;
    /** 認可根治 Wave7: TEAM / ORGANIZATION スコープの per-scope 認可（メンバー / 管理者）。 */
    private final AccessControlService accessControlService;

    /**
     * チームのルートフォルダ一覧を取得する。
     *
     * <p>認可（Wave7）: 当該チームのメンバーのみ。従来は {@code userId} を引数に取らず
     * <b>認可が原理的に不可能</b>だったため、任意チームのルートフォルダ名・説明・
     * {@code minVisibleRole} / {@code downloadDisabled} が列挙できた。</p>
     *
     * @param teamId チームID
     * @param userId 操作ユーザーID
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listTeamRootFolders(Long teamId, Long userId) {
        accessControlService.checkMembership(userId, teamId, "TEAM");
        List<SharedFolderEntity> folders = folderRepository.findByTeamIdAndParentIdIsNullOrderByNameAsc(teamId);
        return fileSharingMapper.toFolderResponseList(folders);
    }

    /**
     * 組織のルートフォルダ一覧を取得する。
     *
     * <p>認可（Wave7）: 当該組織のメンバーのみ（チーム版と同一方針）。</p>
     *
     * @param organizationId 組織ID
     * @param userId         操作ユーザーID
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listOrgRootFolders(Long organizationId, Long userId) {
        accessControlService.checkMembership(userId, organizationId, "ORGANIZATION");
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
     * <p>認可（Wave7）: 親フォルダ<b>実体</b>のスコープで閲覧認可を当てる（BOLA 封鎖。
     * パスの teamId を信用せず、folderId から辿った実スコープで判定する）。</p>
     *
     * @param folderId 親フォルダID
     * @param userId   操作ユーザーID
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listChildFolders(Long folderId, Long userId) {
        checkFolderViewAccess(findFolderOrThrow(folderId), userId);
        List<SharedFolderEntity> folders = folderRepository.findByParentIdOrderByNameAsc(folderId);
        return fileSharingMapper.toFolderResponseList(folders);
    }

    /**
     * フォルダ詳細を取得する。
     *
     * <p>認可（Wave7）: フォルダ実体のスコープで閲覧認可を当てる。</p>
     *
     * @param folderId フォルダID
     * @param userId   操作ユーザーID
     * @return フォルダレスポンス
     */
    public FolderResponse getFolder(Long folderId, Long userId) {
        SharedFolderEntity entity = findFolderOrThrow(folderId);
        checkFolderViewAccess(entity, userId);
        return fileSharingMapper.toFolderResponse(entity);
    }

    /**
     * チーム用フォルダを作成する。
     *
     * <p>認可（Wave7）: 当該チームのメンバーのみ作成可（汎用 EP
     * {@code SharedFolderQueryService#createFolder} の {@code authorizeScopeView} と同粒度）。
     * さらに {@code parentId} 指定時は<b>親が同一チームのフォルダであること</b>を検証し、
     * 他チーム配下への接ぎ木を封鎖する。</p>
     *
     * @param teamId  チームID
     * @param userId  作成者ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createTeamFolder(Long teamId, Long userId, CreateFolderRequest request) {
        accessControlService.checkMembership(userId, teamId, "TEAM");
        checkParentWithinScope(request.getParentId(), FileScopeType.TEAM, teamId);
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
     * <p>認可（Wave7）: 当該組織のメンバーのみ作成可。{@code parentId} 指定時は親が
     * 同一組織のフォルダであることを検証する（接ぎ木封鎖）。</p>
     *
     * @param organizationId 組織ID
     * @param userId         作成者ユーザーID
     * @param request        作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createOrgFolder(Long organizationId, Long userId, CreateFolderRequest request) {
        accessControlService.checkMembership(userId, organizationId, "ORGANIZATION");
        checkParentWithinScope(request.getParentId(), FileScopeType.ORGANIZATION, organizationId);
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
     * <p><b>接ぎ木封鎖（Wave7）:</b> {@code userId} は SecurityContext 由来のため所有者のなりすましは
     * 起きないが、旧実装は {@code request.getParentId()} の<b>所有者検証が無かった</b>
     *（{@code validateFolderNameUnique} は同名重複しか見ない）。そのため他チーム／他人配下の
     * folderId を {@code parentId} に指定して PERSONAL フォルダを接ぎ木できた。
     * 本実装では親が<b>自分の PERSONAL フォルダ</b>であることを強制する。</p>
     *
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createPersonalFolder(Long userId, CreateFolderRequest request) {
        checkParentWithinScope(request.getParentId(), FileScopeType.PERSONAL, userId);
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
     * <p>認可（Wave7）: 更新は {@code minVisibleRole} / {@code downloadDisabled} という
     * <b>可視性の制御そのもの</b>を書き換えるため、閲覧より強い権限（TEAM/ORG は
     * ADMIN/DEPUTY_ADMIN、PERSONAL は本人）を要求する
     * （{@code SharedFolderQueryService#authorizeDelete} と同粒度）。</p>
     *
     * <p><b>移動時の接ぎ木封鎖:</b> {@code parentId} 指定（＝移動）時は、移動先の親が
     * <b>同一スコープ</b>のフォルダであることを検証する。他チーム配下へ自チームのフォルダを
     * ぶら下げる／逆に他チームのフォルダを引き込む経路を塞ぐ。</p>
     *
     * @param folderId フォルダID
     * @param userId   操作ユーザーID
     * @param request  更新リクエスト
     * @return 更新されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse updateFolder(Long folderId, Long userId, UpdateFolderRequest request) {
        SharedFolderEntity entity = findFolderOrThrow(folderId);
        checkFolderManageAccess(entity, userId);

        if (request.getName() != null) {
            entity.changeName(request.getName());
        }
        if (request.getDescription() != null) {
            entity.changeDescription(request.getDescription());
        }
        if (request.getParentId() != null) {
            checkParentWithinScope(request.getParentId(), entity.getScopeType(), resolveScopeId(entity));
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
     * <p>認可（Wave7）: 削除は破壊操作のため更新と同粒度（TEAM/ORG は ADMIN/DEPUTY_ADMIN、
     * PERSONAL は本人）。</p>
     *
     * <p><b>注意:</b> 本メソッドは当該フォルダのみを soft-delete する（配下のカスケード削除・
     * 容量戻しは行わない）。FE の汎用削除 EP は
     * {@code SharedFolderQueryService#deleteFolder}（カスケード＋容量戻し）を使う。</p>
     *
     * @param folderId フォルダID
     * @param userId   操作ユーザーID
     */
    @Transactional
    public void deleteFolder(Long folderId, Long userId) {
        SharedFolderEntity entity = findFolderOrThrow(folderId);
        checkFolderManageAccess(entity, userId);
        entity.softDelete();
        folderRepository.save(entity);
        log.info("フォルダ削除: folderId={}, userId={}", folderId, userId);
    }

    /**
     * フォルダを取得する。存在しない場合は例外をスローする。
     */
    public SharedFolderEntity findFolderOrThrow(Long folderId) {
        return folderRepository.findById(folderId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND));
    }

    // ========================================
    // 認可（認可根治 Wave7）
    // ========================================

    /**
     * フォルダ実体のスコープに応じた<b>閲覧認可</b>（{@code SharedFolderQueryService#authorizeBaseView} と同一方針）。
     *
     * <p>ArchUnit 認可番人の委譲追跡は 2 ホップまでのため、{@link AccessControlService} は
     * 本メソッドから<b>直接</b>呼ぶ（{@code SharedFolderQueryService} へ委譲すると番人から見えなくなる）。
     * 大会／ディビジョンのみ従来どおり {@link FolderScopeAccessGuard}（tournament ドメイン実装）へ委譲する。</p>
     *
     * @param folder 対象フォルダ（scope は実体由来＝BOLA 封鎖）
     * @param userId 操作ユーザー ID
     */
    private void checkFolderViewAccess(SharedFolderEntity folder, Long userId) {
        switch (folder.getScopeType()) {
            case PERSONAL -> checkPersonalOwner(folder, userId);
            case TEAM -> accessControlService.checkMembership(userId, folder.getTeamId(), "TEAM");
            case ORGANIZATION ->
                    accessControlService.checkMembership(userId, folder.getOrganizationId(), "ORGANIZATION");
            case TOURNAMENT, TOURNAMENT_DIVISION ->
                    folderScopeAccessGuard.checkFolderViewByFolderId(folder.getId(), userId);
        }
    }

    /**
     * フォルダ実体のスコープに応じた<b>管理認可</b>（更新・削除）。閲覧より強い権限を要求する。
     *
     * <p>{@code SharedFolderQueryService#authorizeDelete} と同一方針（TEAM/ORG は
     * {@code checkAdminOrAbove}＝ADMIN/DEPUTY_ADMIN のみ、一般 MEMBER は 403）。</p>
     *
     * @param folder 対象フォルダ
     * @param userId 操作ユーザー ID
     */
    private void checkFolderManageAccess(SharedFolderEntity folder, Long userId) {
        switch (folder.getScopeType()) {
            case PERSONAL -> checkPersonalOwner(folder, userId);
            case TEAM -> accessControlService.checkAdminOrAbove(userId, folder.getTeamId(), "TEAM");
            case ORGANIZATION ->
                    accessControlService.checkAdminOrAbove(userId, folder.getOrganizationId(), "ORGANIZATION");
            case TOURNAMENT, TOURNAMENT_DIVISION ->
                    folderScopeAccessGuard.checkFolderPostByFolderId(folder.getId(), userId);
        }
    }

    /** 個人フォルダの所有者判定。他人のものは存在を漏らさず 404。 */
    private void checkPersonalOwner(SharedFolderEntity folder, Long userId) {
        if (userId == null || !userId.equals(folder.getUserId())) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }
    }

    /**
     * 親フォルダが作成／移動先スコープと同一であることを検証する（接ぎ木・BOLA 封鎖）。
     *
     * <p>{@code parentId} が null（＝ルート直下）なら何もしない。非 null のとき、親フォルダが
     * 存在しない／スコープ種別が違う／同種でもスコープ ID が違う場合はいずれも
     * {@code FOLDER_NOT_FOUND}（404）とし、他スコープの folderId の存在有無を漏らさない。</p>
     *
     * @param parentId       リクエスト由来の親フォルダ ID（null 可）
     * @param expectedScope  期待するスコープ種別
     * @param expectedScopeId 期待するスコープ ID（teamId / organizationId / userId / scopeRefId）
     */
    private void checkParentWithinScope(Long parentId, FileScopeType expectedScope, Long expectedScopeId) {
        if (parentId == null) {
            return;
        }
        SharedFolderEntity parent = findFolderOrThrow(parentId);
        if (parent.getScopeType() != expectedScope
                || !Objects.equals(resolveScopeId(parent), expectedScopeId)) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }
    }

    /** フォルダのスコープ ID を解決する（TEAM=teamId / ORG=organizationId / PERSONAL=userId / 大会系=scopeRefId）。 */
    private Long resolveScopeId(SharedFolderEntity folder) {
        return switch (folder.getScopeType()) {
            case TEAM -> folder.getTeamId();
            case ORGANIZATION -> folder.getOrganizationId();
            case PERSONAL -> folder.getUserId();
            case TOURNAMENT, TOURNAMENT_DIVISION -> folder.getScopeRefId();
        };
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

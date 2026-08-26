package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.service.SharedFolderService;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F08.7.1 / 04 リーグ単位ファイル置き場の大会／ディビジョンスコープ folder/file 認可・スコープ解決サービス。
 *
 * <p>既存 F05.5 ファイル共有（{@link SharedFolderService}）を再利用し、大会／ディビジョン文脈の導線を
 * 追加する。認可は連絡スペースと統一し {@link TournamentContactAccessService} の
 * {@code checkView}（閲覧）/ {@code checkPost}（アップロード/編集）を流用する（設計書 §5）。</p>
 *
 * <p><b>IDOR 検証チェーン</b>（設計書 §3）: {@code tId → orgId}（主催組織帰属）→（ディビジョンなら）
 * {@code divId → tId} → {@code folderId → (scope_type, scope_ref_id)} 帰属。存在しない／論理削除済みは
 * 一律 404（存在を漏らさない）。</p>
 *
 * <p>本サービスは tournament ドメインから filesharing ドメインの Service を直接呼ぶ越境となる（原則5）。
 * クロスドメインは ID 参照のみ（原則1）。</p>
 *
 * <pre>{@code
 * // TODO: tournament ドメインから filesharing ドメインの Service を直接呼んでいる。
 * //       将来は TournamentFolderRequestedEvent によるイベント駆動化候補。
 * }</pre>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/04_file_storage.md</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentFolderService {

    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final SharedFolderService folderService;
    private final TournamentContactAccessService accessService;

    // ========================================================================
    // 大会スコープ
    // ========================================================================

    /**
     * 大会スコープのルートフォルダ一覧を取得する（閲覧認可を通す）。
     *
     * @param tournamentId 大会 ID
     * @param userId       閲覧ユーザー ID（未ログインは null。公開トグル ON なら閲覧可）
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listTournamentRootFolders(Long tournamentId, Long userId) {
        // IDOR: tId 帰属 + 閲覧認可（公開スペースは未ログイン可・read-only）
        resolveTournamentOrThrow(tournamentId);
        accessService.checkView(ContactSpaceScopeType.TOURNAMENT, tournamentId, ContactSpaceKind.BULLETIN, userId);
        return folderService.listTournamentScopedRootFolders(FileScopeType.TOURNAMENT, tournamentId);
    }

    /**
     * 大会スコープのフォルダを作成する（アップロード/編集認可を通す）。
     *
     * @param tournamentId 大会 ID
     * @param userId       作成ユーザー ID
     * @param request      作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createTournamentFolder(Long tournamentId, Long userId, CreateFolderRequest request) {
        TournamentEntity tournament = resolveTournamentOrThrow(tournamentId);
        accessService.checkPost(ContactSpaceScopeType.TOURNAMENT, tournamentId, userId);
        return folderService.createTournamentScopedFolder(
                FileScopeType.TOURNAMENT, tournament.getOrganizationId(), tournamentId, userId, request);
    }

    // ========================================================================
    // ディビジョンスコープ
    // ========================================================================

    /**
     * ディビジョンスコープのルートフォルダ一覧を取得する（閲覧認可を通す）。
     *
     * @param tournamentId 大会 ID（IDOR: divId 帰属検証用）
     * @param divisionId   ディビジョン ID
     * @param userId       閲覧ユーザー ID（未ログインは null）
     * @return フォルダレスポンスリスト
     */
    public List<FolderResponse> listDivisionRootFolders(Long tournamentId, Long divisionId, Long userId) {
        resolveDivisionOrThrow(tournamentId, divisionId);
        accessService.checkView(ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId, ContactSpaceKind.BULLETIN, userId);
        return folderService.listTournamentScopedRootFolders(FileScopeType.TOURNAMENT_DIVISION, divisionId);
    }

    /**
     * ディビジョンスコープのフォルダを作成する（アップロード/編集認可を通す）。
     *
     * @param tournamentId 大会 ID
     * @param divisionId   ディビジョン ID
     * @param userId       作成ユーザー ID
     * @param request      作成リクエスト
     * @return 作成されたフォルダレスポンス
     */
    @Transactional
    public FolderResponse createDivisionFolder(Long tournamentId, Long divisionId, Long userId,
                                               CreateFolderRequest request) {
        resolveDivisionOrThrow(tournamentId, divisionId);
        accessService.checkPost(ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId, userId);
        // クォータ帰属は主催組織。大会から組織を辿る（§2.1）。
        Long organizationId = resolveTournamentOrThrow(tournamentId).getOrganizationId();
        return folderService.createTournamentScopedFolder(
                FileScopeType.TOURNAMENT_DIVISION, organizationId, divisionId, userId, request);
    }

    // ========================================================================
    // フォルダ個別操作の認可（folderId → スコープ帰属検証 + canView/canPost）
    // ========================================================================

    /**
     * フォルダ個別操作の閲覧認可を検証する（設計書 §3・IDOR チェーンの末端 {@code folderId → scope}）。
     *
     * <p>folderId が当該大会／ディビジョンスコープに属することを確認してから閲覧認可を通す。
     * 他スコープの folderId を渡す IDOR は 404 で弾く。</p>
     *
     * @param tournamentId 大会 ID
     * @param divisionId   ディビジョン ID（大会スコープなら null）
     * @param folderId     フォルダ ID
     * @param userId       閲覧ユーザー ID（未ログインは null）
     */
    public void checkFolderViewAccess(Long tournamentId, Long divisionId, Long folderId, Long userId) {
        ScopeRef ref = resolveAndVerifyFolderScope(tournamentId, divisionId, folderId);
        accessService.checkView(ref.scopeType(), ref.scopeId(), ContactSpaceKind.BULLETIN, userId);
    }

    /**
     * フォルダ個別操作のアップロード/編集認可を検証する（設計書 §5）。
     *
     * @param tournamentId 大会 ID
     * @param divisionId   ディビジョン ID（大会スコープなら null）
     * @param folderId     フォルダ ID
     * @param userId       操作ユーザー ID
     */
    public void checkFolderPostAccess(Long tournamentId, Long divisionId, Long folderId, Long userId) {
        ScopeRef ref = resolveAndVerifyFolderScope(tournamentId, divisionId, folderId);
        accessService.checkPost(ref.scopeType(), ref.scopeId(), userId);
    }

    // ========================================================================
    // 内部ヘルパー（IDOR チェーン）
    // ========================================================================

    /**
     * 大会の存在を確認して返す。存在しない／論理削除済みは 404（IDOR 対策）。
     */
    private TournamentEntity resolveTournamentOrThrow(Long tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
    }

    /**
     * ディビジョンが当該大会に属することを確認して返す（{@code divId → tId} 帰属）。
     * 不一致／不存在は 404（IDOR 対策）。
     */
    private TournamentDivisionEntity resolveDivisionOrThrow(Long tournamentId, Long divisionId) {
        return divisionRepository.findByIdAndTournamentId(divisionId, tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.DIVISION_NOT_FOUND));
    }

    /**
     * folderId が当該大会／ディビジョンスコープに属することを検証し、スコープ参照を返す。
     *
     * <p>IDOR チェーン: tId/divId 帰属を確認した上で、フォルダの {@code (scope_type, scope_ref_id)} が
     * 期待スコープと一致することを確認する。不一致／不存在は一律 404。</p>
     */
    private ScopeRef resolveAndVerifyFolderScope(Long tournamentId, Long divisionId, Long folderId) {
        ContactSpaceScopeType scopeType;
        Long scopeId;
        FileScopeType expectedFileScope;
        if (divisionId != null) {
            resolveDivisionOrThrow(tournamentId, divisionId);
            scopeType = ContactSpaceScopeType.TOURNAMENT_DIVISION;
            scopeId = divisionId;
            expectedFileScope = FileScopeType.TOURNAMENT_DIVISION;
        } else {
            resolveTournamentOrThrow(tournamentId);
            scopeType = ContactSpaceScopeType.TOURNAMENT;
            scopeId = tournamentId;
            expectedFileScope = FileScopeType.TOURNAMENT;
        }

        SharedFolderEntity folder = folderService.findFolderOrThrow(folderId);
        // 他スコープの folderId を渡す IDOR を 404 で弾く（存在を漏らさない）。
        if (folder.getScopeType() != expectedFileScope || !scopeId.equals(folder.getScopeRefId())) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }
        return new ScopeRef(scopeType, scopeId);
    }

    /** スコープ参照（連絡スペース認可へ渡す scopeType + scopeId）。 */
    private record ScopeRef(ContactSpaceScopeType scopeType, Long scopeId) {}
}

package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 大会・ディビジョン連絡スペースの認可サービス（F08.7.1 連絡機能 §4）。
 *
 * <p>村の二段認可（{@link com.mannschaft.app.village.service.VillageBulletinAccessService}）を範とし、
 * read（{@link #checkView}）/ write（{@link #checkPost}）を分離する。掲示板・チャット双方の
 * Service 入口で必ず通す（多層防御）。</p>
 *
 * <h2>認可ルール</h2>
 * <ul>
 *   <li>閲覧（{@code checkView}）: 公開スペース（{@code is_public=true}）なら未ログイン含め全員 ／
 *       参加チーム（status∈{REGISTERED,ACTIVE}）のアクティブメンバー ／ 主催組織 ADMIN ／ SYSTEM_ADMIN。</li>
 *   <li>投稿（{@code checkPost}）: 参加チームの ADMIN/DEPUTY_ADMIN ／ 主催組織 ADMIN ／ SYSTEM_ADMIN。
 *       一般 MEMBER / SUPPORTER / PUBLIC は閲覧のみ（権限昇格防止）。</li>
 *   <li>公開設定（{@code checkVisibilityManage}）: 主催組織 ADMIN ／ SYSTEM_ADMIN のみ（チーム代表は不可）。</li>
 * </ul>
 *
 * <p>存在しない／論理削除済みスペース・大会・ディビジョンは一律 404（IDOR 対策・存在を漏らさない）。
 * クロスドメインの所属判定は {@code AccessControlService} と {@code TournamentParticipantRepository} の
 * exists クエリ（ID 参照のみ・原則1）で行い、N+1 を回避する（§4.3）。読み取り専用ゆえ
 * {@code @Transactional(readOnly=true)} に閉じる（§4.4）。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/01_communication.md §4</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentContactAccessService {

    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final TournamentContactSpaceRepository contactSpaceRepository;
    private final TournamentParticipantRepository participantRepository;
    private final AccessControlService accessControlService;

    /**
     * 連絡スペースの閲覧認可を検証する（設計書 §4.1）。認可違反時は例外を投げる。
     *
     * @param scopeType スコープ種別（TOURNAMENT / TOURNAMENT_DIVISION）
     * @param scopeId   大会 ID（TOURNAMENT）またはディビジョン ID（TOURNAMENT_DIVISION）
     * @param spaceKind スペース種別（BULLETIN / CHAT）
     * @param userId    閲覧しようとするユーザー ID（未ログインは null）
     * @throws BusinessException スペース不在（{@link TournamentErrorCode#CONTACT_SPACE_NOT_FOUND}・404）／
     *                           閲覧権限なし（{@link TournamentErrorCode#CONTACT_SPACE_VIEW_FORBIDDEN}・403）
     */
    public void checkView(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind spaceKind, Long userId) {
        // スペースが存在しない／論理削除済みは 404（IDOR 対策）
        boolean isPublic = contactSpaceRepository
                .findByScopeTypeAndScopeIdAndSpaceKind(scopeType, scopeId, spaceKind)
                .map(s -> Boolean.TRUE.equals(s.getIsPublic()))
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));

        // 公開スペースは未ログイン含め全員閲覧可（read-only）
        if (isPublic) {
            return;
        }

        // 未ログインで非公開スペースは閲覧不可（存在は 404 で隠さず、認可は 403）
        if (userId == null) {
            throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN);
        }

        // 参加チームのアクティブメンバーなら許可（N+1 回避 exists クエリ）
        if (isActiveParticipantMember(scopeType, scopeId, userId)) {
            return;
        }

        // 主催組織 ADMIN / SYSTEM_ADMIN なら許可
        if (isOrganizerAdminOrSystemAdmin(scopeType, scopeId, userId)) {
            return;
        }

        throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN);
    }

    /**
     * 連絡スペースへの投稿認可を検証する（設計書 §4.2）。認可違反時は例外を投げる。
     *
     * <p>投稿できるのは各参加チームの代表（ADMIN）・副代表（DEPUTY_ADMIN）と主催組織 ADMIN ／ SYSTEM_ADMIN のみ。
     * 一般 MEMBER / SUPPORTER / PUBLIC は閲覧のみ（権限昇格防止）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   大会 ID またはディビジョン ID
     * @param userId    投稿しようとするユーザー ID
     * @throws BusinessException 投稿権限なし（{@link TournamentErrorCode#CONTACT_SPACE_POST_FORBIDDEN}・403）／
     *                           スコープ不在（{@link TournamentErrorCode#CONTACT_SPACE_NOT_FOUND}・404）
     */
    public void checkPost(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        if (userId == null) {
            throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        // 参加チームの ADMIN/DEPUTY_ADMIN なら許可（N+1 回避 exists クエリ）
        boolean isTeamAdmin = scopeType == ContactSpaceScopeType.TOURNAMENT
                ? participantRepository.existsTeamAdminOfAnyParticipantTeam(scopeId, userId)
                : participantRepository.existsTeamAdminOfDivisionParticipantTeam(scopeId, userId);
        if (isTeamAdmin) {
            return;
        }

        // 主催組織 ADMIN / SYSTEM_ADMIN なら許可
        if (isOrganizerAdminOrSystemAdmin(scopeType, scopeId, userId)) {
            return;
        }

        throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
    }

    /**
     * 連絡スペースの公開設定変更認可を検証する（設計書 §5・主催組織 ADMIN / SYSTEM_ADMIN 限定）。
     *
     * <p>{@code checkPost} より厳しく、チーム代表は公開設定を変更できない。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   大会 ID またはディビジョン ID
     * @param userId    操作ユーザー ID
     * @throws BusinessException 権限なし（{@link TournamentErrorCode#CONTACT_SPACE_VISIBILITY_FORBIDDEN}・403）／
     *                           スコープ不在（{@link TournamentErrorCode#CONTACT_SPACE_NOT_FOUND}・404）
     */
    public void checkVisibilityManage(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        Long organizationId = resolveOrganizationId(scopeType, scopeId);
        if (userId != null && accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return;
        }
        throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
    }

    // ========================================================================
    // 内部ヘルパー
    // ========================================================================

    private boolean isActiveParticipantMember(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        return scopeType == ContactSpaceScopeType.TOURNAMENT
                ? participantRepository.existsActiveMemberOfAnyParticipantTeam(scopeId, userId)
                : participantRepository.existsActiveMemberOfDivisionParticipantTeam(scopeId, userId);
    }

    private boolean isOrganizerAdminOrSystemAdmin(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        Long organizationId = resolveOrganizationId(scopeType, scopeId);
        if (accessControlService.isAdmin(userId, organizationId, "ORGANIZATION")) {
            return true;
        }
        return accessControlService.isSystemAdmin(userId);
    }

    /**
     * スコープから主催組織 ID を解決する。大会／ディビジョンが存在しない場合は 404。
     */
    private Long resolveOrganizationId(ContactSpaceScopeType scopeType, Long scopeId) {
        if (scopeType == ContactSpaceScopeType.TOURNAMENT) {
            TournamentEntity tournament = tournamentRepository.findById(scopeId)
                    .orElseThrow(() -> new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
            return tournament.getOrganizationId();
        }
        TournamentDivisionEntity division = divisionRepository.findById(scopeId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
        TournamentEntity tournament = tournamentRepository.findById(division.getTournamentId())
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.CONTACT_SPACE_NOT_FOUND));
        return tournament.getOrganizationId();
    }
}

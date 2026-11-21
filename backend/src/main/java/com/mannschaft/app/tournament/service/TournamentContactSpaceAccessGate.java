package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 大会・ディビジョン連絡スペースの存在秘匿つき認可ゲート（CMP-261116-0726）。
 *
 * <p>存在を「知り得ない」者（そのスコープのいずれのスペースも閲覧できない者）には、スペース・大会・
 * ディビジョンが実在しない場合と同一の不在応答（呼び出し側が渡す {@code notFoundCode}）を返し、存在オラクルを
 * 塞ぐ。知り得るが権限が足りない者にだけ 403（投稿 {@link TournamentErrorCode#CONTACT_SPACE_POST_FORBIDDEN}／
 * 公開設定 {@link TournamentErrorCode#CONTACT_SPACE_VISIBILITY_FORBIDDEN}）を返す。</p>
 *
 * <h2>tx 境界（ArchUnit D-3T）</h2>
 * <p>本クラスは意図して {@code @Transactional} を持たない。他ドメインの tx 入口から本ゲートを経由すると、
 * tournament ドメインの Repository への到達が新たな越境違反として凍結キーを増やすため、他ドメインからは
 * Controller や tx を持たない Facade から呼ぶこと。tournament ドメイン内の Service からは tx 内で呼んでよい。</p>
 *
 * <h2>rollback-only 対策</h2>
 * <p>判定は Repository と {@link AccessControlService}（{@code isAdmin(userId, orgId, "ORGANIZATION")} と
 * {@code isSystemAdmin} のみ）の真偽値で行い、他 Service の例外を catch して分岐する方式は取らない
 * （proxy 越しの例外は外側 tx を rollback-only にし 500 を招くため）。</p>
 *
 * <p>判定に要するクエリ数は参加チーム数・返却件数に比例しない（AC-20）。</p>
 */
@Component
@RequiredArgsConstructor
public class TournamentContactSpaceAccessGate {

    private static final String ORGANIZATION_SCOPE = "ORGANIZATION";

    private final TournamentContactSpaceRepository contactSpaceRepository;
    private final TournamentParticipantRepository participantRepository;
    private final TournamentRepository tournamentRepository;
    private final TournamentDivisionRepository divisionRepository;
    private final AccessControlService accessControlService;

    /**
     * 閲覧を要求する。拒否・スペース不在・大会／ディビジョン不在はすべて {@code notFoundCode}。
     *
     * @param scopeType    スコープ種別
     * @param scopeId      大会 ID またはディビジョン ID
     * @param kind         スペース種別（BULLETIN / CHAT）
     * @param userId       ユーザー ID（未ログインは null）
     * @param notFoundCode 不在応答に用いるエラーコード（入口のリソース種別に合わせる）
     */
    public void requireView(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind, Long userId,
                            ErrorCode notFoundCode) {
        TournamentContactSpaceEntity space = findSpaceOrThrow(scopeType, scopeId, kind, notFoundCode);
        if (isPublic(space)) {
            return;
        }
        if (userId == null) {
            throw new BusinessException(notFoundCode);
        }
        if (isActiveParticipantMember(scopeType, scopeId, userId)) {
            return;
        }
        if (isOrganizerAdminOrSystemAdmin(scopeType, scopeId, userId, notFoundCode)) {
            return;
        }
        throw new BusinessException(notFoundCode);
    }

    /**
     * 投稿・管理を要求する。
     *
     * <p>スペース（{@code kind}）不在は {@code notFoundCode}（AC-19）。投稿者なら閲覧判定をせずに返る（AC-20）。
     * 拒否時のみ閲覧可否を判定し、閲覧可なら 403 {@link TournamentErrorCode#CONTACT_SPACE_POST_FORBIDDEN}、
     * 閲覧不可なら {@code notFoundCode}（AC-17: 判定は {@code kind} のスペースの公開設定に従う）。</p>
     */
    public void requirePost(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind, Long userId,
                            ErrorCode notFoundCode) {
        TournamentContactSpaceEntity space = findSpaceOrThrow(scopeType, scopeId, kind, notFoundCode);
        if (canPost(scopeType, scopeId, userId, notFoundCode)) {
            return;
        }
        if (isPublic(space) || (userId != null && isActiveParticipantMember(scopeType, scopeId, userId))) {
            throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }
        throw new BusinessException(notFoundCode);
    }

    /**
     * 本人短絡つきの投稿・管理要求（AC-16）。本人なら閲覧のみ要求し、そうでなければ投稿権を要求する。
     *
     * @param isAuthor 呼び出し側が自ドメインの Repository で判定した「対象の作成者本人か」
     */
    public void requireAuthorOrPost(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind, Long userId,
                                    boolean isAuthor, ErrorCode notFoundCode) {
        if (isAuthor) {
            requireView(scopeType, scopeId, kind, userId, notFoundCode);
        } else {
            requirePost(scopeType, scopeId, kind, userId, notFoundCode);
        }
    }

    /**
     * 閲覧を要求し、投稿権の有無も併せて返す（readers 用・AC-15/AC-20）。
     *
     * <p>投稿判定を先に行い、成立すれば閲覧判定を省略する。閲覧不可・不在は {@code notFoundCode}。</p>
     */
    public ContactAccessLevel requireViewResolvingLevel(ContactSpaceScopeType scopeType, Long scopeId,
                                                        ContactSpaceKind kind, Long userId,
                                                        ErrorCode notFoundCode) {
        TournamentContactSpaceEntity space = findSpaceOrThrow(scopeType, scopeId, kind, notFoundCode);
        if (canPost(scopeType, scopeId, userId, notFoundCode)) {
            return ContactAccessLevel.POST;
        }
        if (isPublic(space)) {
            return ContactAccessLevel.VIEW;
        }
        // 主催 ADMIN / SYSTEM_ADMIN は canPost で成立済み。残る閲覧者は参加チームの active member のみ。
        if (userId != null && isActiveParticipantMember(scopeType, scopeId, userId)) {
            return ContactAccessLevel.VIEW;
        }
        throw new BusinessException(notFoundCode);
    }

    /**
     * 公開設定 API の管理権を要求する（AC-11/AC-18）。
     *
     * <p>パスの大会 ID とディビジョンの帰属もここで検証し、大会不在・ディビジョン不在・帰属不一致は
     * 主催 ADMIN／SYSTEM_ADMIN でも {@link TournamentErrorCode#CONTACT_SPACE_NOT_FOUND}。管理権がなければ、
     * そのスコープのいずれかのスペースを閲覧できる者には 403
     * {@link TournamentErrorCode#CONTACT_SPACE_VISIBILITY_FORBIDDEN}、それ以外には不在と同一の 404。</p>
     *
     * @param tournamentId    パスの大会 ID
     * @param divisionIdOrNull パスのディビジョン ID（大会スコープなら null）
     * @param userId          操作ユーザー ID
     */
    public void requireVisibilityManage(Long tournamentId, Long divisionIdOrNull, Long userId) {
        ErrorCode notFound = TournamentErrorCode.CONTACT_SPACE_NOT_FOUND;
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(notFound));
        ContactSpaceScopeType scopeType;
        Long scopeId;
        if (divisionIdOrNull != null) {
            divisionRepository.findByIdAndTournamentId(divisionIdOrNull, tournamentId)
                    .orElseThrow(() -> new BusinessException(notFound));
            scopeType = ContactSpaceScopeType.TOURNAMENT_DIVISION;
            scopeId = divisionIdOrNull;
        } else {
            scopeType = ContactSpaceScopeType.TOURNAMENT;
            scopeId = tournamentId;
        }

        if (userId != null && (accessControlService.isSystemAdmin(userId)
                || accessControlService.isAdmin(userId, tournament.getOrganizationId(), ORGANIZATION_SCOPE))) {
            return;
        }

        // 知り得る = そのスコープのいずれかのスペース（BULLETIN / CHAT）を閲覧できる
        List<TournamentContactSpaceEntity> spaces = contactSpaceRepository.findByScopeTypeAndScopeId(scopeType, scopeId);
        boolean knowable = spaces.stream().anyMatch(this::isPublic)
                || (!spaces.isEmpty() && userId != null && isActiveParticipantMember(scopeType, scopeId, userId));
        if (knowable) {
            throw new BusinessException(TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
        }
        throw new BusinessException(notFound);
    }

    // ========================================================================
    // 内部ヘルパー
    // ========================================================================

    private TournamentContactSpaceEntity findSpaceOrThrow(ContactSpaceScopeType scopeType, Long scopeId,
                                                          ContactSpaceKind kind, ErrorCode notFoundCode) {
        return contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(scopeType, scopeId, kind)
                .orElseThrow(() -> new BusinessException(notFoundCode));
    }

    private boolean isPublic(TournamentContactSpaceEntity space) {
        return Boolean.TRUE.equals(space.getIsPublic());
    }

    /** 投稿者（参加チーム ADMIN/DEPUTY_ADMIN・主催組織 ADMIN・SYSTEM_ADMIN）か。 */
    private boolean canPost(ContactSpaceScopeType scopeType, Long scopeId, Long userId, ErrorCode notFoundCode) {
        if (userId == null) {
            return false;
        }
        boolean isTeamAdmin = scopeType == ContactSpaceScopeType.TOURNAMENT
                ? participantRepository.countTeamAdminOfAnyParticipantTeam(scopeId, userId) > 0
                : participantRepository.countTeamAdminOfDivisionParticipantTeam(scopeId, userId) > 0;
        return isTeamAdmin || isOrganizerAdminOrSystemAdmin(scopeType, scopeId, userId, notFoundCode);
    }

    private boolean isActiveParticipantMember(ContactSpaceScopeType scopeType, Long scopeId, Long userId) {
        return scopeType == ContactSpaceScopeType.TOURNAMENT
                ? participantRepository.countActiveMemberOfAnyParticipantTeam(scopeId, userId) > 0
                : participantRepository.countActiveMemberOfDivisionParticipantTeam(scopeId, userId) > 0;
    }

    private boolean isOrganizerAdminOrSystemAdmin(ContactSpaceScopeType scopeType, Long scopeId, Long userId,
                                                  ErrorCode notFoundCode) {
        Long organizationId = resolveOrganizationId(scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(notFoundCode));
        return accessControlService.isAdmin(userId, organizationId, ORGANIZATION_SCOPE)
                || accessControlService.isSystemAdmin(userId);
    }

    /** スコープから主催組織 ID を解決する。大会／ディビジョンが存在しなければ空。 */
    private Optional<Long> resolveOrganizationId(ContactSpaceScopeType scopeType, Long scopeId) {
        Optional<Long> tournamentId = scopeType == ContactSpaceScopeType.TOURNAMENT
                ? Optional.of(scopeId)
                : divisionRepository.findById(scopeId).map(d -> d.getTournamentId());
        return tournamentId.flatMap(tournamentRepository::findById).map(TournamentEntity::getOrganizationId);
    }

    /**
     * スペース群の閲覧水準を例外なしで一括解決する（CMP-261118-0900 §10.3.5・§10.2 御裁可）。
     *
     * <p>閲覧できない行はマップに含めない（NONE は返さない）。全 (scope, kind) について
     * {@link #requireViewResolvingLevel} の戻り値（例外になるものは不在）と一致する不変条件を持つ（AC-P34）。</p>
     *
     * <ul>
     *   <li>{@code tournamentId} 配下でないスペース（別大会の大会全体スペース・別大会／不在の部門のスペース）は
     *       含めない（X1）。</li>
     *   <li>大会が見つからない（論理削除・レース）なら例外を投げず空マップ（X3）。</li>
     *   <li>クエリ本数は部門数・参加チーム数・スペース数に比例しない定数（AC-P20）。部門ごとの単発 count や
     *       スペースの再取得をせず、部門の帰属確認・代表部門・会員部門をそれぞれ IN の 1 本で引く。
     *       データによって本数が揺れないよう、利用者がいれば主催者判定と集約クエリは結果によらず一度ずつ引く。</li>
     * </ul>
     *
     * @param userId       ユーザー ID（未ログインは null。公開スペースのみ VIEW になる）
     * @param tournamentId 大会 ID
     * @param spaces       判定対象のスペース群（呼び出し側が取得済みのもの）
     * @return スペース ID から水準への写像（閲覧不可は含めない）
     */
    public Map<UUID, ContactAccessLevel> resolveLevelsForSpaces(
            Long userId, Long tournamentId, Collection<TournamentContactSpaceEntity> spaces) {
        if (spaces == null || spaces.isEmpty()) {
            return Map.of();
        }
        Optional<TournamentEntity> tournament = tournamentRepository.findById(tournamentId);
        if (tournament.isEmpty()) {
            return Map.of(); // X3
        }

        // X1: 大会配下の部門だけを判定対象にする（帰属確認は IN の 1 本）
        Set<Long> requestedDivisionIds = spaces.stream()
                .filter(s -> s.getScopeType() == ContactSpaceScopeType.TOURNAMENT_DIVISION)
                .map(TournamentContactSpaceEntity::getScopeId)
                .collect(Collectors.toSet());
        Set<Long> ownedDivisionIds = requestedDivisionIds.isEmpty() ? Set.of()
                : divisionRepository.findAllById(requestedDivisionIds).stream()
                        .filter(d -> tournamentId.equals(d.getTournamentId()))
                        .map(TournamentDivisionEntity::getId)
                        .collect(Collectors.toSet());
        boolean hasTournamentScope = spaces.stream().anyMatch(s -> isTournamentScopeOf(s, tournamentId));

        ParticipationSnapshot snapshot = userId == null
                ? ParticipationSnapshot.ANONYMOUS
                : loadParticipation(userId, tournamentId, tournament.get().getOrganizationId(),
                        ownedDivisionIds, hasTournamentScope);

        Map<UUID, ContactAccessLevel> levels = new HashMap<>();
        for (TournamentContactSpaceEntity space : spaces) {
            boolean tournamentScope = isTournamentScopeOf(space, tournamentId);
            boolean divisionScope = space.getScopeType() == ContactSpaceScopeType.TOURNAMENT_DIVISION
                    && ownedDivisionIds.contains(space.getScopeId());
            if (!tournamentScope && !divisionScope) {
                continue; // X1
            }
            // 以下は requireViewResolvingLevel と同じ順（投稿者 → 公開 → 参加チームの有効メンバー）
            boolean teamAdmin = tournamentScope
                    ? snapshot.teamAdminOfAny()
                    : snapshot.adminDivisionIds().contains(space.getScopeId());
            if (teamAdmin || snapshot.organizerAdminOrSystemAdmin()) {
                levels.put(space.getId(), ContactAccessLevel.POST);
            } else if (isPublic(space)) {
                levels.put(space.getId(), ContactAccessLevel.VIEW);
            } else if (tournamentScope
                    ? snapshot.activeMemberOfAny()
                    : snapshot.memberDivisionIds().contains(space.getScopeId())) {
                levels.put(space.getId(), ContactAccessLevel.VIEW);
            }
        }
        return levels;
    }

    private static boolean isTournamentScopeOf(TournamentContactSpaceEntity space, Long tournamentId) {
        return space.getScopeType() == ContactSpaceScopeType.TOURNAMENT && tournamentId.equals(space.getScopeId());
    }

    /** 利用者の所属を集約クエリで一度ずつ引く（条件は単発の {@code count*} と同一）。 */
    private ParticipationSnapshot loadParticipation(Long userId, Long tournamentId, Long organizationId,
                                                    Set<Long> ownedDivisionIds, boolean hasTournamentScope) {
        boolean organizerOrSystemAdmin = accessControlService.isAdmin(userId, organizationId, ORGANIZATION_SCOPE)
                || accessControlService.isSystemAdmin(userId);
        Set<Long> adminDivisionIds = ownedDivisionIds.isEmpty() ? Set.of()
                : new HashSet<>(participantRepository.findDivisionIdsWhereTeamAdmin(ownedDivisionIds, userId));
        Set<Long> memberDivisionIds = ownedDivisionIds.isEmpty() ? Set.of()
                : new HashSet<>(participantRepository.findDivisionIdsWhereActiveMember(ownedDivisionIds, userId));
        boolean teamAdminOfAny = hasTournamentScope
                && participantRepository.countTeamAdminOfAnyParticipantTeam(tournamentId, userId) > 0;
        boolean activeMemberOfAny = hasTournamentScope
                && participantRepository.countActiveMemberOfAnyParticipantTeam(tournamentId, userId) > 0;
        return new ParticipationSnapshot(organizerOrSystemAdmin, teamAdminOfAny, activeMemberOfAny,
                adminDivisionIds, memberDivisionIds);
    }

    /** 一括解決に用いる利用者の所属の写し。 */
    private record ParticipationSnapshot(boolean organizerAdminOrSystemAdmin, boolean teamAdminOfAny,
                                         boolean activeMemberOfAny, Set<Long> adminDivisionIds,
                                         Set<Long> memberDivisionIds) {

        static final ParticipationSnapshot ANONYMOUS =
                new ParticipationSnapshot(false, false, false, Set.of(), Set.of());
    }
}

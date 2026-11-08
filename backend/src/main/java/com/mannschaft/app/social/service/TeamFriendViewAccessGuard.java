package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * フレンド関係（team_friends）の書込前に通す「閲覧可否の門」（CMP-260821-1027 存在オラクルの本文正規化）。
 *
 * <h2>保証する内容</h2>
 * <p>一覧と本部品の閲覧資格は、本クラスの {@link #listsPublicOnly} を唯一の部品として共有する
 * （フレンド一覧 {@code TeamFriendsController#listFriends} も同メソッドで SUPPORTER の公開限定を決める）。</p>
 *
 * <p>フレンド関係を閲覧できない者には、不在の関係IDと同一の {@link SocialErrorCode#FRIEND_RELATION_NOT_FOUND}
 * （SOCIAL_106・404・同一本文）を返して実在を秘匿する。閲覧資格はフレンド一覧
 * （{@code GET /teams/{id}/friends}・{@link TeamFriendQueryService#listFriends}）と同じで、関係の片方のチームに
 * 所属し（{@link AccessControlService#isMember}）、かつ SUPPORTER のみの者は公開済み（{@code is_public=TRUE}）の
 * 関係に限る。閲覧できる者はそのまま通し、ADMIN 限定の拒否（SOCIAL_107）は従来どおり
 * {@link TeamFriendVisibilityService} に委ねる。</p>
 *
 * <p>判定の順序は {@link TeamFriendVisibilityService#setVisibility} と揃える。パスのチームの ADMIN でない者は、
 * 関係 ID の実在に関わらず同 Service が先頭で一律に SOCIAL_107 を返す（関係 ID に依存しないので存在は漏れない）ため、
 * 本部品は判定せずに通す。ADMIN についてだけ、関係を閲覧できるかを Service より先に判定する。</p>
 *
 * <h2>なぜ {@code @Transactional} を付けず、Controller から先に呼ぶのか</h2>
 * <p>所属判定は membership / role ドメインの Repository へ到達するため、書込 TX の中で行うと D-3T（取引の越境）
 * に当たる。本部品は取引を持たず、他ドメインの判定はそれぞれの取引で行う
 * （{@code CorkboardScopedBoardVisibilityGuard} と同じ型）。</p>
 */
@Component
@RequiredArgsConstructor
public class TeamFriendViewAccessGuard {

    private static final String SCOPE_TEAM = "TEAM";
    private static final String ROLE_SUPPORTER = "SUPPORTER";

    private final TeamFriendRepository teamFriendRepository;
    private final AccessControlService accessControlService;

    /**
     * パスのチームの ADMIN が、フレンド関係を閲覧できることを確かめる。
     *
     * @param teamId       パスのチームID
     * @param teamFriendId フレンド関係ID（パス由来）
     * @param userId       操作ユーザーID
     * @throws BusinessException 不在・閲覧できない者（{@link SocialErrorCode#FRIEND_RELATION_NOT_FOUND} / 404）
     */
    public void requireVisible(Long teamId, Long teamFriendId, Long userId) {
        if (!accessControlService.isAdmin(userId, teamId, SCOPE_TEAM)) {
            // 非 ADMIN は Service が関係 ID に依存せず SOCIAL_107 を返す。
            return;
        }
        TeamFriendEntity friend = teamFriendRepository.findById(teamFriendId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_RELATION_NOT_FOUND));
        if (teamId.equals(friend.getTeamAId()) || teamId.equals(friend.getTeamBId())) {
            // パスのチームの ADMIN が自チームの関係を操作する正常系。
            return;
        }
        boolean isPublic = Boolean.TRUE.equals(friend.getIsPublic());
        if (canSeeFrom(friend.getTeamAId(), isPublic, userId) || canSeeFrom(friend.getTeamBId(), isPublic, userId)) {
            return;
        }
        throw new BusinessException(SocialErrorCode.FRIEND_RELATION_NOT_FOUND);
    }

    /**
     * フレンド一覧で、閲覧者が公開済み（{@code is_public=TRUE}）の関係だけに限られるか（SUPPORTER のみの者）を返す。
     *
     * <p>フレンド一覧（{@code GET /teams/{id}/friends}）と本部品の書込前判定が共有する唯一の規則
     * （CMP-260821-1027）。一覧の所属判定そのものは {@link TeamFriendQueryService#listFriends} の
     * {@link AccessControlService#checkMembership} が行い、本部品は同じ {@link AccessControlService#isMember} を使う。</p>
     *
     * @param userId 閲覧者ユーザーID
     * @param teamId 一覧のチームID
     * @return SUPPORTER のみの場合 true（公開済みの関係だけが見える）
     */
    public boolean listsPublicOnly(Long userId, Long teamId) {
        return ROLE_SUPPORTER.equals(accessControlService.getRoleName(userId, teamId, SCOPE_TEAM));
    }

    /** 一覧と同じ規則: 所属者のみ。SUPPORTER のみの者は公開済みの関係に限る。 */
    private boolean canSeeFrom(Long teamId, boolean isPublic, Long userId) {
        if (!accessControlService.isMember(userId, teamId, SCOPE_TEAM)) {
            return false;
        }
        return isPublic || !listsPublicOnly(userId, teamId);
    }
}

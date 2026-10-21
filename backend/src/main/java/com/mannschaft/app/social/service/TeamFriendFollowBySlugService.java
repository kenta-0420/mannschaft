package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FollowTeamResponse;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * フォロー先を slug で指定するフォローの入口（CMP-261012-0315）。
 *
 * <p>検証順は ① 権限（{@code MANAGE_FRIEND_TEAMS}）→ ② slug 解決・フォロー可否（team ドメイン）
 * → ③ {@link TeamFriendsService#follow} への委譲（自チーム・既フォロー・後続処理は共通）。
 * 権限チェックを slug 解決より先に行うのは、権限の無い者に slug の実在を 404 と 403 の差で
 * 教えないため。</p>
 *
 * <p>本クラスは意図的に {@code @Transactional} を持たない。トランザクションとキャッシュ失効は
 * 委譲先の {@link TeamFriendsService#follow} が担う（他ドメイン Repository への推移到達を
 * 新たな入口に増やさないための設計）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamFriendFollowBySlugService {

    private static final String PERM_MANAGE_FRIEND_TEAMS = "MANAGE_FRIEND_TEAMS";
    private static final String SCOPE_TEAM = "TEAM";

    private final AccessControlService accessControlService;
    private final TeamService teamService;
    private final TeamFriendsService teamFriendsService;

    /**
     * フォロー先を slug で指定して自チームが他チームをフォローする。
     *
     * @param teamId         自チーム ID
     * @param targetTeamSlug フォロー先チーム slug
     * @param userId         操作実行者のユーザー ID
     * @return フォロー結果
     * @throws BusinessException 権限不足（403）・対象チーム不存在（アーカイブ・非 ACTIVE 含む。404）・
     *                           自己フォロー（400）・フォロー重複（409）
     */
    public FollowTeamResponse followBySlug(Long teamId, String targetTeamSlug, Long userId) {
        accessControlService.checkPermission(userId, teamId, SCOPE_TEAM, PERM_MANAGE_FRIEND_TEAMS);
        Long targetTeamId = teamService.resolveFollowableTeamId(targetTeamSlug)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_TARGET_TEAM_NOT_FOUND));
        return teamFriendsService.follow(teamId, targetTeamId, userId);
    }
}

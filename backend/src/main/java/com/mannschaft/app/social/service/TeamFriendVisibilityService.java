package com.mannschaft.app.social.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * フレンドチーム公開設定変更サービス（F01.5 Phase 1、リファクタリング第4弾 Phase 4-B で分離）。
 *
 * <p>
 * フレンド関係の公開フラグ（{@code is_public}）操作と関連する監査ログ記録を担当する。
 * </p>
 *
 * <p>
 * 設計書: {@code docs/refactoring/phase4_overview.md} §2
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamFriendVisibilityService {

    /** スコープ識別子（チーム） */
    private static final String SCOPE_TEAM = "TEAM";

    private final TeamFriendRepository teamFriendRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * フレンド関係の公開設定（{@code is_public}）を変更する。
     *
     * <p>
     * 認可: {@code teamId} チームの ADMIN のみ（DEPUTY_ADMIN 不可）。それ以外は 404。
     * 拒否コード {@code FRIEND_VISIBILITY_ADMIN_ONLY} は不在の
     * {@code FRIEND_RELATION_NOT_FOUND} と同じ 404 に写像しており、応答差から
     * teamFriendId の実在を判別されること（存在オラクル）を防いでいる。
     * Phase 1 は単独承認型として、どちらかの ADMIN が {@code TRUE} に切り替えれば
     * 公開となる。Phase 3 で両チーム承認型に昇格予定。
     * </p>
     *
     * @param teamId       自チーム ID
     * @param teamFriendId フレンド関係 ID
     * @param isPublic     公開フラグ
     * @param userId       操作実行者のユーザー ID
     * @throws BusinessException 権限不足・フレンド関係不存在・他チーム関係への操作時
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = "teamFriendList", allEntries = true)
    })
    public void setVisibility(Long teamId, Long teamFriendId, boolean isPublic, Long userId) {
        // 1. ADMIN 権限チェック（DEPUTY_ADMIN 不可）
        if (!accessControlService.isAdmin(userId, teamId, SCOPE_TEAM)) {
            throw new BusinessException(SocialErrorCode.FRIEND_VISIBILITY_ADMIN_ONLY);
        }

        // 2. フレンド関係の取得・IDOR チェック（teamId がフレンドペアの片方であること）
        TeamFriendEntity friend = teamFriendRepository.findById(teamFriendId)
                .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_RELATION_NOT_FOUND));

        if (!friend.getTeamAId().equals(teamId) && !friend.getTeamBId().equals(teamId)) {
            // 所有権のないリソースへの操作は、不在と同じ 404 で返して存在を秘匿する
            // （FRIEND_VISIBILITY_ADMIN_ONLY は 404 に写像済み）。
            throw new BusinessException(SocialErrorCode.FRIEND_VISIBILITY_ADMIN_ONLY);
        }

        boolean before = Boolean.TRUE.equals(friend.getIsPublic());
        friend.changePublicity(isPublic);
        teamFriendRepository.save(friend);

        log.info("フレンド公開設定変更: teamFriendId={}, before={}, after={}, userId={}",
                teamFriendId, before, isPublic, userId);
        recordVisibilityChangeAudit(userId, teamId, teamFriendId, before, isPublic);
    }

    /**
     * 公開設定変更の監査ログを記録する。
     */
    private void recordVisibilityChangeAudit(Long userId, Long teamId,
                                             Long teamFriendId, boolean before, boolean after) {
        auditLogService.record(
                "FRIEND_VISIBILITY_CHANGED", userId, null,
                teamId, null,
                null, null, null,
                String.format(
                        "{\"team_friend_id\":%d,\"is_public_before\":%s,\"is_public_after\":%s}",
                        teamFriendId, before, after));
    }
}

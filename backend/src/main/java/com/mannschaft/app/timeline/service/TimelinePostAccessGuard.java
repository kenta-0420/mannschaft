package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * タイムライン投稿の管理操作（更新・削除・ピン留め切替）に対する認可ゲート（認可根治戦役 Wave7）。
 *
 * <h2>権限粒度</h2>
 * <p>投稿者本人は常に管理できる。加えて、同一ドメインの兄弟（{@code SurveyAccessGuard} /
 * {@code checkOwnerOrAdmin} を採用する各ドメイン）に倣い、<b>TEAM/ORGANIZATION スコープの投稿</b>は
 * そのスコープの ADMIN/DEPUTY_ADMIN も管理できる（{@link AccessControlService#isAdminOrAbove}）。</p>
 *
 * <p>PUBLIC/PERSONAL/VILLAGE/FRIEND_* スコープは {@link AccessControlService} が ADMIN 判定に使う
 * {@code membership.domain.ScopeType} が TEAM/ORGANIZATION の 2 値しか持たないため、これらのスコープに
 * ADMIN 判定を適用すると {@code IllegalArgumentException}（500）になる。したがってこれらのスコープは
 * <b>投稿者本人のみ</b>に限定する（fail-closed。改修前の owner-only 挙動をそのまま維持）。</p>
 *
 * <h2>BOLA 対策</h2>
 * <p>認可に用いるスコープは呼び出し元の申告値ではなく、<b>投稿実体（{@code post.getScopeType()} /
 * {@code post.getScopeId()}）由来</b>である。呼び出し元は必ず投稿 entity を先に取得してから
 * 本ゲートへ渡すこと。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelinePostAccessGuard {

    private final AccessControlService accessControlService;

    /**
     * 指定ユーザーが投稿を管理（更新・削除・ピン留め切替）できるかを検証する。
     *
     * @param userId 操作ユーザー ID
     * @param post   対象投稿（DB 由来の実体）
     * @throws BusinessException 投稿者本人でも TEAM/ORGANIZATION スコープの ADMIN+ でもない場合
     *                           （{@link TimelineErrorCode#NOT_POST_OWNER}）
     */
    public void checkCanManage(Long userId, TimelinePostEntity post) {
        if (userId != null && userId.equals(post.getUserId())) {
            return;
        }
        if (isTeamOrOrganizationScope(post.getScopeType())
                && accessControlService.isAdminOrAbove(userId, post.getScopeId(), post.getScopeType().name())) {
            return;
        }
        throw new BusinessException(TimelineErrorCode.NOT_POST_OWNER);
    }

    private boolean isTeamOrOrganizationScope(PostScopeType scopeType) {
        return scopeType == PostScopeType.TEAM || scopeType == PostScopeType.ORGANIZATION;
    }
}

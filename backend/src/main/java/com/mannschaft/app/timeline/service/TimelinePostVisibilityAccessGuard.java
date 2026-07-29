package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.service.PostingIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * タイムライン投稿の可視性判定を一元化する認可ゲート（認可根治戦役 Wave7）。
 *
 * <p>投稿本体の読取経路（{@link TimelinePostService#getPostDetail} / {@code #getReplies} 等）は
 * 認可根治 Wave3-B7-timeline / Wave6 で既に硬化済みであり、本クラスは<b>そのときに確立した判定
 * ロジックをそのまま抽出した唯一の正準実装</b>である。TIMELINE_POST は F00 共通可視性 Resolver
 * （{@code ContentVisibilityChecker}）の {@code VisibilityResolver} が未実装のため、独自の可視性
 * 述語を新設せず、本クラスへ一本化することで「投稿に付随する子リソース（投票・みたよ！・
 * ブックマーク）が独自の可視性判定を書く」事故を防ぐ。</p>
 *
 * <ul>
 *   <li>PUBLIC: 常に可視</li>
 *   <li>TEAM/ORGANIZATION: 呼び出し元がそのスコープのメンバーであること</li>
 *   <li>VILLAGE: 呼び出し元がその村の現役 USER メンバーであること</li>
 *   <li>PERSONAL: 呼び出し元が投稿者本人であること</li>
 *   <li>FRIEND_FORWARD: 転送先チームのメンバーであること（{@code scope_id} は転送実行チームの
 *       {@code teams.id}）</li>
 *   <li>FRIEND_TEAM / FRIEND_ARCHIVE: 生成経路が存在しない（Phase 3 利用予定の予約値）ため
 *       fail-closed</li>
 * </ul>
 *
 * <p><b>BOLA 対策</b>: 判定に使うスコープは常に <b>DB に永続化された post 自身の scope</b>
 * であり、呼び出し元がクエリ/パスで申告した値ではない。不可視な投稿は
 * {@link TimelineErrorCode#POST_NOT_FOUND}（404）に倒し、対象 ID の実在を秘匿する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelinePostVisibilityAccessGuard {

    private final TimelinePostRepository postRepository;
    private final AccessControlService accessControlService;
    private final PostingIdentityService postingIdentityService;

    /**
     * 投稿 1 件の可視性を判定する（既に取得済みの entity 版）。
     *
     * @param post   判定対象の投稿（DB 由来の実体）
     * @param userId 呼び出し元ユーザー ID
     * @return 可視なら true
     */
    public boolean isVisible(TimelinePostEntity post, Long userId) {
        return switch (post.getScopeType()) {
            case PUBLIC -> true;
            case TEAM -> accessControlService.isMember(userId, post.getScopeId(), "TEAM");
            case ORGANIZATION -> accessControlService.isMember(userId, post.getScopeId(), "ORGANIZATION");
            case VILLAGE -> post.getScopeVillageId() != null
                    && postingIdentityService.isUserVillageMember(post.getScopeVillageId(), userId);
            case PERSONAL -> userId != null && userId.equals(post.getUserId());
            case FRIEND_FORWARD -> accessControlService.isMember(userId, post.getScopeId(), "TEAM");
            case FRIEND_TEAM, FRIEND_ARCHIVE -> false;
        };
    }

    /**
     * 投稿 ID から可視性を検証する（投票・みたよ！・ブックマーク等、投稿に付随する
     * 子リソースの書き込み/読取入口向け）。不可視・不存在は区別せず
     * {@link TimelineErrorCode#POST_NOT_FOUND}（404）に倒す。
     *
     * @param postId 投稿 ID
     * @param userId 呼び出し元ユーザー ID
     * @return 可視な投稿 entity
     * @throws BusinessException 投稿が存在しない、または不可視の場合（POST_NOT_FOUND）
     */
    public TimelinePostEntity requireVisiblePost(Long postId, Long userId) {
        TimelinePostEntity post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND));
        if (!isVisible(post, userId)) {
            throw new BusinessException(TimelineErrorCode.POST_NOT_FOUND);
        }
        return post;
    }
}

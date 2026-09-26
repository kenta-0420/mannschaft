package com.mannschaft.app.timeline.service;

import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * タイムライン投稿を通報（moderation ドメイン）から扱うための最小ファサード（CMP-260917-1135）。
 *
 * <p>通報の宛先スコープ・作成者・控えは、呼び出し元の申告ではなく<b>DB に永続化された投稿自身</b>から
 * 導出する（設計書 F10.1 §content_reports）。閲覧可否は {@link TimelinePostVisibilityAccessGuard}
 * （投稿詳細と同じ正準実装）で判定し、独自の可視性述語は書かない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelinePostModerationService {

    private final TimelinePostRepository postRepository;
    private final TimelinePostVisibilityAccessGuard postVisibilityGuard;

    /**
     * 通報対象の投稿（または返信）を、通報者が閲覧できる場合に限り返す。
     *
     * <p>通報者が<b>実際に閲覧できる</b>投稿に限る。{@link TimelinePostVisibilityAccessGuard#isVisible}
     * はスコープの認可だけを判定し投稿の状態を見ないため、ここで公開中（{@link PostStatus#PUBLISHED}）で
     * あることも要求する（フィード・一覧のクエリと同じ基準。下書き・予約・非表示・削除済みは、同じ
     * スコープの利用者にも見えない）。返信は親投稿も同じ基準で閲覧できることを要求する。</p>
     *
     * <p>存在しない・論理削除済み・公開中でない・閲覧できない・種別（投稿か返信か）が食い違う、の
     * いずれも区別せず空を返す（呼び出し元は同一応答で拒否し、対象の実在と本文を漏らさない）。</p>
     *
     * @param postId         投稿 ID
     * @param reply          {@code true} なら返信（{@code parent_id} あり）、{@code false} なら親投稿を期待する
     * @param reporterUserId 通報者 ID
     * @return 導出した通報対象。閲覧できない等の場合は空
     */
    public Optional<PostReportTarget> findReportTarget(Long postId, boolean reply, Long reporterUserId) {
        return postRepository.findById(postId)
                .filter(post -> (post.getParentId() != null) == reply)
                .filter(post -> isViewable(post, reporterUserId))
                .filter(post -> !reply || postRepository.findById(post.getParentId())
                        .filter(parent -> isViewable(parent, reporterUserId))
                        .isPresent())
                .map(TimelinePostModerationService::toTarget);
    }

    /** 公開中で、かつ通報者のスコープから見える投稿か。 */
    private boolean isViewable(TimelinePostEntity post, Long reporterUserId) {
        return post.getStatus() == PostStatus.PUBLISHED
                && postVisibilityGuard.isVisible(post, reporterUserId);
    }

    private static PostReportTarget toTarget(TimelinePostEntity post) {
        return new PostReportTarget(post.getScopeType().name(), post.getScopeId(),
                post.getUserId(), post.getContent());
    }

    /**
     * 通報対象として導出した投稿の属性。
     *
     * @param scopeType    投稿のスコープ種別（{@code PostScopeType} の名前）
     * @param scopeId      投稿のスコープ ID（VILLAGE・PUBLIC は 0）
     * @param authorUserId 投稿者（システム投稿は null）
     * @param content      本文
     */
    public record PostReportTarget(String scopeType, Long scopeId, Long authorUserId, String content) { }
}

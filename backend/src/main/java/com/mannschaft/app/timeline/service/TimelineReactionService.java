package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.dto.ReactionResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * タイムラインリアクション（みたよ！）サービス。投稿への「みたよ！」の追加・削除を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineReactionService {

    private final TimelinePostReactionRepository reactionRepository;
    private final TimelinePostRepository postRepository;
    /**
     * 認可根治 Wave7: 対象投稿が呼び出し元から可視であることを検証する（投稿本体と同一の正準判定）。
     * リアクションの追加・削除は共有カウンタ（{@code reaction_count}）を書き換える操作であるため、
     * 呼び出し元が可視な投稿に対してのみ実行できるよう先に検証する。
     */
    private final TimelinePostVisibilityAccessGuard postVisibilityGuard;

    /**
     * 投稿に「みたよ！」リアクションを追加する。
     *
     * <p><b>認可根治 Wave7</b>: {@link TimelinePostVisibilityAccessGuard} で対象投稿の可視性を
     * 検証してから処理する（不可視・不存在は {@link TimelineErrorCode#POST_NOT_FOUND}）。</p>
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     * @return レスポンスDTO（みたよ！状態・件数）
     */
    @Transactional
    public ReactionResponse addReaction(Long postId, Long userId) {
        TimelinePostEntity post = postVisibilityGuard.requireVisiblePost(postId, userId);

        if (reactionRepository.existsByTimelinePostIdAndUserId(postId, userId)) {
            throw new BusinessException(TimelineErrorCode.REACTION_ALREADY_EXISTS);
        }

        TimelinePostReactionEntity reaction = TimelinePostReactionEntity.builder()
                .timelinePostId(postId)
                .userId(userId)
                .build();
        reactionRepository.save(reaction);

        post.incrementReactionCount();
        postRepository.save(post);

        long mitayoCount = reactionRepository.countByTimelinePostId(postId);
        log.info("みたよ！追加: postId={}, userId={}", postId, userId);
        return new ReactionResponse(postId, true, (int) mitayoCount);
    }

    /**
     * 投稿の「みたよ！」リアクションを削除する。
     *
     * <p><b>認可根治 Wave7</b>: {@link #addReaction} と対称に対象投稿の可視性を検証する
     * （不可視・不存在は {@link TimelineErrorCode#POST_NOT_FOUND}）。</p>
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     * @return レスポンスDTO（みたよ！状態・件数）
     */
    @Transactional
    public ReactionResponse removeReaction(Long postId, Long userId) {
        TimelinePostEntity post = postVisibilityGuard.requireVisiblePost(postId, userId);

        TimelinePostReactionEntity reaction = reactionRepository
                .findByTimelinePostIdAndUserId(postId, userId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.REACTION_NOT_FOUND));

        reactionRepository.delete(reaction);

        post.decrementReactionCount();
        postRepository.save(post);

        long mitayoCount = reactionRepository.countByTimelinePostId(postId);
        log.info("みたよ！削除: postId={}, userId={}", postId, userId);
        return new ReactionResponse(postId, false, (int) mitayoCount);
    }
}

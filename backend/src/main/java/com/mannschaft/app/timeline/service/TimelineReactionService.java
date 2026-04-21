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
     * 投稿に「みたよ！」リアクションを追加する。
     *
     * @param postId 投稿ID
     * @param userId ユーザーID
     * @return レスポンスDTO（みたよ！状態・件数）
     */
    @Transactional
    public ReactionResponse addReaction(Long postId, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);

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
     * @param postId 投稿ID
     * @param userId ユーザーID
     * @return レスポンスDTO（みたよ！状態・件数）
     */
    @Transactional
    public ReactionResponse removeReaction(Long postId, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);

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

    /**
     * 投稿を取得する。存在しない場合は例外をスローする。
     */
    private TimelinePostEntity findPostOrThrow(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND));
    }
}

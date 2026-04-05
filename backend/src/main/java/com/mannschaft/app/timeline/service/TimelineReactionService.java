package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.TimelineErrorCode;
import com.mannschaft.app.timeline.TimelineMapper;
import com.mannschaft.app.timeline.dto.ReactionResponse;
import com.mannschaft.app.timeline.dto.ReactionSummaryResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.entity.TimelinePostReactionEntity;
import com.mannschaft.app.timeline.repository.TimelinePostReactionRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * タイムラインリアクションサービス。投稿への絵文字リアクションの追加・削除・集計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineReactionService {

    private final TimelinePostReactionRepository reactionRepository;
    private final TimelinePostRepository postRepository;
    private final TimelineMapper timelineMapper;

    /**
     * 投稿にリアクションを追加する。
     *
     * @param postId 投稿ID
     * @param emoji  絵文字
     * @param userId ユーザーID
     * @return 作成されたリアクション
     */
    @Transactional
    public ReactionResponse addReaction(Long postId, String emoji, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);

        reactionRepository.findByTimelinePostIdAndUserIdAndEmoji(postId, userId, emoji)
                .ifPresent(r -> {
                    throw new BusinessException(TimelineErrorCode.REACTION_ALREADY_EXISTS);
                });

        TimelinePostReactionEntity reaction = TimelinePostReactionEntity.builder()
                .timelinePostId(postId)
                .userId(userId)
                .emoji(emoji)
                .build();
        reaction = reactionRepository.save(reaction);

        post.incrementReactionCount();
        postRepository.save(post);

        log.info("リアクション追加: postId={}, emoji={}, userId={}", postId, emoji, userId);
        return timelineMapper.toReactionResponse(reaction);
    }

    /**
     * 投稿のリアクションを削除する。
     *
     * @param postId 投稿ID
     * @param emoji  絵文字
     * @param userId ユーザーID
     */
    @Transactional
    public void removeReaction(Long postId, String emoji, Long userId) {
        TimelinePostEntity post = findPostOrThrow(postId);

        TimelinePostReactionEntity reaction = reactionRepository
                .findByTimelinePostIdAndUserIdAndEmoji(postId, userId, emoji)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.REACTION_NOT_FOUND));

        reactionRepository.delete(reaction);

        post.decrementReactionCount();
        postRepository.save(post);

        log.info("リアクション削除: postId={}, emoji={}, userId={}", postId, emoji, userId);
    }

    /**
     * 投稿のリアクション一覧を取得する。
     *
     * @param postId 投稿ID
     * @return リアクション一覧
     */
    public List<ReactionResponse> getReactions(Long postId) {
        return timelineMapper.toReactionResponseList(
                reactionRepository.findByTimelinePostId(postId));
    }

    /**
     * 投稿のリアクション集計を取得する。
     *
     * @param postId 投稿ID
     * @return 絵文字別リアクション数
     */
    public List<ReactionSummaryResponse> getReactionSummary(Long postId) {
        return reactionRepository.countByPostIdGroupByEmoji(postId)
                .stream()
                .map(row -> new ReactionSummaryResponse((String) row[0], (Long) row[1]))
                .toList();
    }

    /**
     * 投稿を取得する。存在しない場合は例外をスローする。
     */
    private TimelinePostEntity findPostOrThrow(Long postId) {
        return postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(TimelineErrorCode.POST_NOT_FOUND));
    }
}

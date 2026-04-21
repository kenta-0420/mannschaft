package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.dto.BlogReactionResponse;
import com.mannschaft.app.cms.entity.BlogPostReactionEntity;
import com.mannschaft.app.cms.repository.BlogPostReactionRepository;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ブログ記事リアクション（みたよ！）サービス。記事への「みたよ！」の追加・削除を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogReactionService {

    private final BlogPostReactionRepository reactionRepository;
    private final BlogPostRepository postRepository;

    /**
     * 記事に「みたよ！」リアクションを追加する。
     *
     * @param blogPostId 記事ID
     * @param userId     ユーザーID
     * @return レスポンスDTO（みたよ！状態・件数）
     */
    @Transactional
    public BlogReactionResponse addReaction(Long blogPostId, Long userId) {
        // 記事の存在確認
        postRepository.findById(blogPostId)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));

        if (reactionRepository.existsByBlogPostIdAndUserId(blogPostId, userId)) {
            throw new BusinessException(CmsErrorCode.REACTION_ALREADY_EXISTS);
        }

        BlogPostReactionEntity reaction = BlogPostReactionEntity.builder()
                .blogPostId(blogPostId)
                .userId(userId)
                .build();
        reactionRepository.save(reaction);

        long mitayoCount = reactionRepository.countByBlogPostId(blogPostId);
        log.info("ブログみたよ！追加: blogPostId={}, userId={}", blogPostId, userId);
        return new BlogReactionResponse(blogPostId, true, (int) mitayoCount);
    }

    /**
     * 記事の「みたよ！」リアクションを削除する。
     *
     * @param blogPostId 記事ID
     * @param userId     ユーザーID
     * @return レスポンスDTO（みたよ！状態・件数）
     */
    @Transactional
    public BlogReactionResponse removeReaction(Long blogPostId, Long userId) {
        // 記事の存在確認
        postRepository.findById(blogPostId)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));

        if (!reactionRepository.existsByBlogPostIdAndUserId(blogPostId, userId)) {
            throw new BusinessException(CmsErrorCode.REACTION_NOT_FOUND);
        }

        reactionRepository.deleteByBlogPostIdAndUserId(blogPostId, userId);

        long mitayoCount = reactionRepository.countByBlogPostId(blogPostId);
        log.info("ブログみたよ！削除: blogPostId={}, userId={}", blogPostId, userId);
        return new BlogReactionResponse(blogPostId, false, (int) mitayoCount);
    }

    /**
     * 記事のリアクション状態を取得する。
     *
     * @param blogPostId 記事ID
     * @param userId     ユーザーID（nullの場合はみたよ=false）
     * @return レスポンスDTO（みたよ！状態・件数）
     */
    public BlogReactionResponse getReactionStatus(Long blogPostId, Long userId) {
        long mitayoCount = reactionRepository.countByBlogPostId(blogPostId);
        boolean mitayo = userId != null && reactionRepository.existsByBlogPostIdAndUserId(blogPostId, userId);
        return new BlogReactionResponse(blogPostId, mitayo, (int) mitayoCount);
    }
}

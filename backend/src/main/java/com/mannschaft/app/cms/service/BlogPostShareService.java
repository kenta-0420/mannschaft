package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostShareEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostShareRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ブログ記事の共有・プレビュートークンサービス。
 *
 * <p>外部公開系の操作（チーム/組織への共有、プレビュートークン発行/失効）を担当する。
 * リファクタリング第10弾で {@link BlogPostService} から分離。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogPostShareService {

    private final BlogPostRepository postRepository;
    private final BlogPostShareRepository shareRepository;
    private final CmsMapper cmsMapper;

    /**
     * 個人ブログ記事をチーム/組織に共有する。
     */
    @Transactional
    public SharePostResponse sharePost(Long postId, Long userId, SharePostRequest request) {
        BlogPostEntity entity = findPostOrThrow(postId);

        // ソーシャルプロフィール名義の記事は共有不可
        if (entity.getSocialProfileId() != null) {
            throw new BusinessException(CmsErrorCode.SOCIAL_PROFILE_SHARE_NOT_ALLOWED);
        }

        // 重複チェック
        if (request.getTeamId() != null) {
            shareRepository.findByBlogPostIdAndTeamId(postId, request.getTeamId())
                    .ifPresent(s -> { throw new BusinessException(CmsErrorCode.DUPLICATE_SHARE); });
        } else if (request.getOrganizationId() != null) {
            shareRepository.findByBlogPostIdAndOrganizationId(postId, request.getOrganizationId())
                    .ifPresent(s -> { throw new BusinessException(CmsErrorCode.DUPLICATE_SHARE); });
        }

        BlogPostShareEntity share = BlogPostShareEntity.builder()
                .blogPostId(postId)
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .sharedBy(userId)
                .build();
        BlogPostShareEntity saved = shareRepository.save(share);

        log.info("記事共有: postId={}, shareId={}", postId, saved.getId());
        return new SharePostResponse(saved.getId(), postId, saved.getTeamId(), saved.getOrganizationId());
    }

    /**
     * 共有を取り消す。
     */
    @Transactional
    public void revokeShare(Long postId, Long shareId) {
        BlogPostShareEntity share = shareRepository.findById(shareId)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.SHARE_NOT_FOUND));

        if (!share.getBlogPostId().equals(postId)) {
            throw new BusinessException(CmsErrorCode.SHARE_NOT_FOUND);
        }

        shareRepository.delete(share);
        log.info("共有取消: postId={}, shareId={}", postId, shareId);
    }

    /**
     * プレビュートークンを発行する。
     */
    @Transactional
    public BlogPostResponse issuePreviewToken(Long id) {
        BlogPostEntity entity = findPostOrThrow(id);
        if (entity.getStatus() == PostStatus.PUBLISHED) {
            throw new BusinessException(CmsErrorCode.ALREADY_PUBLISHED);
        }

        String token = java.util.UUID.randomUUID().toString().replace("-", "") +
                java.util.UUID.randomUUID().toString().replace("-", "");
        entity.setPreviewToken(token, LocalDateTime.now().plusHours(24));
        BlogPostEntity saved = postRepository.save(entity);
        log.info("プレビュートークン発行: postId={}", id);
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * プレビュートークンを無効化する。
     */
    @Transactional
    public void revokePreviewToken(Long id) {
        BlogPostEntity entity = findPostOrThrow(id);
        entity.setPreviewToken(null, null);
        postRepository.save(entity);
        log.info("プレビュートークン無効化: postId={}", id);
    }

    /**
     * 記事エンティティを取得する。存在しない場合は例外をスローする。
     */
    private BlogPostEntity findPostOrThrow(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
    }
}

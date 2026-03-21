package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.UpdateBlogPostRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.entity.BlogPostTagEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostRevisionRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ブログ記事サービス。記事のCRUD・公開制御・リビジョン管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogPostService {

    private final BlogPostRepository postRepository;
    private final BlogPostTagRepository postTagRepository;
    private final BlogPostRevisionRepository revisionRepository;
    private final CmsMapper cmsMapper;

    /**
     * チーム別記事一覧をページング取得する。
     */
    public Page<BlogPostResponse> listByTeam(Long teamId, Pageable pageable) {
        Page<BlogPostEntity> page = postRepository.findByTeamIdOrderByPinnedDescCreatedAtDesc(teamId, pageable);
        return page.map(cmsMapper::toBlogPostResponse);
    }

    /**
     * 組織別記事一覧をページング取得する。
     */
    public Page<BlogPostResponse> listByOrganization(Long organizationId, Pageable pageable) {
        Page<BlogPostEntity> page = postRepository.findByOrganizationIdOrderByPinnedDescCreatedAtDesc(organizationId, pageable);
        return page.map(cmsMapper::toBlogPostResponse);
    }

    /**
     * 個人ブログ記事一覧をページング取得する。
     */
    public Page<BlogPostResponse> listByUser(Long userId, Pageable pageable) {
        Page<BlogPostEntity> page = postRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        return page.map(cmsMapper::toBlogPostResponse);
    }

    /**
     * slug で記事を取得する。
     */
    public BlogPostResponse getBySlug(Long teamId, Long organizationId, Long userId, String slug) {
        BlogPostEntity entity;
        if (teamId != null) {
            entity = postRepository.findByTeamIdAndSlug(teamId, slug)
                    .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
        } else if (organizationId != null) {
            entity = postRepository.findByOrganizationIdAndSlug(organizationId, slug)
                    .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
        } else {
            entity = postRepository.findByUserIdAndSlug(userId, slug)
                    .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
        }
        return cmsMapper.toBlogPostResponse(entity);
    }

    /**
     * 記事詳細を取得する。
     */
    public BlogPostResponse getById(Long id) {
        BlogPostEntity entity = findPostOrThrow(id);
        return cmsMapper.toBlogPostResponse(entity);
    }

    /**
     * 記事を作成する。
     */
    @Transactional
    public BlogPostResponse createPost(Long userId, CreateBlogPostRequest request) {
        PostType postType = request.getPostType() != null
                ? PostType.valueOf(request.getPostType()) : PostType.BLOG;
        Visibility visibility = request.getVisibility() != null
                ? Visibility.valueOf(request.getVisibility()) : Visibility.MEMBERS_ONLY;
        PostPriority priority = request.getPriority() != null
                ? PostPriority.valueOf(request.getPriority()) : PostPriority.NORMAL;

        String slug = request.getSlug() != null ? request.getSlug() : generateSlug(request.getTitle());
        short readingTime = calculateReadingTime(request.getBody());

        BlogPostEntity entity = BlogPostEntity.builder()
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .userId(request.getTeamId() == null && request.getOrganizationId() == null ? userId : null)
                .socialProfileId(request.getSocialProfileId())
                .authorId(userId)
                .title(request.getTitle())
                .slug(slug)
                .body(request.getBody())
                .excerpt(request.getExcerpt())
                .coverImageUrl(request.getCoverImageUrl())
                .postType(postType)
                .visibility(visibility)
                .priority(priority)
                .publishedAt(request.getPublishedAt())
                .archiveAt(request.getArchiveAt())
                .crossPostToTimeline(request.getCrossPostToTimeline() != null && request.getCrossPostToTimeline())
                .readingTimeMinutes(readingTime)
                .seriesId(request.getSeriesId())
                .seriesOrder(request.getSeriesOrder())
                .build();

        BlogPostEntity saved = postRepository.save(entity);

        // タグ紐付け
        if (request.getTagIds() != null) {
            for (Long tagId : request.getTagIds()) {
                postTagRepository.save(new BlogPostTagEntity(saved.getId(), tagId));
            }
        }

        log.info("記事作成: postId={}, slug={}", saved.getId(), saved.getSlug());
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * 記事を更新する。
     */
    @Transactional
    public BlogPostResponse updatePost(Long id, Long userId, UpdateBlogPostRequest request) {
        BlogPostEntity entity = findPostOrThrow(id);

        Visibility visibility = request.getVisibility() != null
                ? Visibility.valueOf(request.getVisibility()) : entity.getVisibility();
        PostPriority priority = request.getPriority() != null
                ? PostPriority.valueOf(request.getPriority()) : entity.getPriority();
        String slug = request.getSlug() != null ? request.getSlug() : entity.getSlug();
        short readingTime = calculateReadingTime(request.getBody());

        // PUBLISHED 記事を再編集する場合、リビジョンを自動保存
        if (entity.getStatus() == PostStatus.PUBLISHED) {
            saveRevision(entity, userId);
        }

        entity.update(request.getTitle(), slug, request.getBody(), request.getExcerpt(),
                request.getCoverImageUrl(), visibility, priority, readingTime);

        // タグの再紐付け
        if (request.getTagIds() != null) {
            postTagRepository.deleteByBlogPostId(id);
            for (Long tagId : request.getTagIds()) {
                postTagRepository.save(new BlogPostTagEntity(id, tagId));
            }
        }

        BlogPostEntity saved = postRepository.save(entity);
        log.info("記事更新: postId={}", id);
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * 公開ステータスを変更する。
     */
    @Transactional
    public BlogPostResponse changeStatus(Long id, PublishRequest request) {
        BlogPostEntity entity = findPostOrThrow(id);
        PostStatus newStatus = PostStatus.valueOf(request.getStatus());

        if (newStatus == PostStatus.REJECTED && (request.getRejectionReason() == null || request.getRejectionReason().isBlank())) {
            throw new BusinessException(CmsErrorCode.REJECTION_REASON_REQUIRED);
        }

        switch (newStatus) {
            case PUBLISHED -> entity.publish(request.getPublishedAt() != null ? request.getPublishedAt() : LocalDateTime.now());
            case REJECTED -> entity.reject(request.getRejectionReason());
            default -> entity.changeStatus(newStatus);
        }

        BlogPostEntity saved = postRepository.save(entity);
        log.info("記事ステータス変更: postId={}, status={}", id, newStatus);
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * 記事を論理削除する。
     */
    @Transactional
    public void deletePost(Long id) {
        BlogPostEntity entity = findPostOrThrow(id);
        entity.softDelete();
        postRepository.save(entity);
        log.info("記事削除: postId={}", id);
    }

    /**
     * 記事を複製する。
     */
    @Transactional
    public BlogPostResponse duplicatePost(Long id, Long userId) {
        BlogPostEntity original = findPostOrThrow(id);
        String newSlug = generateSlug(original.getTitle() + "-copy");
        short readingTime = calculateReadingTime(original.getBody());

        BlogPostEntity copy = BlogPostEntity.builder()
                .teamId(original.getTeamId())
                .organizationId(original.getOrganizationId())
                .userId(original.getUserId())
                .authorId(userId)
                .title(original.getTitle() + "（コピー）")
                .slug(newSlug)
                .body(original.getBody())
                .excerpt(original.getExcerpt())
                .coverImageUrl(original.getCoverImageUrl())
                .postType(original.getPostType())
                .visibility(original.getVisibility())
                .readingTimeMinutes(readingTime)
                .seriesId(original.getSeriesId())
                .build();

        BlogPostEntity saved = postRepository.save(copy);

        // タグのコピー
        List<BlogPostTagEntity> tags = postTagRepository.findByBlogPostId(id);
        for (BlogPostTagEntity tag : tags) {
            postTagRepository.save(new BlogPostTagEntity(saved.getId(), tag.getBlogTagId()));
        }

        log.info("記事複製: originalId={}, newId={}", id, saved.getId());
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * リビジョン一覧を取得する。
     */
    public List<com.mannschaft.app.cms.dto.RevisionResponse> listRevisions(Long postId) {
        findPostOrThrow(postId);
        return cmsMapper.toRevisionResponseList(
                revisionRepository.findByBlogPostIdOrderByCreatedAtDesc(postId));
    }

    /**
     * リビジョンから復元する。
     */
    @Transactional
    public BlogPostResponse restoreRevision(Long postId, Long revisionId, Long userId) {
        BlogPostEntity entity = findPostOrThrow(postId);
        BlogPostRevisionEntity revision = revisionRepository.findById(revisionId)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.REVISION_NOT_FOUND));

        // 現在の状態をリビジョンとして保存
        saveRevision(entity, userId);

        // 復元
        entity.update(revision.getTitle(), entity.getSlug(), revision.getBody(),
                entity.getExcerpt(), entity.getCoverImageUrl(), entity.getVisibility(),
                entity.getPriority(), calculateReadingTime(revision.getBody()));
        entity.changeStatus(PostStatus.DRAFT);

        BlogPostEntity saved = postRepository.save(entity);
        log.info("リビジョン復元: postId={}, revisionId={}", postId, revisionId);
        return cmsMapper.toBlogPostResponse(saved);
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
    BlogPostEntity findPostOrThrow(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
    }

    /**
     * リビジョンを保存する。
     */
    private void saveRevision(BlogPostEntity entity, Long editorId) {
        long count = revisionRepository.countByBlogPostId(entity.getId());

        // 10版を超える場合は最古のリビジョンを削除
        if (count >= 10) {
            revisionRepository.findFirstByBlogPostIdOrderByRevisionNumberAsc(entity.getId())
                    .ifPresent(revisionRepository::delete);
        }

        BlogPostRevisionEntity revision = BlogPostRevisionEntity.builder()
                .blogPostId(entity.getId())
                .revisionNumber((int) count + 1)
                .title(entity.getTitle())
                .body(entity.getBody())
                .editorId(editorId)
                .build();
        revisionRepository.save(revision);
    }

    /**
     * 推定読了時間を算出する（日本語: 500文字/分、最小1分）。
     */
    private short calculateReadingTime(String body) {
        if (body == null || body.isEmpty()) {
            return 1;
        }
        int charCount = body.length();
        int minutes = (int) Math.ceil((double) charCount / 500);
        return (short) Math.max(1, minutes);
    }

    /**
     * slug を自動生成する。
     */
    private String generateSlug(String title) {
        // 英数字とハイフンのみ、それ以外はnanoidベースで生成
        String base = title.replaceAll("[^a-zA-Z0-9\\-]", "").toLowerCase();
        if (base.isEmpty()) {
            base = java.util.UUID.randomUUID().toString().substring(0, 12);
        }
        return base.substring(0, Math.min(base.length(), 180));
    }
}

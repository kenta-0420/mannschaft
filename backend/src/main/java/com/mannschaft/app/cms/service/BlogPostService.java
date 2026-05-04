package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.dto.AutoSaveRequest;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.BulkActionRequest;
import com.mannschaft.app.cms.dto.BulkActionResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
import com.mannschaft.app.cms.dto.UpdateBlogPostRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostRevisionEntity;
import com.mannschaft.app.cms.entity.BlogPostShareEntity;
import com.mannschaft.app.cms.entity.BlogPostTagEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostRevisionRepository;
import com.mannschaft.app.cms.repository.BlogPostShareRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final BlogPostShareRepository shareRepository;
    private final CmsMapper cmsMapper;
    private final ContentVisibilityChecker contentVisibilityChecker;

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
     *
     * <p>F00 Phase B (設計書 §12.3): 可視性判定を
     * {@link ContentVisibilityChecker#assertCanView} に委譲する。
     * 閲覧不可の場合は {@link com.mannschaft.app.common.BusinessException}
     * ({@code VISIBILITY_001} = 403 / {@code VISIBILITY_004} = 404 相当) を投げる。
     */
    public BlogPostResponse getById(Long id) {
        // 実存確認 + 可視性判定を ContentVisibilityChecker に一元化する。
        // viewerUserId が null（未認証）の場合は PUBLIC かつ PUBLISHED の記事のみ可。
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        contentVisibilityChecker.assertCanView(ReferenceType.BLOG_POST, id, viewerUserId);
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
     * 下書きを自動保存する（エディタ30秒間隔）。
     */
    @Transactional
    public BlogPostResponse autoSave(Long id, Long userId, AutoSaveRequest request) {
        BlogPostEntity entity = findPostOrThrow(id);

        if (request.getTitle() != null) {
            entity.update(request.getTitle(), entity.getSlug(),
                    request.getBody() != null ? request.getBody() : entity.getBody(),
                    request.getExcerpt() != null ? request.getExcerpt() : entity.getExcerpt(),
                    entity.getCoverImageUrl(), entity.getVisibility(), entity.getPriority(),
                    request.getBody() != null ? calculateReadingTime(request.getBody()) : entity.getReadingTimeMinutes());
        } else if (request.getBody() != null) {
            entity.update(entity.getTitle(), entity.getSlug(), request.getBody(),
                    request.getExcerpt() != null ? request.getExcerpt() : entity.getExcerpt(),
                    entity.getCoverImageUrl(), entity.getVisibility(), entity.getPriority(),
                    calculateReadingTime(request.getBody()));
        }

        BlogPostEntity saved = postRepository.save(entity);
        log.info("自動保存: postId={}", id);
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * 一括ステータス変更を実行する。
     */
    @Transactional
    public BulkActionResponse bulkAction(BulkActionRequest request) {
        if (request.getIds().size() > 50) {
            throw new BusinessException(CmsErrorCode.BULK_LIMIT_EXCEEDED);
        }

        List<Long> skippedIds = new ArrayList<>();
        int processedCount = 0;

        for (Long id : request.getIds()) {
            BlogPostEntity entity = postRepository.findById(id).orElse(null);
            if (entity == null) {
                skippedIds.add(id);
                continue;
            }

            switch (request.getAction().toUpperCase()) {
                case "ARCHIVE" -> {
                    if (entity.getStatus() == PostStatus.PUBLISHED) {
                        entity.changeStatus(PostStatus.ARCHIVED);
                        postRepository.save(entity);
                        processedCount++;
                    } else {
                        skippedIds.add(id);
                    }
                }
                case "DELETE" -> {
                    entity.softDelete();
                    postRepository.save(entity);
                    processedCount++;
                }
                case "PUBLISH" -> {
                    if (entity.getStatus() == PostStatus.DRAFT) {
                        entity.publish(LocalDateTime.now());
                        postRepository.save(entity);
                        processedCount++;
                    } else {
                        skippedIds.add(id);
                    }
                }
                default -> skippedIds.add(id);
            }
        }

        log.info("一括操作: action={}, processed={}, skipped={}", request.getAction(), processedCount, skippedIds.size());
        return new BulkActionResponse(processedCount, skippedIds, request.getAction());
    }

    /**
     * RSS/Atomフィード用の公開記事一覧を取得する。
     */
    public List<BlogPostResponse> listPublicPostsForFeed(Long teamId, Long organizationId) {
        List<BlogPostEntity> entities;
        if (teamId != null) {
            entities = postRepository.findTop20ByTeamIdAndStatusAndVisibilityOrderByPublishedAtDesc(
                    teamId, PostStatus.PUBLISHED, Visibility.PUBLIC);
        } else {
            entities = postRepository.findTop20ByOrganizationIdAndStatusAndVisibilityOrderByPublishedAtDesc(
                    organizationId, PostStatus.PUBLISHED, Visibility.PUBLIC);
        }
        return cmsMapper.toBlogPostResponseList(entities);
    }

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
     * セルフレビュー結果を処理する。
     */
    @Transactional
    public BlogPostResponse selfReview(Long postId, Long userId, SelfReviewRequest request) {
        BlogPostEntity entity = findPostOrThrow(postId);

        if (entity.getStatus() != PostStatus.PENDING_SELF_REVIEW) {
            throw new BusinessException(CmsErrorCode.INVALID_STATUS_TRANSITION);
        }

        switch (request.getAction().toUpperCase()) {
            case "PUBLISH" -> entity.publish(LocalDateTime.now());
            case "DRAFT" -> entity.changeStatus(PostStatus.DRAFT);
            case "DELETE" -> entity.softDelete();
            default -> throw new BusinessException(CmsErrorCode.INVALID_STATUS_TRANSITION);
        }

        BlogPostEntity saved = postRepository.save(entity);
        log.info("セルフレビュー: postId={}, action={}", postId, request.getAction());
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * slug でプレビュートークン付き記事を取得する。
     */
    public BlogPostResponse getBySlugWithPreviewToken(Long teamId, Long organizationId, Long userId,
                                                       String slug, String previewToken) {
        BlogPostResponse response = getBySlug(teamId, organizationId, userId, slug);
        // プレビュートークン検証はgetBySlug内で将来実装
        // 現時点ではパラメータを受け取るのみ
        return response;
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

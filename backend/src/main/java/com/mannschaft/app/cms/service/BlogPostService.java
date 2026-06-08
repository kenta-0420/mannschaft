package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.cms.CmsMapper;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
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
import com.mannschaft.app.cms.dto.RevisionResponse;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import com.mannschaft.app.cms.dto.SharePostRequest;
import com.mannschaft.app.cms.dto.SharePostResponse;
import com.mannschaft.app.cms.dto.UpdateBlogPostRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogPostTagEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.service.PostAuthorSnapshotService;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ブログ記事サービス（ファサード）。
 *
 * <p>記事 CRUD・公開制御を担当する。リファクタリング第10弾で次のサブサービスへ責務分離した:
 * <ul>
 *   <li>{@link BlogPostRevisionService} — リビジョン履歴の取得/復元/保存</li>
 *   <li>{@link BlogPostShareService} — 共有・プレビュートークン</li>
 * </ul>
 *
 * <p>本クラスは Controller から呼ばれる public シグネチャを完全維持し、
 * リビジョン・共有系のメソッドは委譲先サブサービスへそのまま転送する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlogPostService {

    private final BlogPostRepository postRepository;
    private final BlogPostTagRepository postTagRepository;
    private final CmsMapper cmsMapper;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final BlogPostRevisionService revisionService;
    private final BlogPostShareService shareService;
    // TODO: publicview ドメインが cms ドメインを参照（CLAUDE.md 原則5）。将来はイベント駆動化を検討。
    private final PostAuthorSnapshotService postAuthorSnapshotService;
    // TODO: cms ドメインが team/organization ドメインを参照（CLAUDE.md 原則5）。将来はイベント駆動化を検討。
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final AccessControlService accessControlService;

    /**
     * チーム別記事一覧をページング取得する。
     *
     * @param teamIdStr チームの公開ID（UUID文字列）または内部Long ID文字列
     */
    public Page<BlogPostResponse> listByTeam(String teamIdStr, Pageable pageable) {
        if (teamIdStr == null) {
            return Page.empty(pageable);
        }
        Long teamId = resolveTeamId(teamIdStr);
        Page<BlogPostEntity> page = postRepository.findByTeamIdOrderByPinnedDescCreatedAtDesc(teamId, pageable);
        return page.map(cmsMapper::toBlogPostResponse);
    }

    /**
     * 組織別記事一覧をページング取得する。
     *
     * @param organizationIdStr 組織の公開ID（UUID文字列）または内部Long ID文字列。null の場合は空ページを返す。
     */
    public Page<BlogPostResponse> listByOrganization(String organizationIdStr, Pageable pageable) {
        if (organizationIdStr == null) {
            return Page.empty(pageable);
        }
        Long organizationId = resolveOrganizationId(organizationIdStr);
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

        // teamId / organizationId の文字列（UUID or Long文字列）を内部Long IDに解決する
        Long resolvedTeamId = request.getTeamId() != null ? resolveTeamId(request.getTeamId()) : null;
        Long resolvedOrgId = request.getOrganizationId() != null ? resolveOrganizationId(request.getOrganizationId()) : null;

        // メンバーシップチェック: チームまたは組織への帰属確認（非メンバーは403）
        if (resolvedTeamId != null) {
            accessControlService.checkMembership(userId, resolvedTeamId, "TEAM");
        } else if (resolvedOrgId != null) {
            accessControlService.checkMembership(userId, resolvedOrgId, "ORGANIZATION");
        }

        // F19.1 Phase 2: チーム/組織が REAL_NAME モードの場合に投稿者本名スナップショットを取得する（§4.7 非対称切替ルール対応）
        String authorRealNameSnapshot;
        if (resolvedTeamId != null) {
            authorRealNameSnapshot = postAuthorSnapshotService.resolveForTeamPost(resolvedTeamId, userId);
        } else if (resolvedOrgId != null) {
            authorRealNameSnapshot = postAuthorSnapshotService.resolveForOrganizationPost(resolvedOrgId, userId);
        } else {
            authorRealNameSnapshot = null;
        }

        BlogPostEntity entity = BlogPostEntity.builder()
                .teamId(resolvedTeamId)
                .organizationId(resolvedOrgId)
                .userId(resolvedTeamId == null && resolvedOrgId == null ? userId : null)
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
                .authorRealNameSnapshot(authorRealNameSnapshot)
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
            revisionService.saveRevision(entity, userId);
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
     * リビジョン一覧を取得する（{@link BlogPostRevisionService} へ委譲）。
     */
    public List<RevisionResponse> listRevisions(Long postId) {
        return revisionService.listRevisions(postId);
    }

    /**
     * リビジョンから復元する（{@link BlogPostRevisionService} へ委譲）。
     */
    public BlogPostResponse restoreRevision(Long postId, Long revisionId, Long userId) {
        return revisionService.restoreRevision(postId, revisionId, userId);
    }

    /**
     * プレビュートークンを発行する（{@link BlogPostShareService} へ委譲）。
     */
    public BlogPostResponse issuePreviewToken(Long id) {
        return shareService.issuePreviewToken(id);
    }

    /**
     * プレビュートークンを無効化する（{@link BlogPostShareService} へ委譲）。
     */
    public void revokePreviewToken(Long id) {
        shareService.revokePreviewToken(id);
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
     * RSS/Atom フィード用の公開記事一覧を取得する。
     *
     * <p>F00 Phase E: 旧 Visibility.PUBLIC 直接フィルタを廃止し、
     * {@link ContentVisibilityChecker#filterAccessible} に可視性判定を委譲する。
     * これにより VisibilityTemplate による細粒度アクセス制御が正しく適用される。
     */
    public List<BlogPostResponse> listPublicPostsForFeed(Long teamId, Long organizationId) {
        List<BlogPostEntity> all;
        if (teamId != null) {
            all = postRepository.findTop20ByTeamIdAndStatusOrderByPublishedAtDesc(
                    teamId, PostStatus.PUBLISHED);
        } else {
            all = postRepository.findTop20ByOrganizationIdAndStatusOrderByPublishedAtDesc(
                    organizationId, PostStatus.PUBLISHED);
        }
        if (all.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = all.stream().map(BlogPostEntity::getId).collect(Collectors.toSet());
        Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.BLOG_POST, ids, null);
        List<BlogPostEntity> filtered = all.stream()
                .filter(e -> accessibleIds.contains(e.getId()))
                .collect(Collectors.toList());
        return cmsMapper.toBlogPostResponseList(filtered);
    }

    /**
     * 個人ブログ記事をチーム/組織に共有する（{@link BlogPostShareService} へ委譲）。
     */
    public SharePostResponse sharePost(Long postId, Long userId, SharePostRequest request) {
        return shareService.sharePost(postId, userId, request);
    }

    /**
     * 共有を取り消す（{@link BlogPostShareService} へ委譲）。
     */
    public void revokeShare(Long postId, Long shareId) {
        shareService.revokeShare(postId, shareId);
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
     * F19.1 Phase 7: 投稿の public_visible フラグを切り替える。
     *
     * <p>投稿者本人のみ操作可能。それ以外は {@link PublicViewErrorCode#PUBLIC_011}（403）を返す。</p>
     *
     * <p>TODO: BlogPostService (cms ドメイン) が PublicViewErrorCode (publicview ドメイン) を参照している。
     *          将来はイベント駆動化 or cms ドメイン内に独自エラーコードを定義することで解消する。</p>
     *
     * @param postId        対象 BlogPost の ID
     * @param requestUserId 操作ユーザー ID
     * @param publicVisible true=公開ページに表示 / false=非表示
     * @throws BusinessException 記事が存在しない場合（CMS_001、404）
     * @throws BusinessException 投稿者本人以外が操作した場合（PUBLIC_011、403）
     */
    @Transactional
    public void patchPublicVisible(Long postId, Long requestUserId, boolean publicVisible) {
        BlogPostEntity post = findPostOrThrow(postId);
        if (!post.getAuthorId().equals(requestUserId)) {
            throw new BusinessException(PublicViewErrorCode.PUBLIC_011);
        }
        post.updatePublicVisible(publicVisible);
        postRepository.save(post);
        log.info("public_visible 更新: postId={}, publicVisible={}, userId={}", postId, publicVisible, requestUserId);
    }

    /**
     * 記事エンティティを取得する。存在しない場合は例外をスローする。
     */
    BlogPostEntity findPostOrThrow(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
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

    /**
     * チームID文字列（UUID文字列 or Long文字列）を内部Long IDに解決する。
     *
     * <p>後方互換のため Long 文字列（数値文字列）も受け入れる。
     * UUID形式の場合は {@link TeamRepository#findByPublicId} で publicId から内部IDを引く。</p>
     *
     * @param idStr チームの公開ID（UUID文字列）または内部Long ID文字列
     * @return 内部Long ID
     * @throws BusinessException チームが見つからない場合（CMS_024）
     * @throws BusinessException 不正なID形式の場合（CMS_024）
     */
    private Long resolveTeamId(String idStr) {
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            try {
                UUID uuid = UUID.fromString(idStr);
                return teamRepository.findByPublicId(uuid)
                        .orElseThrow(() -> new BusinessException(CmsErrorCode.TEAM_NOT_FOUND))
                        .getId();
            } catch (IllegalArgumentException iae) {
                throw new BusinessException(CmsErrorCode.TEAM_NOT_FOUND);
            }
        }
    }

    /**
     * 組織ID文字列（UUID文字列 or Long文字列）を内部Long IDに解決する。
     *
     * <p>後方互換のため Long 文字列（数値文字列）も受け入れる。
     * UUID形式の場合は {@link OrganizationRepository#findByPublicId} で publicId から内部IDを引く。</p>
     *
     * @param idStr 組織の公開ID（UUID文字列）または内部Long ID文字列
     * @return 内部Long ID
     * @throws BusinessException 組織が見つからない場合（CMS_025）
     * @throws BusinessException 不正なID形式の場合（CMS_025）
     */
    private Long resolveOrganizationId(String idStr) {
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            try {
                UUID uuid = UUID.fromString(idStr);
                return organizationRepository.findByPublicId(uuid)
                        .orElseThrow(() -> new BusinessException(CmsErrorCode.ORG_NOT_FOUND))
                        .getId();
            } catch (IllegalArgumentException iae) {
                throw new BusinessException(CmsErrorCode.ORG_NOT_FOUND);
            }
        }
    }
}

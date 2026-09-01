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
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.media.BlogMediaScope;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostTagRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.payment.service.ContentAccessState;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.publicview.service.PostAuthorSnapshotService;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
    // TODO: cms ドメインが payment ドメインを参照（CLAUDE.md 原則5）。クロスドメイン FK は張らず
    //       PaymentGateService のメソッド呼び（ID 渡し）に限定する。将来はイベント駆動化を検討。
    private final PaymentGateService paymentGateService;

    /** 表示経路でのみ使用する本文メディア解決部品（編集経路では絶対に呼ばない。{@link #getMyPostById} 参照）。 */
    private final BlogBodyMediaResolver blogBodyMediaResolver;

    /**
     * チーム別記事一覧をページング取得する。
     *
     * <p>認可根治戦役 Wave7: 本一覧は下書き・非公開ステータスを含む全記事を返す
     * 内部管理用の入口のため、{@link #createPost} と同一の
     * {@link AccessControlService#checkMembership} でチームメンバーに限定する。</p>
     *
     * @param teamIdStr チームの公開ID（UUID文字列）または内部Long ID文字列
     */
    public Page<BlogPostResponse> listByTeam(String teamIdStr, Pageable pageable) {
        if (teamIdStr == null) {
            return Page.empty(pageable);
        }
        Long teamId = resolveTeamId(teamIdStr);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), teamId, "TEAM");
        return scanVisiblePage(pageable, SecurityUtils.getCurrentUserIdOrNull(),
                request -> postRepository.findByTeamIdOrderByPinnedDescCreatedAtDesc(teamId, request));
    }

    /**
     * 組織別記事一覧をページング取得する。
     *
     * <p>認可根治戦役 Wave7: {@link #listByTeam} と同一の理由で
     * {@link AccessControlService#checkMembership} を敷く。</p>
     *
     * @param organizationIdStr 組織の公開ID（UUID文字列）または内部Long ID文字列。null の場合は空ページを返す。
     */
    public Page<BlogPostResponse> listByOrganization(String organizationIdStr, Pageable pageable) {
        if (organizationIdStr == null) {
            return Page.empty(pageable);
        }
        Long organizationId = resolveOrganizationId(organizationIdStr);
        accessControlService.checkMembership(SecurityUtils.getCurrentUserId(), organizationId, "ORGANIZATION");
        return scanVisiblePage(pageable, SecurityUtils.getCurrentUserIdOrNull(),
                request -> postRepository.findByOrganizationIdOrderByPinnedDescCreatedAtDesc(organizationId, request));
    }

    /**
     * 個人ブログ記事一覧をページング取得する。
     *
     * <p>認可根治戦役 Wave7: {@link #getBySlug} と同一の F00 可視性判定
     * （{@link ContentVisibilityChecker#filterAccessible}）で閲覧可能な記事のみへ絞り込む。
     * 本人が自分の一覧（{@code listMyPosts}）を見る場合は、Resolver が DRAFT を
     * 作成者本人に可視と判定するため、自分の下書きも見える。</p>
     */
    public Page<BlogPostResponse> listByUser(Long userId, Pageable pageable) {
        return scanVisiblePage(pageable, SecurityUtils.getCurrentUserIdOrNull(),
                request -> postRepository.findByUserIdOrderByCreatedAtDesc(userId, request));
    }

    private Page<BlogPostResponse> scanVisiblePage(Pageable pageable, Long viewerUserId,
                                                   Function<Pageable, Page<BlogPostEntity>> source) {
        int scanSize = Math.max(100, pageable.getPageSize());
        long offset = pageable.getOffset();
        long visibleTotal = 0;
        boolean systemAdmin = viewerUserId != null && accessControlService.isSystemAdmin(viewerUserId);
        List<BlogPostResponse> result = new ArrayList<>();
        for (int scanPage = 0; ; scanPage++) {
            Page<BlogPostEntity> page = source.apply(PageRequest.of(scanPage, scanSize));
            List<BlogPostEntity> content = page.getContent();
            if (content.isEmpty()) break;
            Set<Long> ids = content.stream().map(BlogPostEntity::getId).collect(Collectors.toSet());
            Set<Long> accessibleIds = contentVisibilityChecker.filterAccessible(
                    ReferenceType.BLOG_POST, ids, viewerUserId);
            Map<Long, ContentGateTarget> targets = content.stream()
                    .map(BlogPostService::targetEntry)
                    .flatMap(Optional::stream)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            Map<Long, GateCheckResponse> gates = paymentGateService.checkAccessBatch(
                    ContentGateType.POST, content.stream().map(BlogPostEntity::getId).toList(), viewerUserId, targets);
            for (BlogPostEntity entity : content) {
                if (!accessibleIds.contains(entity.getId())) continue;
                BlogPostResponse response = applyListPaywall(entity, systemAdmin,
                        gates == null ? null : gates.get(entity.getId()));
                if (response == null) continue;
                long index = visibleTotal++;
                if (index >= offset && result.size() < pageable.getPageSize()) result.add(response);
            }
            if (!page.hasNext()) break;
        }
        return new PageImpl<>(result, pageable, visibleTotal);
    }

    /**
     * 認証ユーザー自身のブログ記事をID指定で取得する（ステータス不問・削除済み除外）。
     *
     * <p>投稿者本人以外がアクセスした場合は POST_NOT_FOUND を返す（IDOR 対策）。</p>
     *
     * <p><b>【重要】本文メディアの署名 URL 解決を結線してはならない</b>: 本メソッドは
     * 編集画面（{@code pages/blog/posts/[id]/edit.vue}）専用の入口であり、<b>生の r2Key を
     * そのまま返すのが正しい</b>。ここで署名 URL へ解決すると、利用者が編集して保存した瞬間に
     * 期限付きの署名 URL が {@code blog_posts.body} へ永続保存され、数十分後に記事の画像が
     * 恒久的に壊れる（保存直後は正常に見えるため発見が遅れる）。解決漏れではない。</p>
     *
     * @param postId 記事 ID
     * @param userId 認証ユーザー ID
     * @return 該当する BlogPostResponse
     * @throws BusinessException 記事が存在しない、または著者が一致しない場合（CMS_001、404）
     */
    public BlogPostResponse getMyPostById(Long postId, Long userId) {
        BlogPostEntity entity = postRepository.findByIdAndAuthorIdAndDeletedAtIsNull(postId, userId)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
        return cmsMapper.toBlogPostResponse(entity);
    }

    /**
     * slug で記事を取得する。
     *
     * <p>F00 可視性認可（{@link #getById} と同一挙動）: slug→entity 解決直後に
     * {@link ContentVisibilityChecker#assertCanView} を呼び、閲覧不可なら
     * {@link com.mannschaft.app.common.BusinessException}（{@code VISIBILITY_001}=403 /
     * {@code VISIBILITY_004}=404 相当）を投げる。これを欠くと slug 経由で他人の
     * MEMBERS_ONLY/DRAFT 記事が漏洩する（実機E2Eで捕捉した認可漏洩バグ）。
     * viewerUserId は認証コンテキストから取得する（リクエスト引数 {@code userId} は
     * スコープ解決用であり閲覧者IDではないため使用しない）。</p>
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
        // 可視性判定を ContentVisibilityChecker に一元化（getById と完全に同じ認可挙動）。
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        contentVisibilityChecker.assertCanView(ReferenceType.BLOG_POST, entity.getId(), viewerUserId);
        // 可視性(F00)通過の「後段」でペイウォール本文ゲートを適用する（可視性 deny が優先）。
        // 表示経路なので、マスクを免れた本文のみ r2Key を署名 URL へ解決する。
        return resolveBodyMedia(
                applyPaywallMask(cmsMapper.toBlogPostResponse(entity), entity, viewerUserId), entity);
    }

    /**
     * 記事詳細を取得する。
     *
     * <p>F00 Phase B (設計書 §12.3): 可視性判定を
     * {@link ContentVisibilityChecker#assertCanView} に委譲する。
     * 閲覧不可の場合は {@link com.mannschaft.app.common.BusinessException}
     * ({@code VISIBILITY_001} = 403 / {@code VISIBILITY_004} = 404 相当) を投げる。
     *
     * <p><b>注意（本文メディアの解決）</b>: 本メソッドは現状どの Controller からも呼ばれていない
     * （呼び出し元はテストのみ）ため、本文メディアの署名 URL 解決を結線していない。
     * <b>表示経路として Controller に繋ぐ場合は、{@link #getBySlug} と同様に
     * {@code resolveBodyMedia} を適用すること</b>。適用を忘れると本文に生の r2Key
     * （{@code blog/TEAM/12/x.png}）がそのまま返り、画像が表示されない。</p>
     */
    public BlogPostResponse getById(Long id) {
        // 実存確認 + 可視性判定を ContentVisibilityChecker に一元化する。
        // viewerUserId が null（未認証）の場合は PUBLIC かつ PUBLISHED の記事のみ可。
        Long viewerUserId = SecurityUtils.getCurrentUserIdOrNull();
        contentVisibilityChecker.assertCanView(ReferenceType.BLOG_POST, id, viewerUserId);
        BlogPostEntity entity = findPostOrThrow(id);
        // 可視性(F00)通過の「後段」でペイウォール本文ゲートを適用する（可視性 deny が優先）。
        return applyPaywallMask(cmsMapper.toBlogPostResponse(entity), entity, viewerUserId);
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
        checkWriteAccess(entity, userId);

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
    public BlogPostResponse changeStatus(Long id, Long userId, PublishRequest request) {
        BlogPostEntity entity = findPostOrThrow(id);
        checkWriteAccess(entity, userId);
        PostStatus newStatus = PostStatus.valueOf(request.getStatus());

        if (newStatus == PostStatus.REJECTED && (request.getRejectionReason() == null || request.getRejectionReason().isBlank())) {
            throw new BusinessException(CmsErrorCode.REJECTION_REASON_REQUIRED);
        }

        // 基準時刻は 1 回だけ取得し、公開判定と非公開化判定で同一の値を使う
        // （エンティティ側は現在時刻を取得しない。CMP-023 / DateTimeAndZoneGuardTest）。
        LocalDateTime baseTime = LocalDateTime.now();

        switch (newStatus) {
            // 予約公開（issue #2616・AC-1〜3）: publishedAt が未来なら BlogPostEntity#publish が
            // DRAFT に据え置き、published_at だけを記録する（PostStatus.SCHEDULED は新設しない）。
            case PUBLISHED -> entity.publish(request.getPublishedAt(), baseTime);
            case REJECTED -> entity.reject(request.getRejectionReason());
            default -> entity.changeStatus(newStatus, baseTime);
        }

        BlogPostEntity saved = postRepository.save(entity);
        log.info("記事ステータス変更: postId={}, status={}", id, newStatus);
        return cmsMapper.toBlogPostResponse(saved);
    }

    /**
     * 記事を論理削除する。
     */
    @Transactional
    public void deletePost(Long id, Long userId) {
        BlogPostEntity entity = findPostOrThrow(id);
        checkWriteAccess(entity, userId);
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
        checkWriteAccess(original, userId);
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
     * プレビュートークンを発行する（{@link BlogPostShareService} へ委譲。認可判定も委譲先で実施）。
     */
    public BlogPostResponse issuePreviewToken(Long id, Long userId) {
        return shareService.issuePreviewToken(id, userId);
    }

    /**
     * プレビュートークンを無効化する（{@link BlogPostShareService} へ委譲。認可判定も委譲先で実施）。
     */
    public void revokePreviewToken(Long id, Long userId) {
        shareService.revokePreviewToken(id, userId);
    }

    /**
     * 下書きを自動保存する（エディタ30秒間隔）。
     */
    @Transactional
    public BlogPostResponse autoSave(Long id, Long userId, AutoSaveRequest request) {
        BlogPostEntity entity = findPostOrThrow(id);
        checkWriteAccess(entity, userId);

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
    public BulkActionResponse bulkAction(BulkActionRequest request, Long userId) {
        if (request.getIds().size() > 50) {
            throw new BusinessException(CmsErrorCode.BULK_LIMIT_EXCEEDED);
        }

        List<Long> skippedIds = new ArrayList<>();
        int processedCount = 0;
        // 一括操作は全件を同一の基準時刻で判定する（処理中に時刻が跨いで挙動が割れないように）。
        LocalDateTime baseTime = LocalDateTime.now();

        for (Long id : request.getIds()) {
            BlogPostEntity entity = postRepository.findById(id).orElse(null);
            if (entity == null) {
                skippedIds.add(id);
                continue;
            }
            // 認可根治戦役 Wave3-B7: 一括操作は対象記事ごとに所有者/スコープADMINを検証する。
            // 非所有者かつ非ADMINの記事が1件でも含まれる場合は即座に403で全体を中断する
            // （fail-closed。部分適用による越境操作の既成事実化を防ぐ）。
            checkWriteAccess(entity, userId);

            switch (request.getAction().toUpperCase()) {
                case "ARCHIVE" -> {
                    if (entity.getStatus() == PostStatus.PUBLISHED) {
                        entity.changeStatus(PostStatus.ARCHIVED, baseTime);
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
                        // 予約公開（issue #2616・AC-17）: 既に未来の published_at を持つ記事は
                        // 「予約済み」であり、一括公開で予約時刻より前に公開してはならない。
                        // 予約時刻をそのまま渡すことで BlogPostEntity#publish が DRAFT に据え置く。
                        entity.publish(entity.getPublishedAt(), baseTime);
                        if (entity.getStatus() == PostStatus.PUBLISHED) {
                            postRepository.save(entity);
                            processedCount++;
                        } else {
                            // 予約中はスキップ扱い。公開はバッチが予約時刻に行う。
                            skippedIds.add(id);
                        }
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
        // フィード（RSS/Atom）は本文を出力しないが、有料本文が DTO に載るのを防ぐため一覧同様 body を落とす。
        return cmsMapper.toBlogPostResponseList(filtered).stream()
                .map(this::stripBody)
                .collect(Collectors.toList());
    }

    /**
     * 個人ブログ記事をチーム/組織に共有する（{@link BlogPostShareService} へ委譲。認可判定も委譲先で実施）。
     */
    public SharePostResponse sharePost(Long postId, Long userId, SharePostRequest request) {
        return shareService.sharePost(postId, userId, request);
    }

    /**
     * 共有を取り消す（{@link BlogPostShareService} へ委譲。認可判定も委譲先で実施）。
     */
    public void revokeShare(Long postId, Long shareId, Long userId) {
        shareService.revokeShare(postId, shareId, userId);
    }

    /**
     * セルフレビュー結果を処理する。
     */
    @Transactional
    public BlogPostResponse selfReview(Long postId, Long userId, SelfReviewRequest request) {
        BlogPostEntity entity = findPostOrThrow(postId);
        checkWriteAccess(entity, userId);

        if (entity.getStatus() != PostStatus.PENDING_SELF_REVIEW) {
            throw new BusinessException(CmsErrorCode.INVALID_STATUS_TRANSITION);
        }

        LocalDateTime baseTime = LocalDateTime.now();

        switch (request.getAction().toUpperCase()) {
            // 予約公開（issue #2616・AC-17）: 予約時刻を持つ記事はその時刻を尊重し、
            // 未来ならセルフレビュー承認後も DRAFT へ据え置いてバッチの公開を待つ。
            case "PUBLISH" -> entity.publish(entity.getPublishedAt(), baseTime);
            case "DRAFT" -> entity.changeStatus(PostStatus.DRAFT, baseTime);
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
     * ペイウォール本文ゲートを適用する（F08.9 漏洩根治）。
     *
     * <p>判定の単一真実源は対象スコープ付きの {@link PaymentGateService#checkAccess(String, Long, Long, ContentGateTarget)}
     * （content-gates/check と同一）。FE 専用ペイウォールではバイパス漏洩するため、
     * 可視性(F00)通過の<b>後段</b>で BE 側でも本文をマスクする。</p>
     *
     * <ul>
     *   <li>SystemAdmin だけがゲートを迂回できる。著者本人は通常どおり評価する。</li>
     *   <li>{@code accessible=false} のとき body=null。{@code titleHidden=true} なら title も null。
     *       {@code excerpt}/{@code coverImageUrl}/{@code title} はプレビュー素材として残す。</li>
     *   <li>fail-closed: {@code checkAccess} が例外または評価不能なら非表示として扱う。</li>
     * </ul>
     *
     * @param dto          マッピング済みレスポンス
     * @param entity       元エンティティ（authorId / id 参照用）
     * @param viewerUserId 閲覧者ユーザー ID（未認証は null）
     * @return ゲート適用後のレスポンス
     */
    private BlogPostResponse applyPaywallMask(BlogPostResponse dto, BlogPostEntity entity, Long viewerUserId) {
        // SystemAdmin だけがゲートを迂回できる。著者本人は通常どおり評価する。
        if (viewerUserId != null && accessControlService.isSystemAdmin(viewerUserId)) {
            return dto.withAccessState(ContentAccessState.FULL.name());
        }

        GateCheckResponse gate;
        try {
            gate = paymentGateService.checkAccess(ContentGateType.POST, entity.getId(), viewerUserId,
                    targetOf(entity));
        } catch (Exception e) {
            // 評価不能（例外）→ null 扱いで fail-closed 経路へ統一する。
            log.warn("ペイウォール判定失敗（記事詳細）: postId={} → fail-closed 判定へ", entity.getId(), e);
            gate = null;
        }

        // checkAccess が null／例外のいずれでも、存在を秘匿して fail-closed にする。
        if (gate == null) {
            throw new BusinessException(CmsErrorCode.POST_NOT_FOUND);
        }
        if (gate.isAccessible()) {
            // ゲートなし or 課金済 → 全文
            return dto.withAccessState(ContentAccessState.FULL.name());
        }
        // 未課金: 未充足ゲートのtitleHiddenだけで存在秘匿を決める。
        if (gate.isTitleHidden()) {
            throw new BusinessException(CmsErrorCode.POST_NOT_FOUND);
        }
        // LOCKED は title と最小限の状態だけ。本文・要約・カバー・メディアを返さない。
        return maskContent(dto, false).withAccessState(ContentAccessState.LOCKED.name());
    }

    /**
     * 表示経路の本文について、生の r2Key を署名付き表示 URL へ解決した新インスタンスを返す。
     *
     * <p>ペイウォールでマスクされた本文（{@code body == null}）は解決しない。
     * マスクを解決処理で復活させてはならないためである。</p>
     *
     * <p><b>編集経路（{@link #getMyPostById}）では呼ばないこと</b>。署名 URL には有効期限があり、
     * 編集画面が受け取った署名 URL がそのまま保存されると {@code blog_posts.body} へ
     * 期限付き URL が永続保存され、数十分後に記事の画像が恒久的に壊れる。</p>
     */
    private BlogPostResponse resolveBodyMedia(BlogPostResponse dto, BlogPostEntity entity) {
        BlogPostResponse.BlogPostContentDto content = dto.getContent();
        if (content == null || content.body() == null) {
            return dto;
        }
        BlogMediaScope scope = BlogMediaScope.of(
                entity.getTeamId(), entity.getOrganizationId(), entity.getUserId());
        if (scope == null) {
            log.warn("本文メディア: 記事のスコープを判定できないため解決を見送る: postId={}", entity.getId());
            return dto;
        }
        String resolvedBody = blogBodyMediaResolver.resolveBody(
                content.body(), scope.scopeType(), scope.scopeId());
        if (resolvedBody == null || resolvedBody.equals(content.body())) {
            return dto;
        }
        BlogPostResponse.BlogPostContentDto resolved = new BlogPostResponse.BlogPostContentDto(
                content.title(),
                content.slug(),
                resolvedBody,
                content.excerpt(),
                content.coverImageUrl());
        return dto.toBuilder().content(resolved).build();
    }

    /**
     * 本文（および任意で title）をマスクした新インスタンスを返す（{@code withReaction} と同じ toBuilder パターン）。
     */
    private BlogPostResponse maskContent(BlogPostResponse dto, boolean maskTitle) {
        BlogPostResponse.BlogPostContentDto c = dto.getContent();
        if (c == null) {
            return dto;
        }
        BlogPostResponse.BlogPostContentDto masked = new BlogPostResponse.BlogPostContentDto(
                maskTitle ? null : c.title(),
                c.slug(),
                null, // body をマスク（@JsonInclude(NON_NULL) でフィールド消滅）
                null,
                null);
        return dto.toBuilder().content(masked).build();
    }

    /**
     * 一覧用に body のみを落とした新インスタンスを返す（一覧は本文を一切返さない）。
     */
    private BlogPostResponse stripBody(BlogPostResponse dto) {
        BlogPostResponse.BlogPostContentDto c = dto.getContent();
        if (c == null || c.body() == null) {
            return dto;
        }
        BlogPostResponse.BlogPostContentDto stripped = new BlogPostResponse.BlogPostContentDto(
                c.title(), c.slug(), null, c.excerpt(), c.coverImageUrl());
        return dto.toBuilder().content(stripped).build();
    }

    /** 一覧の課金軸適用。HIDDENは除外し、LOCKEDはタイトルと状態だけ残す。 */
    private BlogPostResponse applyListPaywall(
            BlogPostEntity entity, boolean systemAdmin, GateCheckResponse gate) {
        BlogPostResponse dto = stripBody(cmsMapper.toBlogPostResponse(entity));
        if (systemAdmin) {
            return dto.withAccessState(ContentAccessState.FULL.name());
        }
        if (gate == null) {
            return null;
        }
        if (gate.isAccessible()) {
            return dto.withAccessState(ContentAccessState.FULL.name());
        }
        if (gate.isTitleHidden()) {
            return null;
        }
        return maskContent(dto, false).withAccessState(ContentAccessState.LOCKED.name());
    }

    /**
     * コンテンツの実在スコープを支払い判定へ渡す。
     */
    private static ContentGateTarget targetOf(BlogPostEntity entity) {
        if (entity == null || entity.getId() == null) return null;
        return new ContentGateTarget(entity.getId(), entity.getTeamId(), entity.getOrganizationId());
    }

    private static Optional<Map.Entry<Long, ContentGateTarget>> targetEntry(BlogPostEntity entity) {
        ContentGateTarget target = targetOf(entity);
        return target == null ? Optional.empty() : Optional.of(Map.entry(entity.getId(), target));
    }

    /**
     * 記事エンティティを取得する。存在しない場合は例外をスローする。
     */
    BlogPostEntity findPostOrThrow(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(CmsErrorCode.POST_NOT_FOUND));
    }

    /**
     * 記事書込操作の認可を検証する（認可根治戦役 Wave3-B7）。
     *
     * <p>投稿者本人（{@code authorId == actorUserId}）はスコープ種別を問わず許可する。
     * それ以外は、記事が所属するスコープ（teamId優先→organizationId）の ADMIN/DEPUTY_ADMIN
     * であることを {@link AccessControlService#checkAdminOrAbove} で要求する（違反時 403 = COMMON_002）。
     * 個人ブログ（teamId/organizationId ともに null）で非所有者の場合はスコープ判定不能のため
     * 直接 403（COMMON_002）とする。</p>
     *
     * @param entity      対象記事エンティティ
     * @param actorUserId 操作ユーザー ID
     * @throws BusinessException 非所有者かつスコープ ADMIN 未満の場合（COMMON_002、403）
     */
    private void checkWriteAccess(BlogPostEntity entity, Long actorUserId) {
        if (actorUserId != null && actorUserId.equals(entity.getAuthorId())) {
            return;
        }
        if (entity.getTeamId() != null) {
            accessControlService.checkAdminOrAbove(actorUserId, entity.getTeamId(), "TEAM");
        } else if (entity.getOrganizationId() != null) {
            accessControlService.checkAdminOrAbove(actorUserId, entity.getOrganizationId(), "ORGANIZATION");
        } else {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
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
     * チームID文字列（スラッグ or Long文字列）を内部Long IDに解決する。
     *
     * <p>後方互換のため Long 文字列（数値文字列）も受け入れる。
     * 数値でない場合はスラッグとして {@link com.mannschaft.app.team.repository.TeamRepository#findBySlugAndDeletedAtIsNull}
     * から内部IDを引く。</p>
     *
     * @param idStr チームのスラッグまたは内部Long ID文字列
     * @return 内部Long ID
     * @throws BusinessException チームが見つからない場合（CMS_024）
     */
    private Long resolveTeamId(String idStr) {
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return teamRepository.findBySlugAndDeletedAtIsNull(idStr)
                    .orElseThrow(() -> new BusinessException(CmsErrorCode.TEAM_NOT_FOUND))
                    .getId();
        }
    }

    /**
     * 組織ID文字列（スラッグ or Long文字列）を内部Long IDに解決する。
     *
     * <p>後方互換のため Long 文字列（数値文字列）も受け入れる。
     * 数値でない場合はスラッグとして {@link com.mannschaft.app.organization.repository.OrganizationRepository#findBySlugAndDeletedAtIsNull}
     * から内部IDを引く。</p>
     *
     * @param idStr 組織のスラッグまたは内部Long ID文字列
     * @return 内部Long ID
     * @throws BusinessException 組織が見つからない場合（CMS_025）
     */
    private Long resolveOrganizationId(String idStr) {
        try {
            return Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return organizationRepository.findBySlugAndDeletedAtIsNull(idStr)
                    .orElseThrow(() -> new BusinessException(CmsErrorCode.ORG_NOT_FOUND))
                    .getId();
        }
    }
}

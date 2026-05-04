package com.mannschaft.app.cms.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.CmsVisibilityMapper;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * BlogPost 用 {@link com.mannschaft.app.common.visibility.ContentVisibilityResolver}。
 *
 * <p>F00 共通可視性基盤 Phase B 第 1 弾。設計書
 * {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §12.3 に従い、
 * {@link AbstractContentVisibilityResolver} の最小契約 (loadProjections / toStandard /
 * toContentStatus) のみを実装する。SystemAdmin 高速パス・status × visibility 合成・
 * 親 ORG 連鎖・監査ログ連携・メトリクスは基底クラスで一括対応される。
 */
@Component
public class BlogPostVisibilityResolver
        extends AbstractContentVisibilityResolver<Visibility, BlogPostVisibilityProjection> {

    private final BlogPostRepository blogPostRepository;

    public BlogPostVisibilityResolver(
            BlogPostRepository blogPostRepository,
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.blogPostRepository = blogPostRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.BLOG_POST;
    }

    @Override
    protected List<BlogPostVisibilityProjection> loadProjections(Collection<Long> ids) {
        return blogPostRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(Visibility v) {
        return CmsVisibilityMapper.toStandard(v);
    }

    @Override
    protected ContentStatus toContentStatus(BlogPostVisibilityProjection row) {
        return mapStatus(row.status());
    }

    /**
     * {@link PostStatus} → {@link ContentStatus} の写像。
     *
     * <ul>
     *   <li>{@code PUBLISHED} → {@link ContentStatus#PUBLISHED}</li>
     *   <li>{@code ARCHIVED} → {@link ContentStatus#ARCHIVED}</li>
     *   <li>{@code DRAFT} / {@code PENDING_REVIEW} / {@code PENDING_SELF_REVIEW} / {@code REJECTED}
     *       → {@link ContentStatus#DRAFT}（公開前 / 取り下げ → 作成者と SystemAdmin のみ可視）</li>
     * </ul>
     *
     * <p>論理削除 ({@code deleted_at IS NOT NULL}) は射影段階の WHERE 句で除外されるため、
     * {@link ContentStatus#DELETED} への写像は不要（実存しない ID として NOT_FOUND 扱い）。
     */
    private static ContentStatus mapStatus(PostStatus status) {
        if (status == null) {
            // fail-closed: status 不明は DRAFT 扱い (基底側で SystemAdmin/作成者のみ可視)
            return ContentStatus.DRAFT;
        }
        return switch (status) {
            case PUBLISHED -> ContentStatus.PUBLISHED;
            case ARCHIVED -> ContentStatus.ARCHIVED;
            case DRAFT, PENDING_REVIEW, PENDING_SELF_REVIEW, REJECTED -> ContentStatus.DRAFT;
        };
    }
}

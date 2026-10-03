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
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.CmsVisibilityMapper;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.spi.ContentGateTarget;

/**
 * BlogPost 用 {@link com.mannschaft.app.common.visibility.ContentVisibilityResolver}。
 *
 * <p>F00 共通可視性基盤 Phase B 第 1 弾。設計書
 * {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §12.3 に従い、
 * {@link AbstractContentVisibilityResolver} の最小契約 (loadProjections / toStandard /
 * toContentStatus) のみを実装する。SystemAdmin 高速パス・status × visibility 合成・
 * 親 ORG 連鎖・監査ログ連携・メトリクスは基底クラスで一括対応される。
 *
 * <p><strong>F08.9 P4b — ペイウォール連結</strong>: {@link Visibility#CUSTOM} が付与されたブログ記事は
 * {@link StandardVisibility#CUSTOM} に写像され、本クラスの {@link #evaluateCustom} が呼ばれる。
 * {@link PaymentGateService#checkAccess} で受益者キー（閲覧者本人）の支払い状態を評価し、
 * アクセス可否を決定する（設計書 F08.9 02 §6 / F00 §5.1.4）。</p>
 */
@Component
public class BlogPostVisibilityResolver
        extends AbstractContentVisibilityResolver<Visibility, BlogPostVisibilityProjection> {

    // F08.9 P4b: content_payment_gates.content_type の値は ContentGateType.POST = "POST"
    private static final String CONTENT_TYPE_BLOG_POST = ContentGateType.POST;

    private final BlogPostRepository blogPostRepository;
    private final PaymentGateService paymentGateService;

    public BlogPostVisibilityResolver(
            BlogPostRepository blogPostRepository,
            PaymentGateService paymentGateService,
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.blogPostRepository = blogPostRepository;
        this.paymentGateService = paymentGateService;
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
     * F08.9 P4b — ペイウォール解錠判定（{@link StandardVisibility#CUSTOM} 経路）。
     *
     * <p>{@link Visibility#CUSTOM} が付与されたブログ記事のみここに到達する。
     * {@link PaymentGateService#checkAccess} で閲覧者本人（受益者キー）の支払い状態を評価する。</p>
     *
     * <p><strong>fail-closed 設計</strong>: {@code viewerUserId} が {@code null}（未認証）、
     * または {@code row} / {@code row.id()} が {@code null} の場合は閲覧拒否側に倒す。</p>
     *
     * @param row          判定対象の Projection
     * @param viewerUserId 閲覧者 user_id（{@code null} 可、未認証 = fail-closed）
     * @param snapshot     メンバーシップスナップショット（本メソッドでは不使用）
     * @return ペイウォール解錠済みなら {@code true}（ゲートなし＝誰でも閲覧可を含む）
     */
    @Override
    protected boolean evaluateCustom(
            BlogPostVisibilityProjection row,
            Long viewerUserId,
            UserScopeRoleSnapshot snapshot) {
        if (viewerUserId == null || row == null || row.id() == null) {
            // 未認証またはデータ不整合 → fail-closed（漏洩より過剰遮断・03_security §4）
            return false;
        }
        return paymentGateService.checkAccess(CONTENT_TYPE_BLOG_POST, row.id(), viewerUserId,
                targetOf(row))
                .isAccessible();
    }

    /** F08.9 Phase 1: visibilityとは独立した課金軸をAND合成する。 */
    @Override
    protected boolean visibleByAdditionalAxis(
            BlogPostVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot,
            StandardVisibility level, Object additionalAxisContext) {
        if (level == StandardVisibility.CUSTOM) {
            return true; // 既存CUSTOM経路の判定を重複させない
        }
        if (isScopeAdmin(row, snapshot)) {
            return true;
        }
        if (!(additionalAxisContext instanceof Map<?, ?> gates) || row == null || row.id() == null) {
            // バッチ評価コンテキストを構築できない場合も、対象スコープ付きの単票判定で fail-closed を保つ。
            GateCheckResponse fallback = paymentGateService.checkAccess(
                    CONTENT_TYPE_BLOG_POST, row.id(), viewerUserId, targetOf(row));
            return fallback != null && fallback.isAccessible();
        }
        // バッチ結果に ID が無い場合は評価不能として fail-closed にする。
        if (!gates.containsKey(row.id())) {
            return false;
        }
        Object value = gates.get(row.id());
        return value instanceof GateCheckResponse response
                && (response.isAccessible() || !response.isTitleHidden());
    }

    @Override
    protected Object prepareAdditionalAxisContext(
            List<BlogPostVisibilityProjection> rows, Long viewerUserId) {
        return paymentGateService.checkAccessBatch(CONTENT_TYPE_BLOG_POST,
                rows.stream().map(BlogPostVisibilityProjection::id).toList(), viewerUserId,
                rows.stream().filter(row -> targetOf(row) != null)
                        .collect(java.util.stream.Collectors.toMap(
                                BlogPostVisibilityProjection::id,
                                BlogPostVisibilityResolver::targetOf)));
    }

    private static ContentGateTarget targetOf(BlogPostVisibilityProjection row) {
        if (row == null || row.id() == null) {
            return null;
        }
        if (row.scopeType() == null && row.scopeId() == null) {
            return new ContentGateTarget(row.id(), null, null);
        }
        if (row.scopeType() == null || row.scopeId() == null) {
            return null;
        }
        return "TEAM".equals(row.scopeType())
                ? new ContentGateTarget(row.id(), row.scopeId(), null)
                : "ORGANIZATION".equals(row.scopeType())
                    ? new ContentGateTarget(row.id(), null, row.scopeId()) : null;
    }

    private static boolean isScopeAdmin(BlogPostVisibilityProjection row, UserScopeRoleSnapshot snapshot) {
        if (snapshot == null || snapshot.isSystemAdmin() || row == null
                || row.scopeType() == null || row.scopeId() == null) {
            return snapshot != null && snapshot.isSystemAdmin();
        }
        String role = snapshot.roleByScope().get(
                new com.mannschaft.app.common.visibility.ScopeKey(row.scopeType(), row.scopeId()));
        if ("ADMIN".equals(role)) {
            return true;
        }
        if ("ORGANIZATION".equals(row.scopeType())) {
            role = snapshot.orgRoleByScope().get(
                    new com.mannschaft.app.common.visibility.ScopeKey(row.scopeType(), row.scopeId()));
            return "ADMIN".equals(role);
        }
        return false;
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

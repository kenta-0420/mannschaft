package com.mannschaft.app.organization.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase D-δ — {@link ReferenceType#ORGANIZATION} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §7.5 / §11.6 / §15 D-13/D-14/D-16。</p>
 *
 * <p><strong>feature flag による段階展開制御</strong>:
 * {@code feature.visibility-resolver.organization=true} を設定した環境でのみ本 Bean が登録される。
 * デフォルト ({@code false}) では Bean 未登録のため、{@link com.mannschaft.app.common.visibility.ContentVisibilityChecker}
 * は ORGANIZATION 型をフォールバック (fail-closed) で処理する。</p>
 *
 * <p><strong>機能側 visibility との StandardVisibility マッピング</strong>（§5.2）:</p>
 * <ul>
 *   <li>{@link OrganizationEntity.Visibility#PUBLIC} → {@link StandardVisibility#PUBLIC}
 *       （誰でも閲覧可）</li>
 *   <li>{@link OrganizationEntity.Visibility#PRIVATE} → {@link StandardVisibility#ADMINS_ONLY}
 *       （組織管理者のみ閲覧可。組織は MEMBERS_ONLY 相当の中間公開概念を持たず、
 *       PUBLIC/PRIVATE の 2 値のみのため、PRIVATE は最も制限的な ADMINS_ONLY にマッピングする。
 *       Phase D-δ 設計方針: Resolver 未稼働状態から切り替える際の誤公開リスクを最小化するため
 *       保守的な ADMINS_ONLY を採用。）</li>
 * </ul>
 *
 * <p><strong>status × visibility 合成</strong>（§7.5）:</p>
 * <ul>
 *   <li>{@code deletedAt != null} → {@link ContentStatus#DELETED}（誰も不可視、fail-closed）</li>
 *   <li>{@code archivedAt != null} → {@link ContentStatus#ARCHIVED}（SystemAdmin のみ可視）</li>
 *   <li>それ以外 → {@link ContentStatus#PUBLISHED}（visibility 評価へ）</li>
 * </ul>
 *
 * <p><strong>制約</strong>（§15 D-14 / D-16）:</p>
 * <ul>
 *   <li>{@code AccessControlService} の 12 メソッドに一切触れない（D-14）。</li>
 *   <li>他 Resolver を inject せず、必要であれば
 *       {@link com.mannschaft.app.common.visibility.ContentVisibilityChecker} を通じて参照する（D-16）。</li>
 *   <li>本クラスには {@code @Transactional} を付与してはならない。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "feature.visibility-resolver.organization", havingValue = "true", matchIfMissing = false)
public class OrganizationVisibilityResolver
        extends AbstractContentVisibilityResolver<OrganizationEntity.Visibility, OrganizationVisibilityProjection> {

    private final OrganizationRepository organizationRepository;

    public OrganizationVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            OrganizationRepository organizationRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.organizationRepository = organizationRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.ORGANIZATION;
    }

    @Override
    protected List<OrganizationVisibilityProjection> loadProjections(Collection<Long> ids) {
        return organizationRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(OrganizationEntity.Visibility visibility) {
        return switch (visibility) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            // PRIVATE は組織管理者のみ閲覧可（保守的マッピング）
            // 組織は PUBLIC/PRIVATE の 2 値のみで MEMBERS_ONLY 相当の中間概念を持たないため
            // ADMINS_ONLY を採用し誤公開リスクを最小化する（Phase D-δ 設計方針）
            case PRIVATE -> StandardVisibility.ADMINS_ONLY;
        };
    }

    @Override
    protected ContentStatus toContentStatus(OrganizationVisibilityProjection row) {
        if (row.deletedAt() != null) {
            return ContentStatus.DELETED;
        }
        if (row.archivedAt() != null) {
            return ContentStatus.ARCHIVED;
        }
        return ContentStatus.PUBLISHED;
    }

    /**
     * 組織には CUSTOM 経路がないため、デフォルトの fail-closed ({@code false}) を使用する。
     */
    @Override
    protected boolean evaluateCustom(
            OrganizationVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        return false;
    }
}

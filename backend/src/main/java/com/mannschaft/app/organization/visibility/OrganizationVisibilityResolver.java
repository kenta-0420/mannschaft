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
import com.mannschaft.app.common.visibility.mapping.OrganizationVisibilityMapper;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase D-δ — {@link ReferenceType#ORGANIZATION} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §7.5 / §11.6 / §15 D-13/D-14/D-16。
 * また F19.1 公開チーム・組織ページ §7.2 / §17.1 で「Phase D 予約 Resolver を本機能で繰り上げ実装」
 * とされており、未認証 PUBLIC 閲覧の段階開示判定における権威ソースとして本 Resolver を恒常稼働させる。</p>
 *
 * <p><strong>F19.1 Phase 1 で恒常稼働化</strong>（2026-05-18）:
 * Phase D 段階では {@code feature.visibility-resolver.organization=true} feature flag で段階展開する設計だったが、
 * F19.1 公開組織ページの未認証閲覧判定の前提として本 Resolver を常時稼働させる必要があるため、
 * F19.1 Phase 1 で {@code @ConditionalOnProperty} を撤去しデフォルト Bean 登録に変更した。
 * Bean が常時登録されることにより {@link com.mannschaft.app.common.visibility.ContentVisibilityChecker}
 * が {@link ReferenceType#ORGANIZATION} 型を正規ルートで処理するようになる。</p>
 *
 * <p><strong>機能側 visibility との StandardVisibility マッピング</strong>（§5.2）:</p>
 * <ul>
 *   <li>{@link OrganizationEntity.Visibility#PUBLIC} → {@link StandardVisibility#PUBLIC}
 *       （誰でも閲覧可）</li>
 *   <li>{@link OrganizationEntity.Visibility#PRIVATE} → {@link StandardVisibility#SCOPE_AFFILIATED}
 *       （外部非公開・組織メンバーは閲覧可。非メンバーには非公開となる。
 *       実際の写像は {@code OrganizationVisibilityMapper.toStandard} を参照。）</li>
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
        return OrganizationVisibilityMapper.toStandard(visibility);
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

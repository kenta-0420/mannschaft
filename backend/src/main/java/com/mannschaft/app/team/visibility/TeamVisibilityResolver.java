package com.mannschaft.app.team.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.TeamVisibilityMapper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase D-γ — {@link ReferenceType#TEAM} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §7.5 / §11.6 / §15 D-13/D-14/D-16。</p>
 *
 * <p><strong>feature flag による段階展開制御</strong>:
 * {@code feature.visibility-resolver.team=true} を設定した環境でのみ本 Bean が登録される。
 * デフォルト ({@code false}) では Bean 未登録のため、{@link com.mannschaft.app.common.visibility.ContentVisibilityChecker}
 * は TEAM 型をフォールバック (fail-closed) で処理する。</p>
 *
 * <p><strong>機能側 visibility との StandardVisibility マッピング</strong>（§5.2）:</p>
 * <ul>
 *   <li>{@link TeamEntity.Visibility#PUBLIC} → {@link StandardVisibility#PUBLIC}
 *       （未認証ユーザーも含め誰でも閲覧可）</li>
 *   <li>{@link TeamEntity.Visibility#ORGANIZATION_ONLY} → {@link StandardVisibility#ORGANIZATION_WIDE}
 *       （スコープの親 ORG 所属メンバーまで公開。
 *       {@link com.mannschaft.app.common.visibility.UserScopeRoleSnapshot#isMemberOfParentOrg} で評価。
 *       親 ORG 非アクティブ時の連鎖ガードは §11.6 参照。）</li>
 *   <li>{@link TeamEntity.Visibility#PRIVATE} → {@link StandardVisibility#PRIVATE}
 *       （作成者本人のみ。チームに作成者概念（{@code created_by}）がないため
 *       authorUserId=null として実質的に fail-closed となる。）</li>
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
@ConditionalOnProperty(name = "feature.visibility-resolver.team", havingValue = "true", matchIfMissing = false)
public class TeamVisibilityResolver
        extends AbstractContentVisibilityResolver<TeamEntity.Visibility, TeamVisibilityProjection> {

    private final TeamRepository teamRepository;

    public TeamVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            TeamRepository teamRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.teamRepository = teamRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.TEAM;
    }

    @Override
    protected List<TeamVisibilityProjection> loadProjections(Collection<Long> ids) {
        return teamRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(TeamEntity.Visibility visibility) {
        return TeamVisibilityMapper.toStandard(visibility);
    }

    @Override
    protected ContentStatus toContentStatus(TeamVisibilityProjection row) {
        if (row.deletedAt() != null) {
            return ContentStatus.DELETED;
        }
        if (row.archivedAt() != null) {
            return ContentStatus.ARCHIVED;
        }
        return ContentStatus.PUBLISHED;
    }

    /**
     * チームには CUSTOM 経路がないため、デフォルトの fail-closed ({@code false}) を使用する。
     */
    @Override
    protected boolean evaluateCustom(
            TeamVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        return false;
    }
}

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
 *       （誰でも閲覧可）</li>
 *   <li>{@link TeamEntity.Visibility#ORGANIZATION_ONLY} → {@link StandardVisibility#MEMBERS_ONLY}
 *       （保守的マッピング: 現時点ではチームメンバーのみ。
 *       Phase D 以降で ORGANIZATION_WIDE へ昇格し「所属組織メンバー全体」に公開する想定。
 *       今は Resolver が稼働していない状態から切り替えるため、制限的な MEMBERS_ONLY を採用し
 *       誤公開リスクを最小化する設計判断（軍議裁可 D-γ 設計方針）。）</li>
 *   <li>{@link TeamEntity.Visibility#PRIVATE} → {@link StandardVisibility#ADMINS_ONLY}
 *       （チーム管理者のみ閲覧可）</li>
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
        return switch (visibility) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            // 保守的マッピング: 全組織メンバーへの公開は Phase D-δ 以降で ORGANIZATION_WIDE に昇格予定
            case ORGANIZATION_ONLY -> StandardVisibility.MEMBERS_ONLY;
            case PRIVATE -> StandardVisibility.ADMINS_ONLY;
        };
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

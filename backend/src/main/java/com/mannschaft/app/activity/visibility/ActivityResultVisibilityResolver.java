package com.mannschaft.app.activity.visibility;

import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.ActivityVisibilityMapper;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * F00 共通可視性基盤 — 活動記録 ({@link ReferenceType#ACTIVITY_RESULT}) 用 Resolver。
 *
 * <p>設計書 {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.2 / §6.1 /
 * §15 D-13 / D-14 / D-16 完全一致。</p>
 *
 * <p>Activity の特性:</p>
 * <ul>
 *   <li>visibility は {@link ActivityVisibility} の 2 値（PUBLIC / MEMBERS_ONLY）のみ。
 *       CUSTOM_TEMPLATE / FOLLOWERS_ONLY 等の高度な可視性は扱わない。</li>
 *   <li>status 軸を持たないため {@link AbstractContentVisibilityResolver#toContentStatus(com.mannschaft.app.common.visibility.VisibilityProjection)}
 *       は既定実装 ({@code PUBLISHED} 固定) に委ねる。</li>
 *   <li>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除行は Projection に届かないため
 *       DELETED ガードも上位で自動的に効く（fail-closed）。</li>
 *   <li>scopeType は {@code TEAM / ORGANIZATION / COMMITTEE} だが、F00 Phase B 範囲では
 *       TEAM / ORGANIZATION のみ正しく解決される。COMMITTEE はメンバーシップ解決対象外で
 *       MEMBERS_ONLY だと SystemAdmin 以外不可視（fail-closed）。</li>
 * </ul>
 *
 * <p>判定パイプラインのすべて（実存確認 → snapshot 構築 → status × visibility 評価 →
 * 監査ログ → メトリクス）は {@link AbstractContentVisibilityResolver} に集約されており、
 * 本クラスは最小契約 (loadProjections / toStandard / referenceType) のみ実装する。</p>
 */
@Component
@Transactional(readOnly = true)
public class ActivityResultVisibilityResolver
        extends AbstractContentVisibilityResolver<ActivityVisibility, ActivityResultVisibilityProjection> {

    /** 機能側 Repository（実存確認込み Projection 取得用、SQL 1）。 */
    private final ActivityResultRepository activityResultRepository;

    /**
     * Spring DI コンストラクタ。
     *
     * <p>{@link AbstractContentVisibilityResolver} が要求する依存（メンバーシップ照会・テンプレート評価・
     * メトリクス・任意の FOLLOWERS_ONLY と監査ログ）に加え、機能 Repository を受け取る。</p>
     *
     * @param activityResultRepository    Activity Repository（必須）
     * @param membershipBatchQueryService メンバーシップバッチ照会（必須）
     * @param templateEvaluator           CUSTOM_TEMPLATE 評価（必須、Activity では使用しないが共通契約）
     * @param visibilityMetrics           Micrometer メトリクス（必須）
     * @param followBatchService          フォロー判定 SPI（任意、Activity では未使用）
     * @param auditLogService             監査ログ（任意）
     */
    public ActivityResultVisibilityResolver(
            ActivityResultRepository activityResultRepository,
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.activityResultRepository = activityResultRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.ACTIVITY_RESULT;
    }

    @Override
    protected List<ActivityResultVisibilityProjection> loadProjections(Collection<Long> ids) {
        return activityResultRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(ActivityVisibility visibility) {
        return ActivityVisibilityMapper.toStandard(visibility);
    }
}

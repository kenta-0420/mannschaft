package com.mannschaft.app.recruitment.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.RecruitmentListingStatusMapper;
import com.mannschaft.app.common.visibility.mapping.RecruitmentVisibilityMapper;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase C — {@link ReferenceType#RECRUITMENT_LISTING} 用
 * {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §5.1 / §7.5 / §11.6 / §15 D-13 / D-14 / D-16。
 *
 * <p>機能 enum {@link RecruitmentVisibility} は
 * {@code PUBLIC / SCOPE_ONLY / SUPPORTERS_ONLY / CUSTOM_TEMPLATE} の 4 値を
 * {@link RecruitmentVisibilityMapper} 経由で {@link StandardVisibility} に正規化する。
 * status 軸は {@link RecruitmentListingStatusMapper} で {@link ContentStatus} に正規化される。
 *
 * <p>FOLLOWERS_ONLY は持たないため {@code FollowBatchService} は注入不要だが、
 * 抽象基底のシグネチャに合わせて Spring から任意で受け取る。
 *
 * <p>本クラスは抽象基底のテンプレートメソッドを差し替えるだけで完結し、
 * {@code canView} / {@code filterAccessible} / {@code decide} の各パイプラインや
 * SystemAdmin 高速パス（§15 D-13）／親 ORG 連鎖ガード（§11.6）／監査ログ（§11.4）／
 * メトリクス（§9.4）の責務は {@link AbstractContentVisibilityResolver} に委譲される。
 *
 * <p><strong>{@code @Transactional} 厳禁</strong>: PR#320/321 で発覚した CGLIB プロキシ
 * NPE 再発防止のため、本クラスに {@code @Transactional} を付与してはならない。
 * トランザクションは下層 {@link RecruitmentListingRepository} /
 * {@link MembershipBatchQueryService} が自前で持つ。VisibilityArchitectureTest が
 * 自動的にチェックする。
 */
@Component
public class RecruitmentListingVisibilityResolver
        extends AbstractContentVisibilityResolver<
                RecruitmentVisibility, RecruitmentListingVisibilityProjection> {

    private final RecruitmentListingRepository recruitmentListingRepository;

    public RecruitmentListingVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityMetrics visibilityMetrics,
            VisibilityTemplateEvaluator templateEvaluator,
            @Autowired(required = false) com.mannschaft.app.common.visibility.FollowBatchService
                    followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            RecruitmentListingRepository recruitmentListingRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.recruitmentListingRepository = recruitmentListingRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.RECRUITMENT_LISTING;
    }

    @Override
    protected List<RecruitmentListingVisibilityProjection> loadProjections(Collection<Long> ids) {
        return recruitmentListingRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(RecruitmentVisibility visibility) {
        return RecruitmentVisibilityMapper.toStandard(visibility);
    }

    @Override
    protected ContentStatus toContentStatus(RecruitmentListingVisibilityProjection row) {
        return RecruitmentListingStatusMapper.toStandard(row.status());
    }
}

package com.mannschaft.app.jobmatching.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.JobMatchingVisibilityMapper;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase C — {@link ReferenceType#JOB_POSTING} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.1.4 / §5.2 /
 * §7.5 / §11.6 / §15 D-13/D-14/D-16。
 *
 * <p>機能 enum {@link VisibilityScope} のうち {@link VisibilityScope#JOBBER_INTERNAL} は
 * {@link StandardVisibility#CUSTOM} に流れる（§5.2 備考: JOBBER ロール限定）。本クラスでは
 * {@link #evaluateCustom(JobPostingVisibilityProjection, Long, UserScopeRoleSnapshot)} を
 * オーバーライドして「対象求人のチームにおける viewer のロールが {@code "JOBBER"} か」を
 * {@link UserScopeRoleSnapshot#roleByScope()} から判定する（30 行以下、§5.1.4 運用規約に準拠）。
 *
 * <p>{@code JOBBER} ロールは {@link com.mannschaft.app.common.visibility.RolePriority} の
 * priority マップには載らない並行ロール（F13.1 §2.9）であり、{@code hasRoleOrAbove} では
 * 表現できないため、ロール文字列の直接照合で判定する。
 *
 * <p>status 正規化（{@link JobPostingStatus} → {@link ContentStatus}）:
 * <ul>
 *   <li>{@code DRAFT} → {@code DRAFT}（作成者本人と SystemAdmin のみ可視）</li>
 *   <li>{@code OPEN} → {@code PUBLISHED}（visibility 評価へ）</li>
 *   <li>{@code CLOSED} → {@code PUBLISHED}（応募終了後も閲覧自体は visibility に従う）</li>
 *   <li>{@code CANCELLED} → {@code ARCHIVED}（SystemAdmin のみ可視、F13.1 §5.4 取り下げ仕様）</li>
 * </ul>
 *
 * <p>{@code @Transactional} は付与しない。{@link AbstractContentVisibilityResolver} の規約および
 * ArchUnit ルール（{@code AbstractContentVisibilityResolverArchitectureTest}）により、
 * Resolver サブクラスへの {@code @Transactional} 付与は禁止されている（CGLIB プロキシによる
 * テンプレートメソッドの差し替え失敗で NPE/評価ロジック飛ばしを引き起こす根本バグ防止、
 * PR #345 / #347 で確立）。
 */
@Component
public class JobPostingVisibilityResolver
        extends AbstractContentVisibilityResolver<VisibilityScope, JobPostingVisibilityProjection> {

    /** F13.1 §2.9 / §5.2: JOBBER_INTERNAL 判定用の team_members.role 名。 */
    static final String JOBBER_ROLE_NAME = "JOBBER";

    private final JobPostingRepository jobPostingRepository;

    public JobPostingVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityMetrics visibilityMetrics,
            VisibilityTemplateEvaluator templateEvaluator,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            JobPostingRepository jobPostingRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.jobPostingRepository = jobPostingRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.JOB_POSTING;
    }

    @Override
    protected List<JobPostingVisibilityProjection> loadProjections(Collection<Long> ids) {
        return jobPostingRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(VisibilityScope visibility) {
        return JobMatchingVisibilityMapper.toStandard(visibility);
    }

    @Override
    protected ContentStatus toContentStatus(JobPostingVisibilityProjection row) {
        return mapStatus(row.status());
    }

    /**
     * §5.1.4 CUSTOM 個別処理 — {@link VisibilityScope#JOBBER_INTERNAL} の判定。
     *
     * <p>「対象求人の所属チームにおいて viewer が {@code JOBBER} ロールを保有しているか」を
     * {@link UserScopeRoleSnapshot#roleByScope()} から直接照合する。viewer 不明（匿名）や
     * scopeType/scopeId 不正は fail-closed。SystemAdmin 高速パス・親 ORG 連鎖チェックは
     * 基底クラスで処理済みのため、ここではロール照合のみに集中する。
     *
     * <p>本実装は §5.1.4 の「1 値あたり 30 行以下」運用規約に準拠する（コメント除く実装行数）。
     */
    @Override
    protected boolean evaluateCustom(JobPostingVisibilityProjection row, Long viewerUserId,
                                     UserScopeRoleSnapshot snapshot) {
        if (viewerUserId == null || row == null
                || row.scopeType() == null || row.scopeId() == null) {
            return false;
        }
        if (row.visibilityScope() != VisibilityScope.JOBBER_INTERNAL) {
            return false;
        }
        ScopeKey scope = new ScopeKey(row.scopeType(), row.scopeId());
        return JOBBER_ROLE_NAME.equals(snapshot.roleByScope().get(scope));
    }

    /**
     * §5.1.4 メトリクス可視化用の細分種別タグ。
     * 現行は {@link VisibilityScope#JOBBER_INTERNAL} のみが CUSTOM 経路に流れる。
     */
    @Override
    protected String customSubType(JobPostingVisibilityProjection row) {
        VisibilityScope v = row != null ? row.visibilityScope() : null;
        return v != null ? v.name() : "UNKNOWN";
    }

    /**
     * {@link JobPostingStatus} → {@link ContentStatus} の写像（§7.5）。
     *
     * <p>論理削除（{@code deleted_at IS NOT NULL}）は射影段階の WHERE 句で除外されるため、
     * {@link ContentStatus#DELETED} への写像は不要。</p>
     */
    private static ContentStatus mapStatus(JobPostingStatus status) {
        if (status == null) {
            // fail-closed: status 不明は DRAFT 扱い (基底側で SystemAdmin/作成者のみ可視)
            return ContentStatus.DRAFT;
        }
        return switch (status) {
            case DRAFT -> ContentStatus.DRAFT;
            case OPEN, CLOSED -> ContentStatus.PUBLISHED;
            case CANCELLED -> ContentStatus.ARCHIVED;
        };
    }
}

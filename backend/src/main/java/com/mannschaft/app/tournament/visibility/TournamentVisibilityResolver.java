package com.mannschaft.app.tournament.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.TournamentStatusMapper;
import com.mannschaft.app.common.visibility.mapping.TournamentVisibilityMapper;
import com.mannschaft.app.tournament.TournamentVisibility;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase C — {@link ReferenceType#TOURNAMENT} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.1 / §7.5 / §11.6
 * / §15 D-13/D-14/D-16。
 *
 * <p>機能 enum {@link TournamentVisibility} は {@code PUBLIC / MEMBERS_ONLY} の 2 値のみで、
 * CUSTOM / FOLLOWERS_ONLY / CUSTOM_TEMPLATE は持たない。{@link TournamentVisibilityMapper}
 * 経由で {@link StandardVisibility} に正規化し、status 軸は {@link TournamentStatusMapper}
 * で {@link ContentStatus} に正規化する。
 *
 * <p>Tournament は組織配下のコンテンツであり、スコープは常に {@code "ORGANIZATION"} 固定。
 * チームスコープは持たない。
 *
 * <p>本クラスは抽象基底のテンプレートメソッドを差し替えるだけで完結し、
 * {@code canView} / {@code filterAccessible} / {@code decide} の各パイプラインや
 * SystemAdmin 高速パス（§15 D-13）／親 ORG 連鎖ガード（§11.6）／監査ログ（§11.4）／
 * メトリクス（§9.4）の責務は {@link AbstractContentVisibilityResolver} に委譲される。
 *
 * <p><strong>{@code @Transactional} 厳禁</strong>: ArchUnit ルール
 * {@code abstractContentVisibilityResolver_subclasses_must_not_be_transactional}
 * により本クラスへのトランザクション境界付与は禁止されている。
 */
@Component
public class TournamentVisibilityResolver
        extends AbstractContentVisibilityResolver<TournamentVisibility, TournamentVisibilityProjection> {

    private final TournamentRepository tournamentRepository;

    public TournamentVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityMetrics visibilityMetrics,
            com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator templateEvaluator,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            TournamentRepository tournamentRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.tournamentRepository = tournamentRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.TOURNAMENT;
    }

    @Override
    protected List<TournamentVisibilityProjection> loadProjections(Collection<Long> ids) {
        return tournamentRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(TournamentVisibility visibility) {
        return TournamentVisibilityMapper.toStandard(visibility);
    }

    @Override
    protected ContentStatus toContentStatus(TournamentVisibilityProjection row) {
        return TournamentStatusMapper.toStandard(row.status());
    }
}

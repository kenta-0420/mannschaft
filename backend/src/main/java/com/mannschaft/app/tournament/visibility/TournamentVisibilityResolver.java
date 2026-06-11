package com.mannschaft.app.tournament.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.TournamentStatusMapper;
import com.mannschaft.app.common.visibility.mapping.TournamentVisibilityMapper;
import com.mannschaft.app.tournament.TournamentVisibility;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
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
 * <p>機能 enum {@link TournamentVisibility} は F08.7 順位UI Wave0 で 6 値
 * （{@code PUBLIC / SUPPORTERS_AND_ABOVE / MEMBERS_AND_ABOVE / ADMINS_AND_ABOVE /
 * SCOPE_AFFILIATED / PARTICIPANTS_ONLY}）に拡張された。{@link TournamentVisibilityMapper}
 * 経由で {@link StandardVisibility} に正規化し（PARTICIPANTS_ONLY は CUSTOM 軸へ写像し
 * {@link #evaluateCustom} で参加チーム関係者判定）、status 軸は {@link TournamentStatusMapper}
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
    private final TournamentParticipantRepository participantRepository;

    public TournamentVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityMetrics visibilityMetrics,
            com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator templateEvaluator,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            TournamentRepository tournamentRepository,
            TournamentParticipantRepository participantRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.tournamentRepository = tournamentRepository;
        this.participantRepository = participantRepository;
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

    /**
     * {@link StandardVisibility#CUSTOM}（= 機能 enum {@link TournamentVisibility#PARTICIPANTS_ONLY}）の
     * 個別判定: 閲覧者が当該大会の参加チームのいずれかにアクティブメンバーとして所属しているか。
     *
     * <p>判定は {@link TournamentParticipantRepository#countActiveMemberOfAnyParticipantTeam}
     * を流用する（{@code tournament_participants × tournament_divisions × memberships} を 1 SQL で JOIN、
     * 連絡可能ステータス REGISTERED/ACTIVE の参加チームに限定）。クロスドメインは ID 参照 JOIN のみ（原則1）。</p>
     *
     * <p>観点5（意図確認・現状維持で正）: 当該 JOIN は {@code scope_type='TEAM'} のメンバーシップを
     * ロールで絞らないため、参加チームの <b>SUPPORTER も可視</b>になる。これは PARTICIPANTS_ONLY の意図
     * 「参加チーム関係者＝応援者を含む内輪」に合致するため、敢えてロール制限を入れない（_AND_ABOVE ラダーの
     * MEMBERS_AND_ABOVE が応援者を除外するのとは別軸の、大会専用 CUSTOM 軸である点に注意）。</p>
     *
     * <p>未認証（{@code viewerUserId == null}）は不可視（fail-closed）。SystemAdmin は基底側の
     * 高速パスで本メソッドに到達しないため考慮不要。</p>
     *
     * @param row          判定対象の Projection（{@code id()} が tournament_id）
     * @param viewerUserId 閲覧者 user_id（{@code null} 可）
     * @param snapshot     メンバーシップスナップショット（本判定では未使用）
     * @return 参加チーム関係者なら {@code true}
     */
    @Override
    protected boolean evaluateCustom(
            TournamentVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        if (viewerUserId == null || row == null || row.id() == null) {
            return false;
        }
        return participantRepository
                .countActiveMemberOfAnyParticipantTeam(row.id(), viewerUserId) > 0;
    }
}

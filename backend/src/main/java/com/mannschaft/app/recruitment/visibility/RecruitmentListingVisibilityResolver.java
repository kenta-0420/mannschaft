package com.mannschaft.app.recruitment.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.mapping.RecruitmentListingStatusMapper;
import com.mannschaft.app.common.visibility.mapping.RecruitmentVisibilityMapper;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest.RecruitmentAudienceScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingAudienceScopeEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingAudienceScopeRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.service.MarketFriendTargetResolver;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /** F22.1 市: FRIEND_TEAMS_ONLY 札の宛先フレンドチーム集合を都度解決する（02_api_design §7）。 */
    private final MarketFriendTargetResolver marketFriendTargetResolver;

    /** F22.1 市: 閲覧者の所属チーム集合を解決する（宛先集合との突合に使用）。 */
    private final UserRoleRepository userRoleRepository;

    private final RecruitmentListingAudienceScopeRepository audienceScopeRepository;

    public RecruitmentListingVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityMetrics visibilityMetrics,
            VisibilityTemplateEvaluator templateEvaluator,
            @Autowired(required = false) com.mannschaft.app.common.visibility.FollowBatchService
                    followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            RecruitmentListingRepository recruitmentListingRepository,
            MarketFriendTargetResolver marketFriendTargetResolver,
            UserRoleRepository userRoleRepository,
            RecruitmentListingAudienceScopeRepository audienceScopeRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.recruitmentListingRepository = recruitmentListingRepository;
        this.marketFriendTargetResolver = marketFriendTargetResolver;
        this.userRoleRepository = userRoleRepository;
        this.audienceScopeRepository = audienceScopeRepository;
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

    /**
     * F22.1 市: {@code FRIEND_TEAMS_ONLY}（{@link StandardVisibility#CUSTOM} に正規化）の
     * 個別可視性判定（02_api_design §7 / 04_security §1.1）。
     *
     * <p>判定ロジック:</p>
     * <ol>
     *   <li>札主が TEAM スコープでなければ不可視（{@code team_friends} は team-to-team のため
     *       組織スコープの FRIEND_TEAMS_ONLY 札は成立しない）。</li>
     *   <li>{@link MarketFriendTargetService#resolveTargetTeamIds(Long, Long)} で「現在の成立
     *       フレンド集合」を都度解決する（保存時固定せずフレンド増減に追従）。</li>
     *   <li>閲覧者の所属チーム集合が <strong>宛先集合 ∪ 札主チーム自身</strong> と交差すれば可視。
     *       交差しなければ不可視（呼び出し側で 404 存在秘匿）。</li>
     * </ol>
     *
     * <p>未認証（{@code viewerUserId == null}）は所属チームを持たないため必ず不可視（fail-closed）。
     * CUSTOM_TEMPLATE（F01.7）は本機能では {@link RecruitmentVisibility#CUSTOM_TEMPLATE} が
     * {@link StandardVisibility#CUSTOM_TEMPLATE} に別途正規化され基底で処理されるため、本メソッドへは
     * FRIEND_TEAMS_ONLY のみが到達する（visibility 値で確認の上 fail-closed）。</p>
     */
    @Override
    protected boolean evaluateCustom(
            RecruitmentListingVisibilityProjection row,
            Long viewerUserId,
            UserScopeRoleSnapshot snapshot) {
        // 防御: FRIEND_TEAMS_ONLY 以外がここに到達したら fail-closed（想定外）。
        if (row.recruitmentVisibility() == RecruitmentVisibility.SELECTED_SCOPES) {
            return evaluateSelectedPersonalScopes(row, viewerUserId);
        }
        if (row.recruitmentVisibility() != RecruitmentVisibility.FRIEND_TEAMS_ONLY) {
            return false;
        }
        // 未認証は所属チームを持たない → 不可視。
        if (viewerUserId == null) {
            return false;
        }
        // 札主は TEAM スコープのみ（組織スコープのフレンド宛札は成立しない）。
        if (!"TEAM".equals(row.scopeType()) || row.scopeId() == null) {
            return false;
        }
        Long ownerTeamId = row.scopeId();

        // 宛先フレンドチーム集合（都度解決）∪ 札主チーム自身。
        Set<Long> allowedTeamIds = new HashSet<>(
                marketFriendTargetResolver.resolveTargetTeamIds(ownerTeamId, row.id()));
        allowedTeamIds.add(ownerTeamId);

        // 閲覧者の所属チーム集合と交差判定。
        Set<Long> viewerTeamIds = viewerTeamIds(viewerUserId);
        for (Long teamId : viewerTeamIds) {
            if (allowedTeamIds.contains(teamId)) {
                return true;
            }
        }
        return false;
    }

    /** 閲覧者が所属する全チーム ID 集合を返す（team_id 非 NULL の user_roles）。 */
    private boolean evaluateSelectedPersonalScopes(
            RecruitmentListingVisibilityProjection row, Long viewerUserId) {
        if (viewerUserId == null || row.scopeId() == null || row.authorUserId() == null
                || !"PERSONAL".equals(row.scopeType()) || !row.authorUserId().equals(row.scopeId())) {
            return false;
        }
        Set<Long> viewerTeamIds = viewerTeamIds(viewerUserId);
        Set<Long> viewerOrganizationIds = new HashSet<>(
                userRoleRepository.findOrganizationIdsByUserId(viewerUserId));
        for (RecruitmentListingAudienceScopeEntity scope : audienceScopeRepository.findByListingId(row.id())) {
            if (scope.getScopeType() == RecruitmentAudienceScopeType.TEAM
                    && viewerTeamIds.contains(scope.getScopeId())) {
                return true;
            }
            if (scope.getScopeType() == RecruitmentAudienceScopeType.ORGANIZATION
                    && viewerOrganizationIds.contains(scope.getScopeId())) {
                return true;
            }
        }
        return false;
    }

    private Set<Long> viewerTeamIds(Long viewerUserId) {
        Set<Long> teamIds = new HashSet<>();
        for (Long teamId : userRoleRepository.findTeamIdsByUserId(viewerUserId)) {
            if (teamId != null) {
                teamIds.add(teamId);
            }
        }
        return teamIds;
    }
}

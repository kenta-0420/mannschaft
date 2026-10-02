package com.mannschaft.app.survey.visibility;

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
import com.mannschaft.app.common.visibility.mapping.SurveyResultsVisibilityMapper;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.service.PermissionScopeQueryService;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * F00 Phase C — {@link ReferenceType#SURVEY} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §5.1 / §5.1.4 / §7.5 / §11.6 / §15 D-13/D-14/D-16。</p>
 *
 * <p><strong>機能側 visibility との対応</strong>（§5.2）:</p>
 * <ul>
 *   <li>{@link ResultsVisibility#ADMINS_ONLY} → {@link StandardVisibility#ADMINS_AND_ABOVE}</li>
 *   <li>{@link ResultsVisibility#AFTER_RESPONSE} → {@link StandardVisibility#CUSTOM}
 *       （回答済みユーザーのみ可視。判定は {@link SurveyResponseRepository}）</li>
 *   <li>{@link ResultsVisibility#AFTER_CLOSE} → {@link StandardVisibility#CUSTOM}
 *       （<b>締切後かつスコープ所属者のみ</b>可視。{@code expiresAt} 未設定は fail-closed。
 *       時間条件は {@link #evaluateAfterClose}、所属条件は {@link #visibleByAdditionalAxis} が担い
 *       AND で合成される。所属の述語は {@code ALWAYS} の配信母集団とは<b>仕様上あえて別物</b>で、
 *       {@code distribution_mode} / {@code include_supporters} を参照しない（Issue #2774））</li>
 *   <li>{@link ResultsVisibility#VIEWERS_ONLY} → {@link StandardVisibility#CUSTOM}
 *       （限定リスト {@code survey_result_viewers} に登録済みのみ可視）</li>
 *   <li>{@link ResultsVisibility#ALWAYS} → {@link StandardVisibility#SCOPE_AFFILIATED}
 *       （TEAM）／{@link StandardVisibility#ORGANIZATION_AND_DESCENDANTS}（ORGANIZATION・
 *       {@link #adjustLevel} で昇格）。公開後は締切前でも<b>配信母集団と同じ範囲</b>に可視。
 *       時間条件を持たないため CUSTOM に流さない。未公開 DRAFT は status 軸で弾かれ、
 *       スコープ外は所属軸で弾かれる。応援者の扱いは {@link #visibleByAdditionalAxis} を参照</li>
 * </ul>
 *
 * <p><strong>status × visibility 合成</strong>（§7.5）:</p>
 * <ul>
 *   <li>{@link SurveyStatus#DRAFT} → {@link ContentStatus#DRAFT}（作成者・SystemAdmin のみ可視）</li>
 *   <li>{@link SurveyStatus#PUBLISHED} / {@link SurveyStatus#CLOSED} → {@link ContentStatus#PUBLISHED}
 *       （結果可視性ガード {@link ResultsVisibility} の評価へ進む）</li>
 *   <li>{@link SurveyStatus#ARCHIVED} → {@link ContentStatus#ARCHIVED}（SystemAdmin のみ可視）</li>
 *   <li>{@code null} → {@link ContentStatus#DELETED}（fail-closed）</li>
 * </ul>
 *
 * <p><strong>制約</strong>（§15 D-14 / D-16）:</p>
 * <ul>
 *   <li>{@code AccessControlService} の 12 メソッドに一切触れない（D-14）。</li>
 *   <li>他 Resolver を inject せず、必要であれば
 *       {@link com.mannschaft.app.common.visibility.ContentVisibilityChecker} を通じて参照する（D-16）。</li>
 *   <li>本クラスには {@code @Transactional} を付与してはならない（{@code AbstractContentVisibilityResolver}
 *       の final テンプレートメソッドが CGLIB プロキシで NPE を起こすため。
 *       {@code VisibilityArchitectureTest} で機械的に検出される）。</li>
 * </ul>
 *
 * <p><strong>CUSTOM 個別処理の規約</strong>（§5.1.4）:
 * 各 CUSTOM 値の判定は 1 メソッド 30 行以下を厳守する。fail-closed を徹底し、
 * 入力が不完全（{@code userId == null}, {@code expiresAt == null}, viewers 不在など）の場合は
 * {@code false} を返す。</p>
 */
@Component
public class SurveyVisibilityResolver
        extends AbstractContentVisibilityResolver<ResultsVisibility, SurveyVisibilityProjection> {

    private final SurveyRepository surveyRepository;
    private final SurveyResponseRepository surveyResponseRepository;
    private final SurveyResultViewerRepository surveyResultViewerRepository;
    private final SurveyTargetRepository surveyTargetRepository;
    private final OrganizationMembershipService organizationMembershipService;
    private final PermissionScopeQueryService permissionScopeQueryService;

    /** 可視性の上位条件で「管理者相当」に含める権限（CMP-041）。 */
    private static final String MANAGE_SURVEYS = "MANAGE_SURVEYS";

    public SurveyVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            SurveyRepository surveyRepository,
            SurveyResponseRepository surveyResponseRepository,
            SurveyResultViewerRepository surveyResultViewerRepository,
            SurveyTargetRepository surveyTargetRepository,
            OrganizationMembershipService organizationMembershipService,
            PermissionScopeQueryService permissionScopeQueryService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.surveyRepository = surveyRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.surveyResultViewerRepository = surveyResultViewerRepository;
        this.surveyTargetRepository = surveyTargetRepository;
        this.organizationMembershipService = organizationMembershipService;
        this.permissionScopeQueryService = permissionScopeQueryService;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.SURVEY;
    }

    @Override
    protected List<SurveyVisibilityProjection> loadProjections(Collection<Long> ids) {
        return surveyRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(ResultsVisibility visibility) {
        return SurveyResultsVisibilityMapper.toStandard(visibility);
    }

    /**
     * フェーズ M2: 組織スコープのアンケートで、結果可視性が組織全体（上向き 1 段の
     * {@link StandardVisibility#ORGANIZATION_WIDE}）に正規化された場合に限り、下向き再帰の
     * {@link StandardVisibility#ORGANIZATION_AND_DESCENDANTS} へ昇格する（schedule と同一作法）。
     *
     * <p>これにより、ネスト組織 root が配信した組織全体アンケートの結果を、孫組織配下の参加チームのみ
     * 所属メンバーまで閲覧可能にする（欠陥 Z の根治・配信 universe と評価範囲を一致させる）。</p>
     *
     * <p><strong>補足（現状の実効性）</strong>: 現行の {@link com.mannschaft.app.survey.ResultsVisibility}
     * は組織全体（org-wide）を表す値を持たず、{@link com.mannschaft.app.common.visibility.mapping.SurveyResultsVisibilityMapper}
     * は {@link StandardVisibility#ORGANIZATION_WIDE} を生成しないため、本昇格は現時点では発火しない。
     * 「アンケートを閲覧・回答してよい所属圏か」という組織配信の可視範囲は M1 の
     * {@code SurveyResultService.isUserInUniverse}（再帰 universe）が司る。本フックは schedule と
     * 作法を揃え、将来 org-wide 値が追加された際に下向き再帰へ正しく開くための前向き整合である。</p>
     */
    @Override
    protected StandardVisibility adjustLevel(
            SurveyVisibilityProjection row, StandardVisibility level) {
        boolean organizationScope = "ORGANIZATION".equals(row.scopeType());
        if (level == StandardVisibility.ORGANIZATION_WIDE && organizationScope) {
            return StandardVisibility.ORGANIZATION_AND_DESCENDANTS;
        }
        // ALWAYS は Mapper が CUSTOM を返し、配信母集団の述語そのもので判定する（#2617-3）。
        // 所属軸（SCOPE_AFFILIATED / ORGANIZATION_AND_DESCENDANTS）へ昇格させる旧実装は、
        // 下向き再帰の判定が user_roles のみを見る共有クエリに依存しており、
        // memberships へ移行済みの MEMBER / SUPPORTER を構造的に取りこぼしていたため撤去した。
        return level;
    }

    /**
     * ALWAYS の追加軸 — 可視範囲を {@code distribution_mode} 別の配信母集団に厳密に一致させる。
     *
     * <p>所属軸（{@link StandardVisibility#SCOPE_AFFILIATED} /
     * {@link StandardVisibility#ORGANIZATION_AND_DESCENDANTS}）は「スコープに居るか」しか答えられず、
     * 配信母集団の 2 つの絞り込み（対象者名簿・応援者除外）を表現できない。そのままでは
     * <b>配信されていない者に中間集計が見えてしまう</b>ため、本フックで母集団と揃える。
     * 判定述語は配信母集団を算出している既存実装
     * （{@code SurveyResultService#resolveUniverseUserIds} /
     * {@code #isUserInUniverse}）と同一のものを再利用し、独自述語は書かない。</p>
     *
     * <ul>
     *   <li><b>TARGETED</b>: 母集団は {@code survey_targets} 名簿そのもの。よって名簿登録を必須にする
     *       （回答時の関所 {@code SurveyResponseService} と同じ述語の一括版）。
     *       スコープ所属だけでは通さない。ただし設計書の優先順位規定に従い、
     *       <b>当該スコープの ADMIN+ と結果閲覧者名簿（{@code survey_result_viewers}）登録者は迂回</b>する
     *       （名簿ゲート導入前は所属軸でこれらが閲覧できていたため、締める方向の回帰を作らない）。</li>
     *   <li><b>ALL × ORGANIZATION</b>: 母集団は {@code include_supporters}（既定 FALSE）で応援者を除外する。
     *       所属軸は G7 により SUPPORTER を含むため、除外時は下向き再帰ツリーでの実効ロールが
     *       MEMBER 以上であることを要求する（CMP-017b の {@code hasDescendantRoleOrAbove} を再利用）。</li>
     *   <li><b>ALL × TEAM</b>: 母集団は {@code include_supporters} を参照せず {@code user_roles} の
     *       当該スコープ行すべて（応援者を含む）であり SCOPE_AFFILIATED と既に一致するため、
     *       追加の条件を課さない（挙動を変えないことが正しい）。</li>
     * </ul>
     *
     * <p>ALWAYS 以外の値は本フックでは素通しする（既存 4 値の判定は一切変えない）。
     * なお SystemAdmin と DRAFT の作成者本人は本フックに到達する前に
     * {@code AbstractContentVisibilityResolver#visibleByVisibility} で短絡され、
     * 公開済アンケートの作成者は {@code SurveyResultService#validateResultAccess} の
     * 作成者高速パスで Resolver 自体を通らない（名簿に作成者が入らない運用でも締め出さない）。</p>
     */
    /**
     * 名簿・母集団・所属の判定に必要な集合を<b>バッチ 1 回につき 1 本ずつ</b>先読みする。
     *
     * <p>行ごとに {@code existsBySurveyIdAndUserId} を呼ぶと、{@code filterAccessible} に
     * N 件渡されたとき N 本の SQL が飛び（N+1）、設計書
     * {@code F00_content_visibility_resolver.md} §9 のバッチ SQL 本数上限・性能目標に反する。
     * そこで必要な ID を集め、1 本の {@code WHERE survey_id IN (:ids) AND user_id = :userId} で引く。</p>
     *
     * <p><b>先読みする軸は 2 つあり、対象行が異なる</b>（{@link #visibleByAdditionalAxis} 参照）:</p>
     * <ul>
     *   <li>{@code ALWAYS} → <b>配信母集団</b>（{@code survey_targets} 名簿 /
     *       {@code include_supporters} トグル準拠の組織配信母集団）。組織母集団は
     *       <b>トグルごとにバルク 1 本</b>で引くため、別組織の行が何件混ざっても
     *       SQL は増えない（最大 2 本。Issue #2782）</li>
     *   <li>{@code AFTER_CLOSE} → <b>上位条件の名簿のみ</b>。所属判定は TEAM / ORGANIZATION とも
     *       snapshot で答えられるため<b>追加クエリは 0 本</b>
     *       （ORG は {@link #additionalDescendantScopes} で snapshot 側に解決させる）</li>
     * </ul>
     *
     * <p>対象行が無ければクエリを発行しない（他の値のみの一覧に本ゲートのコストを乗せない）。
     * 未認証（{@code viewerUserId == null}）も同様にクエリ不要で、判定側が fail-closed する。</p>
     */
    @Override
    protected Object prepareAdditionalAxisContext(
            List<SurveyVisibilityProjection> rows, Long viewerUserId) {
        if (viewerUserId == null || rows == null || rows.isEmpty()) {
            return AudienceContext.EMPTY;
        }
        // 上位条件（結果閲覧者名簿）の照会対象。ALWAYS / AFTER_CLOSE の双方が上位条件を持つ。
        Set<Long> bypassCandidateIds = new HashSet<>();
        Set<Long> targetedIds = new HashSet<>();
        Set<OrgAudienceKey> orgAudienceKeys = new HashSet<>();
        // 権限保有 DEPUTY_ADMIN の判定対象スコープ。上位条件は results_visibility を問わず
        // 全行に効く（ADMINS_ONLY / VIEWERS_ONLY 等も含む）ため、名簿の対象行とは母集団が異なる。
        Set<Long> permissionTeamIds = new HashSet<>();
        Set<Long> permissionOrgIds = new HashSet<>();
        for (SurveyVisibilityProjection row : rows) {
            if (row == null || row.id() == null) {
                continue;
            }
            if (row.scopeId() != null) {
                if ("TEAM".equals(row.scopeType())) {
                    permissionTeamIds.add(row.scopeId());
                } else if ("ORGANIZATION".equals(row.scopeType())) {
                    permissionOrgIds.add(row.scopeId());
                }
            }
            ResultsVisibility v = row.resultsVisibility();
            if (v == ResultsVisibility.ALWAYS) {
                bypassCandidateIds.add(row.id());
                if (requiresTargetRoster(row)) {
                    targetedIds.add(row.id());
                } else if ("ORGANIZATION".equals(row.scopeType()) && row.scopeId() != null) {
                    orgAudienceKeys.add(new OrgAudienceKey(
                            row.scopeId(), Boolean.TRUE.equals(row.includeSupporters())));
                }
            } else if (v == ResultsVisibility.AFTER_CLOSE) {
                // 所属軸は snapshot（下向き再帰・バルク 1 本）で判定するため先読み不要。
                // 必要なのは上位条件の名簿照会だけである。
                bypassCandidateIds.add(row.id());
            }
        }
        // 権限保有 DEPUTY_ADMIN のスコープを、スコープ種別ごとにバルク 1 本ずつ（最大 2 本）先読みする。
        // 行ごと・スコープごとに引くとスコープ数比例の N+1 になり、§9 の SQL 本数上限に反する。
        Set<ScopeKey> manageSurveysScopes =
                resolveManageSurveysScopes(permissionTeamIds, permissionOrgIds, viewerUserId);

        if (bypassCandidateIds.isEmpty()) {
            // 名簿・母集団の追加軸を持つ行が無ければ、それらの SQL は増やさない（従来どおり）。
            return new AudienceContext(Set.of(), Set.of(), Set.of(), manageSurveysScopes);
        }

        // 結果閲覧者名簿（上位条件）は対象全行に対して 1 本。
        Set<Long> resultViewerIds = Set.copyOf(surveyResultViewerRepository
                .findResultViewerSurveyIds(bypassCandidateIds, viewerUserId));
        // 配信対象名簿は ALWAYS × TARGETED 行がある場合のみ 1 本。
        Set<Long> targetedSurveyIds = targetedIds.isEmpty()
                ? Set.of()
                : Set.copyOf(surveyTargetRepository.findTargetedSurveyIds(targetedIds, viewerUserId));
        // 組織の配信母集団は「組織数」ではなく「トグルの種類数」に比例させる（最大 2 本）。
        return new AudienceContext(targetedSurveyIds, resultViewerIds,
                resolveOrgAudience(orgAudienceKeys, viewerUserId), manageSurveysScopes);
    }

    /**
     * 閲覧者が {@code MANAGE_SURVEYS} を持つ<b>権限保有 DEPUTY_ADMIN</b> であるスコープ集合を
     * <b>スコープ種別ごとにバルク 1 本</b>（最大 2 本）で先読みする（CMP-041 五番隊）。
     *
     * <p><b>なぜ snapshot ではなくここで引くのか</b> — {@link UserScopeRoleSnapshot} はロール名しか
     * 持たず、{@code RolePriority} 上 DEPUTY_ADMIN(3) は ADMIN(2) より弱いため
     * {@code hasRoleOrAbove(scope, "ADMIN")} では権限保有者を表現できない。一方で
     * {@code RoleService#resolveEffectivePermissions} を Resolver から直に呼ぶと、キャッシュミス時に
     * <b>スコープごとに</b>多数の SQL が飛び、Issue #2782 で撤去した実装を再生産する。
     * そこで「バッチ 1 回につき集合を先読みする」という基盤の契約に沿ってバルク化する。</p>
     *
     * <p>述語は第一陣が新設した単票版
     * {@code UserRoleRepository#existsDeputyAdminWithPermissionInTeam} /
     * {@code #existsDeputyAdminWithPermissionInOrganization} と<b>同一の意味論</b>であり、
     * role ドメインの {@link PermissionScopeQueryService} 経由で引く
     * （他ドメインの Repository を直接触ると番人 {@code CrossDomainRepositoryDependencyArchTest} の
     * D-5 に反するため）。判定は
     * {@code role_permissions.is_default = 1} 経由か権限グループ経由のいずれかを実付与とみなす。</p>
     *
     * <p>対象スコープが無い種別の SQL は発行しない（両方無ければ 0 本）。</p>
     */
    private Set<ScopeKey> resolveManageSurveysScopes(
            Set<Long> teamIds, Set<Long> orgIds, Long viewerUserId) {
        if (viewerUserId == null || (teamIds.isEmpty() && orgIds.isEmpty())) {
            return Set.of();
        }
        Set<ScopeKey> scopes = new HashSet<>();
        if (!teamIds.isEmpty()) {
            for (Long teamId : permissionScopeQueryService
                    .findPermittedTeamIds(viewerUserId, teamIds, MANAGE_SURVEYS)) {
                if (teamId != null) {
                    scopes.add(new ScopeKey("TEAM", teamId));
                }
            }
        }
        if (!orgIds.isEmpty()) {
            for (Long orgId : permissionScopeQueryService
                    .findPermittedOrganizationIds(viewerUserId, orgIds, MANAGE_SURVEYS)) {
                if (orgId != null) {
                    scopes.add(new ScopeKey("ORGANIZATION", orgId));
                }
            }
        }
        return scopes;
    }

    /**
     * 上位条件（当該スコープの管理者相当 ／ 結果閲覧者名簿の登録者）を
     * <b>scope 軸ごと貫通</b>させる（CMP-041 五番隊）。
     *
     * <p>{@code ADMINS_ONLY} は {@link StandardVisibility#ADMINS_AND_ABOVE} という<b>閾値</b>に
     * 解決されるため、AND 合成の {@link #visibleByAdditionalAxis} からは開けない。
     * 基底クラスの {@code privilegedViewerBypass} が唯一の「開ける方向」の合流点である。</p>
     *
     * <p>status 軸（DRAFT / ARCHIVED）は基底クラスが本フックより手前で確定させるため貫通しない
     * （AC-22）。参照するのは当該行のスコープと当該アンケートの名簿のみのため、
     * 他スコープ・他テナントの管理者は通らない（AC-23）。</p>
     */
    @Override
    protected boolean privilegedViewerBypass(
            SurveyVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot,
            Object additionalAxisContext) {
        if (row == null || viewerUserId == null) {
            return false;
        }
        return isPrivilegedViewer(row, snapshot,
                additionalAxisContext instanceof AudienceContext ctx ? ctx : AudienceContext.EMPTY);
    }

    /**
     * AFTER_CLOSE の所属軸 — 締切という時間条件に<b>スコープ所属の照合を AND で合成</b>する（Issue #2774）。
     *
     * <p>{@link ResultsVisibility#AFTER_CLOSE} は {@link StandardVisibility#CUSTOM} へ流れるため、
     * scope 軸（{@code SCOPE_AFFILIATED} 等）の評価を経ない。その結果、判定が
     * {@link #evaluateAfterClose} の時間条件だけで確定し、<b>閲覧者がどのスコープに居るかを
     * 一度も参照しない</b>状態になっていた。同じ CUSTOM 経路でも {@code AFTER_RESPONSE} は
     * 回答履歴を、{@code VIEWERS_ONLY} は結果閲覧者名簿を照合しており、時間条件だけの値が
     * 所属確認を持たない点が非対称であった。{@code surveys.results_visibility} の DB 既定値が
     * この値であるため、明示設定していないアンケートがすべて該当する。</p>
     *
     * <p><b>不変条件</b>: 締切を過ぎていることは可視の<b>必要条件であって十分条件ではない</b>。</p>
     *
     * <p><b>⚠️ なぜ {@code ALWAYS} と述語が違うのか（統一してはならない）</b> —
     * 設計書 {@code docs/features/F05.4_survey_vote.md} は両者を明確に書き分けている:</p>
     * <ul>
     *   <li>{@code ALWAYS}: 「<b>配信母集団に含まれる者</b>」。TARGETED なら {@code survey_targets}
     *       名簿のみ、組織 ALL × {@code include_supporters = FALSE} なら応援者を除外する
     *       （「配信母集団＝中間集計の閲覧母集団」を不変条件とするため）</li>
     *   <li>{@code AFTER_CLOSE}: 「締切後のみ<b>スコープ所属者全員</b>」</li>
     * </ul>
     * <p>したがって本フックは {@link #isInDistributionAudience}（配信母集団）ではなく
     * {@link #isScopeAffiliated}（スコープ所属）を呼ぶ。両者を同一述語に寄せると<b>仕様より狭くなり</b>、
     * 「TARGETED の名簿に載っていないスコープ所属メンバー」と
     * 「{@code include_supporters = FALSE} の組織 ALL 配信における応援者」を締切後も不当に締め出す。
     * <b>重複に見えても統一しないこと。</b>締切後は配信の切り口ではなくスコープ所属が基準になる、
     * という仕様上の意図的な差である。</p>
     *
     * <p>本フックは {@code visibleByLevel} の結果と AND 合成されるため、時間条件を緩める方向には
     * 決して働かない。SystemAdmin 高速パス・DRAFT の作成者スキップ・§11.6 親 ORG 連鎖ガードは
     * いずれも本フックより手前で確定するため覆らない。公開済アンケートの作成者は
     * {@code SurveyResultService#validateResultAccess} の作成者高速パスで Resolver 自体を通らない。</p>
     *
     * <p>{@code AFTER_CLOSE} 以外の値は素通しする（{@code ALWAYS} は従来どおり
     * {@link #evaluateAlways} 内で自身の述語を評価しており、二重適用にはならない）。</p>
     */
    @Override
    protected boolean visibleByAdditionalAxis(
            SurveyVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot,
            StandardVisibility level, Object additionalAxisContext) {
        if (row == null || row.resultsVisibility() != ResultsVisibility.AFTER_CLOSE) {
            return true;
        }
        return isScopeAffiliated(row, viewerUserId, snapshot,
                additionalAxisContext instanceof AudienceContext ctx ? ctx : AudienceContext.EMPTY);
    }

    /**
     * 閲覧者が当該アンケートの<b>スコープに所属している</b>か（{@code AFTER_CLOSE} 用の所属軸）。
     *
     * <p>設計書の「締切後のみ<b>スコープ所属者全員</b>」を表す述語であり、
     * <b>{@code distribution_mode} と {@code include_supporters} を一切参照しない</b>。
     * 名簿（{@code survey_targets}）は「配信母集団」の定義であって「所属」の定義ではないため、
     * TARGETED 配信でも名簿を条件にしない。</p>
     *
     * <ul>
     *   <li><b>TEAM スコープ</b>: 当該チームへの直接所属
     *       （{@link StandardVisibility#SCOPE_AFFILIATED} と同一述語。応援者を除外しない）。
     *       snapshot で判定できるため追加クエリは不要。</li>
     *   <li><b>ORGANIZATION スコープ</b>:
     *       {@link UserScopeRoleSnapshot#isDescendantMemberOf}
     *       — 「組織直属 ∪ 配下 ACTIVE チーム」の下向き再帰<b>所属軸</b>（G7 により SUPPORTER を一律含む）。
     *       ロール閾値を見る {@code hasDescendantRoleOrAbove} や、配信トグル準拠の
     *       {@code isInOrgDistributionAudience} とは<b>別物</b>なので取り違えないこと。
     *       Issue #2780 / #2785 / #2786 で {@code user_roles} ∪ {@code memberships} の
     *       和集合へ是正済みであり、{@code memberships} 専属の一般メンバーも正しく拾う。
     *       対象 ORG は {@link #additionalDescendantScopes} で申告し、
     *       組織が何件混ざってもバルククエリ 1 本で解決される（追加クエリ 0 本）。</li>
     * </ul>
     *
     * <p>設計書 §「結果閲覧権限の判定」の上位条件（優先順 2 = ADMIN+ ／ 優先順 3 = 結果閲覧者名簿）は
     * {@link #isPrivilegedViewer} で所属軸を貫通する。時間軸の貫通は {@link #evaluateCustom} 側で
     * 行っており、<b>両方を貫通して初めて</b>「{@code results_visibility} を無視して閲覧可能」という
     * 仕様が成立する（片方だけでは AND 合成で打ち消される）。</p>
     *
     * <p>スコープ不明・未認証は fail-closed（false）。
     * {@code context} は {@link #prepareAdditionalAxisContext} で先読み済みで、本メソッドは DB を触らない。</p>
     */
    private boolean isScopeAffiliated(
            SurveyVisibilityProjection row, Long viewerUserId,
            UserScopeRoleSnapshot snapshot, AudienceContext context) {
        if (viewerUserId == null || row.id() == null
                || row.scopeType() == null || row.scopeId() == null) {
            return false;
        }
        // 上位条件は所属軸も貫通する（優先順 2/3。時間軸は evaluateCustom 側で貫通させている）。
        if (isPrivilegedViewer(row, snapshot, context)) {
            return true;
        }
        ScopeKey scope = new ScopeKey(row.scopeType(), row.scopeId());
        if ("ORGANIZATION".equals(row.scopeType())) {
            return snapshot.isDescendantMemberOf(scope);
        }
        return snapshot.isMemberOf(scope);
    }

    /**
     * {@code AFTER_CLOSE} の組織スコープ行を、snapshot の下向き再帰解決対象として申告する
     * （Issue #2782 の回避・追加クエリ 0 本化）。
     *
     * <p>当初は組織ごとに {@code OrganizationMembershipService#isUserInOrgDistributionUniverse} を
     * 呼んでいたが、これは行数比例ではないものの<b>組織数に比例</b>し、
     * 「追加軸はバッチ 1 回で先読みする」という基盤の契約に反していた。
     * snapshot の下向き再帰は<b>複数 ORG 根を 1 本の再帰 CTE でまとめて</b>解決するため、
     * 別組織のアンケートが何件混ざっても SQL は増えない。</p>
     *
     * <p>両者は同一の意味論である（いずれも「対象組織を根とする再帰ツリーの
     * 直属 ∪ 配下 ACTIVE チーム」への所属を、{@code user_roles} ∪ {@code memberships} の
     * 和集合で判定する所属軸。SUPPORTER を除外しない）。同値であることは実 DB のテストで
     * 両述語を突き合わせて実証している。</p>
     *
     * <p>申告しても判定レベルは {@link StandardVisibility#CUSTOM} のままで変わらない
     * （{@link #adjustLevel} には触れていない）。変わるのは snapshot に下向き再帰の所属が
     * 載るかどうかだけである。</p>
     */
    @Override
    protected Set<ScopeKey> additionalDescendantScopes(List<SurveyVisibilityProjection> rows) {
        if (rows == null || rows.isEmpty()) {
            return Set.of();
        }
        Set<ScopeKey> scopes = new HashSet<>();
        for (SurveyVisibilityProjection row : rows) {
            if (row != null
                    && row.resultsVisibility() == ResultsVisibility.AFTER_CLOSE
                    && "ORGANIZATION".equals(row.scopeType())
                    && row.scopeId() != null) {
                scopes.add(new ScopeKey(row.scopeType(), row.scopeId()));
            }
        }
        return scopes;
    }

    /**
     * 設計書 §「結果閲覧権限の判定」の<b>上位条件</b>に該当する閲覧者か
     * （優先順 2 = 当該スコープの ADMIN+ ／ 優先順 3 = {@code survey_result_viewers} 登録者）。
     *
     * <p>仕様上これらは「<b>{@code results_visibility} を無視して</b>閲覧可能」と定められている。
     * 優先順 1（作成者）は {@code SurveyResultService#validateResultAccess} の高速パスで
     * Resolver 自体を通らないため、ここでは扱わない。</p>
     *
     * <p><b>⚠️ 呼び出し箇所が 2 つあるのは冗長ではない。</b>{@code AFTER_CLOSE} の判定は
     * <b>時間軸</b>（{@link #evaluateCustom} → {@link #evaluateAfterClose}）と
     * <b>所属軸</b>（{@link #visibleByAdditionalAxis} → {@link #isScopeAffiliated}）の
     * <b>AND 合成</b>であるため、片方だけを迂回させても他方に打ち消されて実効性が無い。
     * 「{@code results_visibility} を無視する」を成立させるには<b>両方の軸を貫通</b>する必要がある。
     * どちらか一方を消すと上位条件が黙って効かなくなるので消さないこと。</p>
     *
     * <p><b>status 軸は貫通しない。</b>{@code DRAFT}（未公開）・{@code ARCHIVED} は基底クラスの
     * status ガードが本メソッドより手前で fail-closed するため、上位条件に該当する者でも不可視である
     * （未公開アンケートを漏らさないための境界）。</p>
     *
     * <p>参照するのは当該スコープへの直接ロールと当該アンケートの名簿のみのため、
     * 他スコープ・他テナントの ADMIN は通らない。</p>
     */
    private static boolean isPrivilegedViewer(
            SurveyVisibilityProjection row, UserScopeRoleSnapshot snapshot, AudienceContext context) {
        if (row.id() == null) {
            return false;
        }
        return isScopeAdmin(row, snapshot)
                || isPermittedDeputyAdmin(row, context)
                || context.resultViewerSurveyIds().contains(row.id());
    }

    /**
     * 当該スコープで {@code MANAGE_SURVEYS} を持つ DEPUTY_ADMIN か（CMP-041）。
     *
     * <p>集合は {@link #prepareAdditionalAxisContext} でバルク先読み済みのため、本メソッドは DB を触らない。
     * {@link #isScopeAdmin} と OR で合成し、ADMIN の経路は一切変えない。</p>
     */
    private static boolean isPermittedDeputyAdmin(
            SurveyVisibilityProjection row, AudienceContext context) {
        if (row.scopeType() == null || row.scopeId() == null) {
            return false;
        }
        return context.manageSurveysScopes()
                .contains(new ScopeKey(row.scopeType(), row.scopeId()));
    }

    /**
     * 組織スコープの配信母集団を、<b>トグルごとにバルク 1 本</b>で解決する（Issue #2782）。
     *
     * <p>従来は {@code OrganizationMembershipService#isInOrgDistributionAudience} を
     * {@code (組織, トグル)} の組ごとに呼んでおり、別組織のアンケートを {@code filterAccessible} に
     * まとめて渡すと<b>組織の種類数に比例</b>して再帰 EXISTS が飛んでいた。これは基盤
     * {@link AbstractContentVisibilityResolver#prepareAdditionalAxisContext} の
     * 「判定ループに入る前に必要な集合をバッチで引く」契約に反する。</p>
     *
     * <p><b>⚠️ なぜ {@code AFTER_CLOSE} と同じ手（snapshot への集約）が使えないのか</b> —
     * {@code AFTER_CLOSE} は「スコープ所属者全員」なので、所属軸である
     * {@link UserScopeRoleSnapshot#isDescendantMemberOf} へそのまま寄せられ追加クエリ 0 本になった
     * （{@link #additionalDescendantScopes}）。しかし {@code ALWAYS} は<b>配信母集団</b>であり、
     * {@code include_supporters = FALSE} のとき<b>純 SUPPORTER を除外</b>しなければならない。
     * snapshot の所属軸は G7 により SUPPORTER を一律含むため、この区別を表現できない。
     * 寄せると母集団の意味論が壊れ、配信されていない応援者に中間集計が見える（漏洩）。
     * よって<b>意味論を保ったまま複数 ORG 根をバルク化する</b>という別の解を採る。</p>
     *
     * <p>トグルは 2 値なので、実在するトグルの種類数（最大 2）だけ SQL を発行する。
     * 組織が何件混ざっても本数は変わらない。組織スコープの {@code ALWAYS} 行が無ければ 0 本。</p>
     */
    private Set<OrgAudienceKey> resolveOrgAudience(
            Set<OrgAudienceKey> orgAudienceKeys, Long viewerUserId) {
        if (orgAudienceKeys.isEmpty()) {
            return Set.of();
        }
        // トグルごとに組織 ID を束ね、束ね単位で 1 本ずつ引く（母集団の定義がトグルで変わるため）。
        Set<OrgAudienceKey> inAudience = new HashSet<>();
        for (boolean includeSupporters : new boolean[] {false, true}) {
            Set<Long> orgIds = new HashSet<>();
            for (OrgAudienceKey key : orgAudienceKeys) {
                if (key.includeSupporters() == includeSupporters) {
                    orgIds.add(key.orgId());
                }
            }
            if (orgIds.isEmpty()) {
                continue;
            }
            for (Long orgId : organizationMembershipService.resolveOrgDistributionAudienceRoots(
                    orgIds, viewerUserId, includeSupporters)) {
                inAudience.add(new OrgAudienceKey(orgId, includeSupporters));
            }
        }
        return inAudience;
    }

    /** 組織配信母集団の判定キー（同一組織・同一トグルの行はまとめて 1 回だけ判定する）。 */
    private record OrgAudienceKey(Long orgId, boolean includeSupporters) {}

    /**
     * 追加軸の判定に必要な情報（バッチ 1 回分・すべて先読み済み）。
     *
     * <p><b>2 つの異なる軸</b>を同居させている。{@code ALWAYS} は配信母集団
     * （{@link #isInDistributionAudience}）、{@code AFTER_CLOSE} はスコープ所属
     * （{@link #isScopeAffiliated}）で、仕様上あえて異なる（理由は
     * {@link #visibleByAdditionalAxis} の javadoc 参照）。
     * {@code resultViewerSurveyIds} のみ上位条件として両者が共用する。</p>
     *
     * <p>{@code AFTER_CLOSE} の<b>所属</b>判定はここには載らない。snapshot の下向き再帰
     * （{@link UserScopeRoleSnapshot#isDescendantMemberOf}）で答えられ、そちらは
     * 組織が何件混ざっても<b>バルククエリ 1 本</b>で解決されるためである（Issue #2782 の回避）。</p>
     *
     * @param targetedSurveyIds     閲覧者が配信対象名簿に載っている survey_id（ALWAYS 用）
     * @param resultViewerSurveyIds 閲覧者が結果閲覧者名簿に載っている survey_id（上位条件・共用）
     * @param inOrgAudience         閲覧者が<b>配信母集団</b>に含まれる (組織, トグル) の組（ALWAYS 用）
     */
    private record AudienceContext(
            Set<Long> targetedSurveyIds,
            Set<Long> resultViewerSurveyIds,
            Set<OrgAudienceKey> inOrgAudience,
            Set<ScopeKey> manageSurveysScopes) {

        private static final AudienceContext EMPTY =
                new AudienceContext(Set.of(), Set.of(), Set.of(), Set.of());
    }

    /**
     * ALWAYS の判定（純メモリ。DB アクセスは {@link #prepareAdditionalAxisContext} で完了済み）。
     *
     * <p>「配信母集団に居るなら見える」という一本の規則で評価する。所属軸で近似すると、
     * 配信されていない者に見えたり（漏洩）、配信された者が 403 になったり（機能不全）するため、
     * <b>配信母集団を算出しているのと同一の述語</b>を用いる:</p>
     * <ul>
     *   <li><b>TARGETED</b>: {@code survey_targets} 名簿（回答時の関所と同じ述語の一括版）。</li>
     *   <li><b>ALL × ORGANIZATION</b>:
     *       {@code OrganizationMembershipService#resolveOrgDistributionAudienceRoots}
     *       — 配信母集団と同一（{@code include_supporters} トグル準拠・配下 ACTIVE チーム再帰）。
     *       これにより下向き再帰の有無も応援者の要否も自動的に整合する。
     *       単発版 {@code isInOrgDistributionAudience} と意味論は 1 対 1 同一で、
     *       違いは「組織ごとに 1 本」から「トグルごとに 1 本」へバルク化した点のみである
     *       （Issue #2782。{@link #resolveOrgAudience} 参照）。</li>
     *   <li><b>ALL × TEAM</b>: 当該チームへの直接所属（{@code SCOPE_AFFILIATED} と同一述語）。
     *       TEAM の母集団は配下展開もトグルも持たないため従来挙動を変えない。</li>
     * </ul>
     *
     * <p>設計書 §「結果閲覧権限の判定」の優先順位に従い、<b>当該スコープの ADMIN+ と
     * 結果閲覧者名簿の登録者は上記に関わらず閲覧可</b>（他スコープ・他テナントの ADMIN は通らない）。
     * SystemAdmin と DRAFT の作成者本人は本メソッド到達前に基底クラスで短絡され、
     * 公開済アンケートの作成者は {@code SurveyResultService#validateResultAccess} の
     * 作成者高速パスで Resolver 自体を通らない。</p>
     */
    private boolean evaluateAlways(
            SurveyVisibilityProjection row, Long viewerUserId,
            UserScopeRoleSnapshot snapshot, AudienceContext context) {
        return isInDistributionAudience(row, viewerUserId, snapshot, context);
    }

    /**
     * 閲覧者が当該アンケートの<b>配信母集団</b>に含まれるか（{@code ALWAYS} 専用）。
     *
     * <p>この 1 メソッドが「配信母集団の照合」の唯一の実装である。
     * {@code distribution_mode} と {@code include_supporters} に厳密に従うのが本述語の要点で、
     * <b>スコープ所属の判定（{@link #isScopeAffiliated}）とは意図的に別物</b>である
     * （理由は {@link #visibleByAdditionalAxis} の javadoc 参照。安易に統一しないこと）。</p>
     *
     * @param context {@link #prepareAdditionalAxisContext} で先読み済み（本メソッドは DB を触らない）
     */
    private boolean isInDistributionAudience(
            SurveyVisibilityProjection row, Long viewerUserId,
            UserScopeRoleSnapshot snapshot, AudienceContext context) {
        if (viewerUserId == null || row.id() == null) {
            return false;
        }
        // 上位条件（results_visibility を無視して常に閲覧可。優先順 2 / 3）。
        if (isPrivilegedViewer(row, snapshot, context)) {
            return true;
        }
        // TARGETED（および distribution_mode 欠損）は名簿がそのまま母集団。
        // 欠損時に名簿必須へ倒すのは「母集団を確定できないなら見せない」fail-closed の徹底。
        if (row.distributionMode() != DistributionMode.ALL) {
            return context.targetedSurveyIds().contains(row.id());
        }
        if ("ORGANIZATION".equals(row.scopeType()) && row.scopeId() != null) {
            return context.inOrgAudience().contains(new OrgAudienceKey(
                    row.scopeId(), Boolean.TRUE.equals(row.includeSupporters())));
        }
        return row.scopeType() != null && row.scopeId() != null
                && snapshot.isMemberOf(new ScopeKey(row.scopeType(), row.scopeId()));
    }

    /**
     * 当該アンケートのスコープにおいて ADMIN 以上か。
     *
     * <p>{@link StandardVisibility#ADMINS_AND_ABOVE} の評価
     * （{@code snapshot.hasRoleOrAbove(scope, "ADMIN")}）と<b>同一の述語</b>を用いる。
     * 参照するのは当該スコープへの直接ロールのみのため、他スコープ・他テナントの ADMIN は通らない。</p>
     */
    private static boolean isScopeAdmin(
            SurveyVisibilityProjection row, UserScopeRoleSnapshot snapshot) {
        if (row.scopeType() == null || row.scopeId() == null) {
            return false;
        }
        return snapshot.hasRoleOrAbove(new ScopeKey(row.scopeType(), row.scopeId()), "ADMIN");
    }

    /**
     * 配信対象名簿（{@code survey_targets}）の照会が必要な行か（{@code ALWAYS} かつ {@code ALL} 配信以外）。
     *
     * <p><b>{@code AFTER_CLOSE} は対象外である。</b>締切後の判定はスコープ所属であって配信母集団ではなく、
     * 名簿を条件にすると仕様（「スコープ所属者全員」）より狭くなるため
     * （{@link #visibleByAdditionalAxis} の javadoc 参照）。</p>
     */
    private static boolean requiresTargetRoster(SurveyVisibilityProjection row) {
        return row.resultsVisibility() == ResultsVisibility.ALWAYS
                && row.distributionMode() != DistributionMode.ALL;
    }

    @Override
    protected ContentStatus toContentStatus(SurveyVisibilityProjection row) {
        SurveyStatus status = row.status();
        if (status == null) {
            // fail-closed: status 欠損は不可視（DELETED 相当）
            return ContentStatus.DELETED;
        }
        return switch (status) {
            case DRAFT -> ContentStatus.DRAFT;
            case PUBLISHED, CLOSED -> ContentStatus.PUBLISHED;
            case ARCHIVED -> ContentStatus.ARCHIVED;
        };
    }

    @Override
    protected String customSubType(SurveyVisibilityProjection row) {
        ResultsVisibility v = row.resultsVisibility();
        return v == null ? "UNKNOWN" : v.name();
    }

    @Override
    protected boolean evaluateCustom(
            SurveyVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot,
            Object additionalAxisContext) {
        ResultsVisibility v = row.resultsVisibility();
        if (v == null) {
            return false;
        }
        return switch (v) {
            case AFTER_RESPONSE -> evaluateAfterResponse(row, viewerUserId);
            // AFTER_CLOSE は「時間条件 ∨ 上位条件」。上位条件（優先順 2/3）は results_visibility を
            // 無視して閲覧できる規定のため、時間条件で締め出してはならない（Issue #2774）。
            // 所属条件は visibleByAdditionalAxis 側で AND 合成される。
            case AFTER_CLOSE -> evaluateAfterClose(row)
                    || isPrivilegedViewer(row, snapshot,
                            additionalAxisContext instanceof AudienceContext c
                                    ? c
                                    : AudienceContext.EMPTY);
            case VIEWERS_ONLY -> evaluateViewersOnly(row, viewerUserId);
            case ALWAYS -> evaluateAlways(row, viewerUserId, snapshot,
                    additionalAxisContext instanceof AudienceContext ctx
                            ? ctx
                            : AudienceContext.EMPTY);
            // ADMINS_ONLY は CUSTOM ではないため本来到達しない
            // （Mapper で StandardVisibility へ正規化済）。万一到達した場合は fail-closed。
            case ADMINS_ONLY -> false;
        };
    }

    /**
     * AFTER_RESPONSE — 回答済みユーザーのみ可視。
     *
     * <p>匿名閲覧（{@code viewerUserId == null}）は fail-closed。
     * 回答有無は {@link SurveyResponseRepository#existsBySurveyIdAndUserId} で判定。</p>
     */
    private boolean evaluateAfterResponse(SurveyVisibilityProjection row, Long viewerUserId) {
        if (viewerUserId == null || row.id() == null) {
            return false;
        }
        // 自分自身が作成者の場合も「回答済みであるか」のみで判定する。
        // 設計書 §5.1.4: CUSTOM の意味論は機能側既存挙動と一致させる
        // (SurveyResultService.validateResultAccess の AFTER_RESPONSE と同等)。
        return surveyResponseRepository.existsBySurveyIdAndUserId(row.id(), viewerUserId);
    }

    /**
     * AFTER_CLOSE の<b>時間条件のみ</b>を評価する（締切を過ぎているか）。
     *
     * <p>{@link SurveyStatus#CLOSED} は管理者が明示的に締め切った状態のため、
     * expiresAt の有無にかかわらず AFTER_CLOSE 条件を満足したものとみなす。</p>
     *
     * <p>PUBLISHED 状態での時刻ベース判定: {@code expiresAt == null}（締切未設定）は
     * fail-closed（軍議裁可済 2026-05-04）。判定は {@code now > expiresAt}（境界では未公開のまま）。</p>
     *
     * <p><b>これは時間軸だけの判定である</b>（Issue #2774）。閲覧者がアンケートの
     * スコープに所属するかは {@link #visibleByAdditionalAxis} が AND で合成する。
     * また上位条件（{@link #isPrivilegedViewer}）は本メソッドの結果に OR で合成され、
     * 時間軸を貫通する（呼び出し元 {@link #evaluateCustom} を参照）。
     * 本メソッドが閲覧者の識別子を取らないのは、時間条件と所属条件を別々の軸として
     * 直交させているためであり、所属確認を省いてよいという意味ではない。</p>
     */
    private boolean evaluateAfterClose(SurveyVisibilityProjection row) {
        // 明示的に締め切られた → AFTER_CLOSE 条件満足
        if (row.status() == SurveyStatus.CLOSED) {
            return true;
        }
        LocalDateTime expiresAt = row.expiresAt();
        if (expiresAt == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * VIEWERS_ONLY — {@code survey_result_viewers} に登録されたユーザーのみ可視。
     *
     * <p>匿名閲覧（{@code viewerUserId == null}）は fail-closed。
     * 作成者本人かどうかは本判定では考慮しない（設計書 §5.1.4: CUSTOM の意味論を厳密に
     * 「限定リスト」のみとする。創作者の閲覧経路は呼び出し側で別途担保する）。</p>
     */
    private boolean evaluateViewersOnly(SurveyVisibilityProjection row, Long viewerUserId) {
        if (viewerUserId == null || row.id() == null) {
            return false;
        }
        // 作成者は限定リストに自動追加される運用を前提とするが、本 Resolver では
        // viewers リストの存在/非存在のみを純粋に判定する（fail-closed の徹底）。
        // 「作成者でも viewers にいなければ false」となる点は呼び出し側設計上の責務。
        return Objects.requireNonNullElse(
                surveyResultViewerRepository.existsBySurveyIdAndUserId(row.id(), viewerUserId),
                false);
    }
}

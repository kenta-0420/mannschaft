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
 *       （締切後のみ可視。{@code expiresAt} 未設定は fail-closed）</li>
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

    public SurveyVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            SurveyRepository surveyRepository,
            SurveyResponseRepository surveyResponseRepository,
            SurveyResultViewerRepository surveyResultViewerRepository,
            SurveyTargetRepository surveyTargetRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.surveyRepository = surveyRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.surveyResultViewerRepository = surveyResultViewerRepository;
        this.surveyTargetRepository = surveyTargetRepository;
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
        // ALWAYS の可視範囲を配信母集団に一致させる（#2617-3 の是正）。
        //
        // 設計書 F05.4 の distribution_mode 備考どおり、ORGANIZATION スコープ × ALL 配信の母集団は
        // 「組織直属メンバー ∪ 配下参加チーム（ACTIVE）のメンバー」へ再帰展開される
        // （実装は SurveyResultService#resolveUniverseUserIds →
        //   OrganizationMembershipService#resolveOrgDistributionUserIds）。
        // Mapper が返す SCOPE_AFFILIATED は当該 ORG への直接所属しか見ないため、
        // そのままでは「アンケートは届くのに ALWAYS の結果だけ 403」という食い違いが生じる。
        // 配信 universe と評価範囲を一致させるため、下向き再帰の既存軸へ昇格する。
        //
        // TEAM スコープは配下展開を持たない（母集団 = user_roles の当該スコープ行）ため
        // SCOPE_AFFILIATED のまま据え置き、従来挙動を一切変えない。
        if (row.resultsVisibility() == ResultsVisibility.ALWAYS && organizationScope) {
            return StandardVisibility.ORGANIZATION_AND_DESCENDANTS;
        }
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
     *       （{@code SurveyTargetRepository#existsBySurveyIdAndUserId} — 回答時の関所
     *       {@code SurveyResponseService} と同じ述語）。スコープ所属だけでは通さない。</li>
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
     * 名簿判定に必要な {@code survey_targets} 所属集合を<b>バッチ 1 回につき 1 本のクエリ</b>で先読みする。
     *
     * <p>行ごとに {@code existsBySurveyIdAndUserId} を呼ぶと、{@code filterAccessible} に
     * N 件渡されたとき N 本の SQL が飛び（N+1）、設計書
     * {@code F00_content_visibility_resolver.md} §9 のバッチ SQL 本数上限・性能目標に反する。
     * 名簿照会が必要なのは「{@code ALWAYS} かつ {@code ALL} 以外」の行だけなので、
     * その ID を集めて 1 本の {@code WHERE survey_id IN (:ids) AND user_id = :userId} で引く。</p>
     *
     * <p>対象行が無ければクエリを発行しない（{@code ALL} 配信のみの一覧に本ゲートのコストを乗せない）。
     * 未認証（{@code viewerUserId == null}）も同様にクエリ不要で、判定側が fail-closed する。</p>
     *
     * @return 閲覧者が配信対象である survey_id の集合（不要な場合は空集合）
     */
    @Override
    protected Object prepareAdditionalAxisContext(
            List<SurveyVisibilityProjection> rows, Long viewerUserId) {
        if (viewerUserId == null || rows == null || rows.isEmpty()) {
            return Set.of();
        }
        Set<Long> rosterCheckIds = new HashSet<>();
        for (SurveyVisibilityProjection row : rows) {
            if (row != null && row.id() != null && requiresTargetRoster(row)) {
                rosterCheckIds.add(row.id());
            }
        }
        if (rosterCheckIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(surveyTargetRepository.findTargetedSurveyIds(rosterCheckIds, viewerUserId));
    }

    /**
     * ALWAYS の追加軸判定（純メモリ。DB アクセスは
     * {@link #prepareAdditionalAxisContext} で完了している）。
     */
    @Override
    @SuppressWarnings("unchecked")
    protected boolean visibleByAdditionalAxis(
            SurveyVisibilityProjection row, Long viewerUserId,
            UserScopeRoleSnapshot snapshot, StandardVisibility level,
            Object additionalAxisContext) {
        if (row.resultsVisibility() != ResultsVisibility.ALWAYS) {
            return true;
        }
        // TARGETED（および distribution_mode 欠損）は名簿必須。欠損時に名簿必須へ倒すのは
        // 「母集団を確定できないなら見せない」fail-closed の徹底。
        if (requiresTargetRoster(row)) {
            if (viewerUserId == null || row.id() == null) {
                return false;
            }
            Set<Long> targetedSurveyIds = additionalAxisContext instanceof Set<?> set
                    ? (Set<Long>) set
                    : Set.of();
            return targetedSurveyIds.contains(row.id());
        }
        if (level != StandardVisibility.ORGANIZATION_AND_DESCENDANTS
                || Boolean.TRUE.equals(row.includeSupporters())) {
            return true;
        }
        // 応援者除外はスナップショット上のロールで判定するため追加クエリは発生しない。
        return snapshot.hasDescendantRoleOrAbove(
                new ScopeKey(row.scopeType(), row.scopeId()), "MEMBER");
    }

    /** 名簿照会が必要な行か（ALWAYS かつ ALL 配信以外）。 */
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
            SurveyVisibilityProjection row, Long viewerUserId, UserScopeRoleSnapshot snapshot) {
        ResultsVisibility v = row.resultsVisibility();
        if (v == null) {
            return false;
        }
        return switch (v) {
            case AFTER_RESPONSE -> evaluateAfterResponse(row, viewerUserId);
            case AFTER_CLOSE -> evaluateAfterClose(row);
            case VIEWERS_ONLY -> evaluateViewersOnly(row, viewerUserId);
            // ADMINS_ONLY / ALWAYS は CUSTOM ではないため本来到達しない
            // （Mapper で StandardVisibility へ正規化済）。万一到達した場合は fail-closed。
            case ADMINS_ONLY, ALWAYS -> false;
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
     * AFTER_CLOSE — 締切後のみ可視。
     *
     * <p>{@link SurveyStatus#CLOSED} は管理者が明示的に締め切った状態のため、
     * expiresAt の有無にかかわらず AFTER_CLOSE 条件を満足したものとみなす。</p>
     *
     * <p>PUBLISHED 状態での時刻ベース判定: {@code expiresAt == null}（締切未設定）は
     * fail-closed（軍議裁可済 2026-05-04）。判定は {@code now > expiresAt}（境界では未公開のまま）。</p>
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

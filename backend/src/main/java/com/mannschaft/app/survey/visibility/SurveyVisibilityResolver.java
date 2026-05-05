package com.mannschaft.app.survey.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.SurveyResultsVisibilityMapper;
import com.mannschaft.app.survey.ResultsVisibility;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyResultViewerRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * F00 Phase C — {@link ReferenceType#SURVEY} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §5.1 / §5.1.4 / §7.5 / §11.6 / §15 D-13/D-14/D-16。</p>
 *
 * <p><strong>機能側 visibility との対応</strong>（§5.2）:</p>
 * <ul>
 *   <li>{@link ResultsVisibility#ADMINS_ONLY} → {@link StandardVisibility#ADMINS_ONLY}</li>
 *   <li>{@link ResultsVisibility#AFTER_RESPONSE} → {@link StandardVisibility#CUSTOM}
 *       （回答済みユーザーのみ可視。判定は {@link SurveyResponseRepository}）</li>
 *   <li>{@link ResultsVisibility#AFTER_CLOSE} → {@link StandardVisibility#CUSTOM}
 *       （締切後のみ可視。{@code expiresAt} 未設定は fail-closed）</li>
 *   <li>{@link ResultsVisibility#VIEWERS_ONLY} → {@link StandardVisibility#CUSTOM}
 *       （限定リスト {@code survey_result_viewers} に登録済みのみ可視）</li>
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

    public SurveyVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            SurveyRepository surveyRepository,
            SurveyResponseRepository surveyResponseRepository,
            SurveyResultViewerRepository surveyResultViewerRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.surveyRepository = surveyRepository;
        this.surveyResponseRepository = surveyResponseRepository;
        this.surveyResultViewerRepository = surveyResultViewerRepository;
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
            // ADMINS_ONLY は CUSTOM ではないため本来到達しない（Mapper で StandardVisibility へ正規化済）。
            // 万一到達した場合は fail-closed。
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
     * AFTER_CLOSE — 締切後のみ可視。
     *
     * <p>{@code expiresAt == null}（締切未設定）は fail-closed（軍議裁可済 2026-05-04）。
     * 判定は {@code now > expiresAt}（境界では未公開のまま）。</p>
     */
    private boolean evaluateAfterClose(SurveyVisibilityProjection row) {
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

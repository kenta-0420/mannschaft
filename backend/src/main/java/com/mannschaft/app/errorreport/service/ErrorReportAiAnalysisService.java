package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * F12.5 Phase 2-C — エラーレポート AI 分析の中核サービス。
 *
 * <p>{@link ErrorReportClaudeAiProvider} を呼び出し、
 * 結果を {@code error_report_ai_analyses} に永続化する。
 * SUCCESS / FAILED いずれの場合も {@code last_ai_analysis_at} を更新して
 * 再試行ループを防ぐ。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorReportAiAnalysisService {

    /** ユーザーコメント抜粋の最大件数（plan §2.4）。 */
    private static final int RECENT_COMMENT_LIMIT = 3;

    /** 1件あたり保守的な推定コスト（推定 prompt 4000 + completion 1500 → 約 6 円、安全側で 10 円）。 */
    static final int CONSERVATIVE_COST_ESTIMATE_JPY = 10;

    /** {@code suggested_files} カラムへ保存する際の最大件数。 */
    private static final int SUGGESTED_FILES_MAX = 10;

    /** {@code suggested_files} カラム（VARCHAR 1000）の長さ上限。 */
    private static final int SUGGESTED_FILES_MAX_LENGTH = 1000;

    /** {@code error_message} カラム（VARCHAR 500）の長さ上限。 */
    private static final int ERROR_MESSAGE_MAX_LENGTH = 500;

    /** ユーザーコメント1件あたりの最大文字数。 */
    private static final int USER_COMMENT_MAX_CHARS = 200;

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;
    private final ErrorReportClaudeAiProvider provider;
    private final ErrorReportSanitizer sanitizer;
    private final ErrorReportAiBudgetService budgetService;
    private final ErrorReportActivityService activityService;
    private final ErrorReportNotifier notifier;
    private final ErrorReportProperties props;

    // Issue #2990 L4: analyzeAfterCommit / analyzeAsync は自己呼び出しにより @Async と @Transactional が
    // いずれも失効していたため、ErrorReportAiAnalysisDispatcher / ErrorReportAiAnalysisAsyncRunner へ
    // 段ごとに Bean を分けて移設した（各ホップがプロキシ境界を跨ぐようにするため）。

    /**
     * 同期的に AI 分析を実行する（手動再分析用）。
     *
     * @param errorReportId エラーレポート ID
     * @param createdBy     操作者ユーザー ID
     * @return 永続化された分析履歴エンティティ
     */
    @Transactional
    public ErrorReportAiAnalysisEntity analyzeSync(Long errorReportId, Long createdBy) {
        if (!props.getAi().isEnabled()) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_007);
        }

        // 予算チェック（保守的に上限値で計算）
        if (!budgetService.canExpend(CONSERVATIVE_COST_ESTIMATE_JPY)) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_008);
        }

        ErrorReportEntity report = errorReportRepository.findById(errorReportId)
                .orElseThrow(() -> new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND));

        SanitizedErrorContext ctx = buildContext(report);

        ErrorReportAiAnalysisEntity entity;
        try {
            AiAnalysisResult result = provider.analyze(ctx);

            // 実コスト計上
            int costJpy = ClaudeModelPricing.estimateJpy(
                    props.getAi().getModel(),
                    result.getPromptTokens(),
                    result.getCompletionTokens());
            budgetService.recordExpense(costJpy);

            String suggestedFiles = serializeSuggestedFiles(result.getSuggestedFiles());

            entity = ErrorReportAiAnalysisEntity.builder()
                    .errorReportId(errorReportId)
                    .modelName(props.getAi().getModel())
                    .promptTokens(result.getPromptTokens())
                    .completionTokens(result.getCompletionTokens())
                    .estimatedCause(result.getEstimatedCause())
                    .fixProposal(result.getFixProposal())
                    .impactAssessment(result.getImpactAssessment())
                    .suggestedFiles(suggestedFiles)
                    .rawResponse(result.getRawResponse())
                    .status("SUCCESS")
                    .createdBy(createdBy)
                    .build();
            entity = aiAnalysisRepository.save(entity);

            // 親レコードの最終分析日時を更新
            report.setLastAiAnalysisAt(LocalDateTime.now());

            // activities に記録
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("modelName", props.getAi().getModel());
            metadata.put("promptTokens", result.getPromptTokens());
            metadata.put("completionTokens", result.getCompletionTokens());
            if (createdBy == null) {
                activityService.recordSystemActivity(
                        errorReportId, ErrorReportActivityType.AI_ANALYZED, metadata);
            } else {
                activityService.record(
                        errorReportId, createdBy,
                        ErrorReportActivityType.AI_ANALYZED, null, metadata);
            }

            // CRITICAL のみ通知
            if (report.getSeverity() == ErrorReportSeverity.CRITICAL) {
                notifier.notifyAiAnalysisCompleted(report, entity);
            }
            return entity;
        } catch (BusinessException e) {
            // 予算系エラーは saved レコードを残さず再投入
            throw e;
        } catch (Exception e) {
            // FAILED の永続化（再試行ループを防ぐため last_ai_analysis_at は更新する）
            entity = ErrorReportAiAnalysisEntity.builder()
                    .errorReportId(errorReportId)
                    .modelName(props.getAi().getModel())
                    .status("FAILED")
                    .errorMessage(truncate(e.getMessage(), ERROR_MESSAGE_MAX_LENGTH))
                    .createdBy(createdBy)
                    .build();
            aiAnalysisRepository.save(entity);
            report.setLastAiAnalysisAt(LocalDateTime.now());
            throw new RuntimeException("AI 分析に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * エラーレポートと最近の発生ログから AI 用コンテキストを構築する。
     */
    SanitizedErrorContext buildContext(ErrorReportEntity report) {
        // 最近の occurrences から user_comment を最大3件取得
        // ※ Phase 2 の occurrences スキーマには user_comment カラムが無いため、
        //   親 report の user_comment / latest_user_comment を使用する。
        List<String> comments = collectRecentComments(report);

        return SanitizedErrorContext.builder()
                .errorMessage(sanitizer.sanitize(report.getErrorMessage()))
                .stackTrace(sanitizer.sanitize(report.getStackTrace()))
                .pageUrlPath(sanitizer.sanitizePagePath(report.getPageUrl()))
                .firstOccurredAt(report.getFirstOccurredAt())
                .lastOccurredAt(report.getLastOccurredAt())
                .occurrenceCount(report.getOccurrenceCount() != null ? report.getOccurrenceCount() : 0)
                .affectedUserCount(report.getAffectedUserCount() != null ? report.getAffectedUserCount() : -1)
                .recentUserComments(comments)
                .build();
    }

    /**
     * 親 report と最近の occurrences からユーザーコメント候補を最大3件、各200字以内で集める。
     * 各値はサニタイズ済み。
     */
    private List<String> collectRecentComments(ErrorReportEntity report) {
        List<String> comments = new java.util.ArrayList<>();
        // 親 report のコメントを優先で 1〜2 件
        if (report.getLatestUserComment() != null && !report.getLatestUserComment().isBlank()) {
            comments.add(truncate(sanitizer.sanitize(report.getLatestUserComment()), USER_COMMENT_MAX_CHARS));
        }
        if (report.getUserComment() != null && !report.getUserComment().isBlank()
                && (report.getLatestUserComment() == null
                || !report.getUserComment().equals(report.getLatestUserComment()))) {
            comments.add(truncate(sanitizer.sanitize(report.getUserComment()), USER_COMMENT_MAX_CHARS));
        }

        // occurrences 側に user_comment カラムが無くても、件数把握のため最近の発生を考慮（将来拡張）。
        // ここでは parent コメントだけを返し、3件に満たない場合はそのまま返す。
        if (comments.size() > RECENT_COMMENT_LIMIT) {
            return comments.subList(0, RECENT_COMMENT_LIMIT);
        }
        return comments;
    }

    /**
     * 関連ファイル候補をカンマ区切り文字列にシリアライズする。
     * 最大10件 × 1000文字でクリップする。
     */
    String serializeSuggestedFiles(List<String> files) {
        if (files == null || files.isEmpty()) return null;
        List<String> capped = files.size() > SUGGESTED_FILES_MAX
                ? files.subList(0, SUGGESTED_FILES_MAX)
                : files;
        String joined = capped.stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(","));
        if (joined.isEmpty()) return null;
        if (joined.length() > SUGGESTED_FILES_MAX_LENGTH) {
            return joined.substring(0, SUGGESTED_FILES_MAX_LENGTH);
        }
        return joined;
    }

    /**
     * 文字列を指定長に切り詰める。NULL 安全。
     */
    static String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength ? str : str.substring(0, maxLength);
    }
}

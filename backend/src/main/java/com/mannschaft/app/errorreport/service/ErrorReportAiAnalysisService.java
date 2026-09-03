package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
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
    private final ErrorReportClaudeAiProvider provider;
    private final ErrorReportSanitizer sanitizer;
    private final ErrorReportAiBudgetService budgetService;
    private final ErrorReportNotifier notifier;
    private final ErrorReportProperties props;
    /** Issue #2990 L4 検分是正: FAILED 記録を呼び出し元のロールバックから切り離すための独立TX Bean。 */
    private final ErrorReportAiAnalysisFailureRecorder failureRecorder;
    /** Issue #2990 L4 再検分是正: SUCCESS の書き込みを AI 呼び出しの外の短命TXに閉じ込めるための Bean。 */
    private final ErrorReportAiAnalysisResultRecorder resultRecorder;

    // Issue #2990 L4: analyzeAfterCommit / analyzeAsync は自己呼び出しにより @Async と @Transactional が
    // いずれも失効していたため、ErrorReportAiAnalysisDispatcher / ErrorReportAiAnalysisAsyncRunner へ
    // 段ごとに Bean を分けて移設した（各ホップがプロキシ境界を跨ぐようにするため）。

    /**
     * 同期的に AI 分析を実行する（手動再分析用）。
     *
     * <h2>3段構成である理由（Issue #2990 L4 再検分是正）</h2>
     * <p>本メソッドには<b>意図的に {@code @Transactional} を付けていない</b>。
     * 内訳は次の3段で、Claude API への HTTP 呼び出し（②）は<b>トランザクションの外</b>で走る。</p>
     * <pre>
     *   ① 読み取り  : findById / buildContext        （リポジトリ単位の短命TX）
     *   ② AI 呼び出し: provider.analyze              （TXなし＝DB接続を1本も握らない）
     *   ③ 書き込み  : ErrorReportAiAnalysisResultRecorder#recordSuccess（短命TX）
     *                 失敗時は ErrorReportAiAnalysisFailureRecorder#recordFailure（短命TX）
     * </pre>
     * <p>是正前は①〜③が単一トランザクションで、AI 応答を待つ秒〜分のあいだ Hikari 接続を
     * 占有していた。管理者の再分析 API は HTTP スレッドから本メソッドを直接呼ぶため
     * {@code ai-analysis-pool} の max2 では同時実行数を縛れず、
     * 「接続を握ったまま AI を待つ外側」＋「追加接続を要求する {@code REQUIRES_NEW} の失敗記録」で
     * 接続枯渇 → FAILED 記録自体の失敗 → 再試行ループ再発、という経路が成立していた。
     * また③の途中（{@code last_ai_analysis_at} 更新後）で失敗すると、外側が握った
     * {@code error_reports} の行ロックを内側の {@code REQUIRES_NEW} が待つ自己デッドロックになった。
     * TX を跨がせないことで、これらの根（AI 呼び出しが TX 内にあること）を断っている。</p>
     *
     * <p><b>本メソッドを外側トランザクションの中から呼んではならない。</b>
     * 呼ぶと②の最中に接続が握られ、上記の問題がそのまま復活する。
     * 唯一の同期呼び出し元である {@code SystemAdminErrorReportController#reanalyze} は
     * {@code @Transactional} を持たない。この制約は
     * {@code ErrorReportAiAnalysisTransactionBoundaryTest} が機械的に固定している。</p>
     *
     * @param errorReportId エラーレポート ID
     * @param createdBy     操作者ユーザー ID
     * @return 永続化された分析履歴エンティティ
     */
    public ErrorReportAiAnalysisEntity analyzeSync(Long errorReportId, Long createdBy) {
        if (!props.getAi().isEnabled()) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_007);
        }

        // 予算チェック（保守的に上限値で計算）
        if (!budgetService.canExpend(CONSERVATIVE_COST_ESTIMATE_JPY)) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_008);
        }

        // ① 読み取り（短命TX。以降 report は detached なので、書き込みは③で読み直す）
        ErrorReportEntity report = errorReportRepository.findById(errorReportId)
                .orElseThrow(() -> new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND));
        SanitizedErrorContext ctx = buildContext(report);

        // ② AI 呼び出し（TX外）と ③ 書き込み。
        //   catch はこの2段のみを覆う。通知（④）を含めないのは、
        //   分析が成功して記録も確定したあとの通知失敗まで FAILED として記録すると、
        //   同一レポートに SUCCESS と FAILED の両方が立ち履歴が嘘になるため。
        ErrorReportAiAnalysisEntity entity;
        try {
            AiAnalysisResult result = provider.analyze(ctx);

            int costJpy = ClaudeModelPricing.estimateJpy(
                    props.getAi().getModel(),
                    result.getPromptTokens(),
                    result.getCompletionTokens());

            entity = resultRecorder.recordSuccess(
                    errorReportId,
                    props.getAi().getModel(),
                    result,
                    serializeSuggestedFiles(result.getSuggestedFiles()),
                    costJpy,
                    createdBy,
                    LocalDateTime.now());
        } catch (BusinessException e) {
            // 予算系エラーは saved レコードを残さず再投入
            throw e;
        } catch (Exception e) {
            // FAILED の永続化（再試行ループを防ぐため last_ai_analysis_at は更新する）。
            //
            // ここに到達する経路は2つある。
            //   (a) ② の AI 呼び出しが失敗した   → DB には何も書いていない
            //   (b) ③ の書き込みが失敗した       → recordSuccess のTXは戻る前に既にロールバック済み
            // いずれの場合も<b>この時点で有効なトランザクションは存在しない</b>。したがって
            // recordFailure（REQUIRES_NEW）が待つべき行ロックも、握られたままの接続も無い。
            // 是正前は analyzeSync 自体が @Transactional だったため (b) の経路で
            // 「外側が error_reports の行ロックを保持したまま内側が同じ行を更新する」
            // 自己デッドロックが成立しえた。成立には setLastAiAnalysisAt の後に
            // error_reports を巻き込むオートフラッシュ（JPQL/ネイティブクエリ）が必要で、
            // 条件は当初の見立てより狭い（実測の詳細は ErrorReportAiAnalysisFailureRecordIT の javadoc）。
            // 現在は TX 自体が無いため、フラッシュ契機に関係なく成立しない。
            //
            // 例外は握り潰さず投げ直す。手動再分析 API は失敗を呼び出し元に返す必要があり、
            // 「投げるのをやめる」は失敗を隠す対処療法になるため採らない。
            failureRecorder.recordFailure(
                    errorReportId,
                    props.getAi().getModel(),
                    truncate(e.getMessage(), ERROR_MESSAGE_MAX_LENGTH),
                    createdBy,
                    LocalDateTime.now());
            throw new RuntimeException("AI 分析に失敗しました: " + e.getMessage(), e);
        }

        // ④ CRITICAL のみ通知。記録済みの分析結果を通知失敗で巻き戻さないため、TX の外・catch の外で行う。
        //    通知の失敗は握り潰さず ERROR ログで可視化したうえで、分析結果自体は呼び出し元へ返す。
        if (report.getSeverity() == ErrorReportSeverity.CRITICAL) {
            try {
                notifier.notifyAiAnalysisCompleted(report, entity);
            } catch (Exception e) {
                log.error("AI 分析完了通知の送信に失敗（分析結果自体は記録済み）: errorReportId={}",
                        errorReportId, e);
            }
        }
        return entity;
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

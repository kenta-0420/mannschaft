package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AI 分析失敗（{@code status="FAILED"}）の記録を独立トランザクションで確定させる Bean（Issue #2990 L4 検分是正）。
 *
 * <h2>なぜ独立トランザクションが要るのか</h2>
 * <p>{@link ErrorReportAiAnalysisService#analyzeSync} は catch 節で FAILED 履歴を保存し
 * {@code last_ai_analysis_at} を更新したうえで例外を投げ直す。コメントが明言するとおり、この更新の
 * 目的は<b>再試行ループの防止</b>である（{@link ErrorReportAiAnalysisBatch} は
 * {@code last_ai_analysis_at IS NULL} を検索条件にしているため、更新されないと同じレポートを
 * 5 分ごとに永久に AI へ投げ続ける）。</p>
 *
 * <p>ところが {@code analyzeSync} の {@code @Transactional} は Issue #2990 L4 で
 * <b>初めて実効化した</b>（それまでは自己呼び出しでプロキシを経ず失効していた）。実効化した結果、
 * 既定のロールバック規則（未検査例外でロールバック）により、catch 節で保存した FAILED 行も
 * {@code last_ai_analysis_at} の更新も<b>投げ直した例外と一緒に巻き戻る</b>ようになった。
 * 「宣言された意図（再試行ループを防ぐ）」と「実際の挙動（何も残らず永久に再試行する）」が
 * 矛盾する状態である。</p>
 *
 * <p>本 Bean を {@link Propagation#REQUIRES_NEW} で分離することで、呼び出し元のロールバックと
 * 無関係に FAILED 記録が確定する。例外は依然として上位へ投げるため、失敗が失敗として
 * 伝わる性質（手動再分析 API の 5xx・バッチのログ）は変わらない。</p>
 *
 * <p>{@code REQUIRES_NEW} は同一 Bean 内の自己呼び出しでは効かないため、別 Bean に切り出している。</p>
 *
 * <h2>デッドロックしない根拠</h2>
 * <p>呼び出し元の外側トランザクションは、この catch に到達する時点で {@code error_reports} 行に対して
 * <b>読み取りしか行っていない</b>（AI 呼び出しは {@code buildContext} の直後・親の更新より前に走る）。
 * したがって内側の独立トランザクションが同じ行を更新しても、外側が保持する行ロックと競合しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorReportAiAnalysisFailureRecorder {

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;

    /**
     * FAILED 履歴の保存と {@code last_ai_analysis_at} の更新を独立トランザクションで確定させる。
     *
     * <p>親レコードは<b>この独立トランザクションの中で読み直す</b>。呼び出し元が保持している
     * エンティティは外側トランザクションの永続コンテキストに属しており、そこへの変更は
     * 外側のロールバックと一緒に消えるためである。</p>
     *
     * @param errorReportId エラーレポート ID
     * @param modelName     使用予定だったモデル名
     * @param errorMessage  失敗理由（呼び出し元でカラム長に切り詰め済み）
     * @param createdBy     操作者ユーザー ID（システム自動なら NULL）
     * @param analyzedAt    {@code last_ai_analysis_at} に記録する時刻。呼び出し元から渡すのは、
     *                      日時ポリシーの番人（{@code DateTimeAndZoneGuardTest}）が引数なし
     *                      {@code now()} の新規クラスへの追加を禁じているため。成功経路と同じ
     *                      1 箇所で時刻を採る形に揃う
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long errorReportId, String modelName, String errorMessage,
                              Long createdBy, LocalDateTime analyzedAt) {
        aiAnalysisRepository.save(ErrorReportAiAnalysisEntity.builder()
                .errorReportId(errorReportId)
                .modelName(modelName)
                .status("FAILED")
                .errorMessage(errorMessage)
                .createdBy(createdBy)
                .build());

        ErrorReportEntity report = errorReportRepository.findById(errorReportId).orElse(null);
        if (report == null) {
            // レポート自体が消えているなら再試行ループも起きない。記録だけ残して黙って戻る。
            log.warn("AI 分析失敗の記録: 親レポートが見当たらず last_ai_analysis_at を更新できない: errorReportId={}",
                    errorReportId);
            return;
        }
        report.setLastAiAnalysisAt(analyzedAt);
        errorReportRepository.save(report);
    }
}

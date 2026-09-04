package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 分析成功時の書き込みを担う「第3段」Bean（Issue #2990 L4 再検分是正）。
 *
 * <h2>なぜ書き込みだけを別 Bean に切り出したのか</h2>
 * <p>是正前の {@link ErrorReportAiAnalysisService#analyzeSync} は
 * <b>1つのトランザクションの中で Claude API への HTTP 呼び出しまで行っていた</b>。
 * これが本 PR で繰り返し問題の根になった:</p>
 * <ol>
 *   <li><b>接続保持</b> — AI 応答を待つ秒〜分のあいだ Hikari 接続を占有し続ける。
 *       管理者の再分析 API（{@code POST /system-admin/error-reports/&#123;id&#125;/ai-analyses}）は
 *       HTTP スレッドから直接この経路を叩くため {@code ai-analysis-pool} の max2 では縛れず、
 *       同時実行数は接続プール（CI 5 / 本番既定 50）に対して無制限だった。</li>
 *   <li><b>接続枯渇</b> — 失敗時に {@code REQUIRES_NEW} の
 *       {@link ErrorReportAiAnalysisFailureRecorder} が<b>追加の</b>接続を要求するため、
 *       外側が接続を握ったまま AI を待っている状況が重なると FAILED 記録自体が
 *       接続取得タイムアウトで失敗し、再試行ループ防止という目的が破れる。</li>
 *   <li><b>自己デッドロック</b> — 外側TXが {@code error_reports} 行を更新したあと
 *       （{@code setLastAiAnalysisAt} のオートフラッシュ）に後続処理が失敗すると、
 *       同じ行を更新する {@code REQUIRES_NEW} が外側の行ロックを待ち、
 *       外側は内側の完了を待つ = ロック待ちタイムアウトまで固まる。</li>
 * </ol>
 *
 * <p>そこで処理を3段に割った。「①読み取り（TX内・短命）→ ②AI 呼び出し（<b>TX外</b>）→
 * ③書き込み（TX内・短命）」である。本 Bean が③にあたる。
 * AI 呼び出し中に DB 接続を1本も握らなくなるため、上記3つが同時に消える。</p>
 *
 * <p>別 Bean にしてあるのは、同一クラス内の自己呼び出しでは {@code @Transactional} が
 * プロキシを経ず失効するためである（本 PR が是正した欠陥そのもの）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorReportAiAnalysisResultRecorder {

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;
    private final ErrorReportAiBudgetService budgetService;
    private final ErrorReportActivityService activityService;

    /**
     * SUCCESS 履歴の保存・実コスト計上・{@code last_ai_analysis_at} 更新・activity 記録を
     * 1つの短命なトランザクションで確定させる。
     *
     * <p>親レコードは<b>このトランザクションの中で読み直す</b>。呼び出し元が持っている
     * エンティティは①段のトランザクション終了とともに detached になっているためである。</p>
     *
     * @param errorReportId  エラーレポート ID
     * @param modelName      使用したモデル名
     * @param result         AI 応答
     * @param suggestedFiles シリアライズ済みの関連ファイル候補
     * @param costJpy        実コスト（円）
     * @param createdBy      操作者ユーザー ID（システム自動なら NULL）
     * @param analyzedAt     {@code last_ai_analysis_at} に記録する時刻。引数で渡すのは
     *                       日時ポリシーの番人（{@code DateTimeAndZoneGuardTest}）が
     *                       引数なし {@code now()} の新規クラスへの追加を禁じているため
     * @return 永続化された分析履歴エンティティ
     */
    @Transactional
    public ErrorReportAiAnalysisEntity recordSuccess(Long errorReportId, String modelName,
                                                     AiAnalysisResult result, String suggestedFiles,
                                                     int costJpy, Long createdBy,
                                                     LocalDateTime analyzedAt) {
        budgetService.recordExpense(costJpy);

        ErrorReportAiAnalysisEntity entity = aiAnalysisRepository.save(
                ErrorReportAiAnalysisEntity.builder()
                        .errorReportId(errorReportId)
                        .modelName(modelName)
                        .promptTokens(result.getPromptTokens())
                        .completionTokens(result.getCompletionTokens())
                        .estimatedCause(result.getEstimatedCause())
                        .fixProposal(result.getFixProposal())
                        .impactAssessment(result.getImpactAssessment())
                        .suggestedFiles(suggestedFiles)
                        .rawResponse(result.getRawResponse())
                        .status("SUCCESS")
                        .createdBy(createdBy)
                        .build());

        ErrorReportEntity report = errorReportRepository.findById(errorReportId).orElse(null);
        if (report != null) {
            report.setLastAiAnalysisAt(analyzedAt);
            errorReportRepository.save(report);
        } else {
            // レポートが消えていても分析履歴は残す。再試行ループの母集団からも消えているので実害はない。
            log.warn("AI 分析成功の記録: 親レポートが見当たらず last_ai_analysis_at を更新できない: errorReportId={}",
                    errorReportId);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("modelName", modelName);
        metadata.put("promptTokens", result.getPromptTokens());
        metadata.put("completionTokens", result.getCompletionTokens());
        if (createdBy == null) {
            activityService.recordSystemActivity(
                    errorReportId, ErrorReportActivityType.AI_ANALYZED, metadata);
        } else {
            activityService.record(
                    errorReportId, createdBy, ErrorReportActivityType.AI_ANALYZED, null, metadata);
        }
        return entity;
    }
}

package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * エラーレポート AI 即時分析の起動 Bean（Issue #2990 L4）。
 *
 * <h2>是正前の欠陥: 同一クラス内の自己呼び出しで {@code @Async} と {@code @Transactional} が二重に失効</h2>
 * <p>是正前は {@link ErrorReportAiAnalysisService} が自クラス内で次の連鎖を持っていた。</p>
 * <pre>
 *   analyzeAfterCommit(...)            // TransactionSynchronization#afterCommit を登録
 *     -&gt; analyzeAsync(...)             // @Async("event-pool") — 自己呼び出しのためプロキシを経ず失効
 *          -&gt; analyzeSync(...)         // @Transactional      — 同じく自己呼び出しで失効
 * </pre>
 * <p>結果として次の2つの実害があった。</p>
 * <ol>
 *   <li>Claude API への呼び出しを含む AI 分析が、{@code afterCommit} コールバックを走らせている
 *       <b>呼び出し元スレッド（多くは HTTP リクエストスレッド）で同期実行</b>されていた。
 *       エラーレポート投稿 API のレスポンスが AI 応答を待つぶん遅延する。</li>
 *   <li>{@code analyzeSync} の {@code @Transactional} も失効するため、即時分析経路では
 *       分析結果の永続化と {@code last_ai_analysis_at} の更新が<b>単一トランザクションに
 *       まとまらず</b>、途中で失敗すると中途半端な状態が残りうる。</li>
 * </ol>
 *
 * <h2>巻き戻りは起きない（ROLLBACK_COUPLED ではない）</h2>
 * <p>登録先が {@code afterCommit} であるため、実行時点で業務トランザクション
 * （{@code ErrorReportService#createOrAggregate}）は<b>既にコミット済み</b>である。
 * したがって AI 分析や通知の失敗で業務処理が巻き戻ることはない。本件の実害は上記の
 * レイテンシとトランザクション境界の欠落であり、L2 で扱った巻き戻り型とは性質が異なる。</p>
 *
 * <h2>L3 の {@code recordBackendException} 監査済み例外とは事情が異なる</h2>
 * <p>L3 では {@code ErrorReportService#recordBackendException} を「業務TXがロールバック済みの後に
 * 走るので AFTER_COMMIT にすると永久に発火しない」として監査済み例外にした。本件の呼び出し元は
 * {@code createOrAggregate}（{@code ErrorReportService} の 95 行目・169 行目の2箇所のみ。
 * {@code recordBackendException} からは呼ばれない）であり、こちらは正常にコミットする業務経路である。
 * よって AFTER_COMMIT 起動は正しく、L3 の例外事由は当てはまらない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorReportAiAnalysisDispatcher {

    private final ErrorReportAiAnalysisAsyncRunner asyncRunner;
    private final ErrorReportAiBudgetService budgetService;
    private final ErrorReportProperties props;

    /**
     * トランザクションコミット後に非同期で AI 分析を実行する。
     * {@code createOrAggregate} からの呼び出し用。
     *
     * <p>トランザクションコンテキストが無い場合（テスト等）は即時に非同期起動する。</p>
     *
     * @param errorReportId エラーレポート ID
     * @param createdBy     操作者ユーザー ID（システム自動なら NULL）
     */
    public void analyzeAfterCommit(Long errorReportId, Long createdBy) {
        // AC-10: 即時分析パスにも予算チェックを適用する。
        // 予算超過時は Claude API を発火させず、警告ログのみ残してスキップする。
        // last_ai_analysis_at は更新しないため、後追いの自動分析バッチが翌期に拾える設計を壊さない。
        if (!props.getAi().isEnabled()) {
            log.debug("AI 即時分析スキップ（機能無効）: errorReportId={}", errorReportId);
            return;
        }
        if (!budgetService.canExpend(ErrorReportAiAnalysisService.CONSERVATIVE_COST_ESTIMATE_JPY)) {
            log.warn("AI 即時分析スキップ（月次予算超過・コストガード）: errorReportId={}, "
                            + "monthlyExpenseJpy={}, budgetJpy={}。後追いバッチが翌期に再評価する。",
                    errorReportId, budgetService.currentMonthlyExpense(),
                    props.getAi().getMonthlyBudgetJpy());
            return;
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // 別 Bean のプロキシ経由で呼ぶ（自己呼び出しでは @Async が失効する）。
                    asyncRunner.analyzeAsync(errorReportId, createdBy);
                }
            });
        } else {
            // トランザクションが無い場合は直接非同期化
            asyncRunner.analyzeAsync(errorReportId, createdBy);
        }
    }
}

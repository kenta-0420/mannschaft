package com.mannschaft.app.errorreport.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * エラーレポート AI 即時分析の非同期実行 Bean（Issue #2990 L4）。
 *
 * <p>{@link ErrorReportAiAnalysisDispatcher} から呼ばれ、{@link ErrorReportAiAnalysisService#analyzeSync}
 * をプロキシ経由で起動する。</p>
 *
 * <h2>なぜ Dispatcher と別 Bean なのか</h2>
 * <p>{@code @Async} も {@code @Transactional} も Spring のプロキシを経た呼び出しでのみ有効になる。
 * 是正前は {@code analyzeAfterCommit → analyzeAsync → analyzeSync} の3段が<b>すべて同一クラス内の
 * 自己呼び出し</b>だったため、{@code @Async} と {@code @Transactional} が<b>いずれも失効</b>していた。
 * 段ごとに Bean を分け、各ホップが必ずプロキシ境界を跨ぐようにしてある:</p>
 * <pre>
 *   ErrorReportAiAnalysisDispatcher#analyzeAfterCommit   （AFTER_COMMIT 登録。非同期でも TX でもない）
 *     -&gt; ErrorReportAiAnalysisAsyncRunner#analyzeAsync   （@Async("event-pool") が効く）
 *          -&gt; ErrorReportAiAnalysisService#analyzeSync   （@Transactional が効く）
 * </pre>
 * <p>1つの Bean に戻すと、その内側の呼び出しは再び自己呼び出しとなり同じ欠陥が復活する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorReportAiAnalysisAsyncRunner {

    private final ErrorReportAiAnalysisService aiAnalysisService;

    /**
     * 非同期エントリポイント。例外は呼び出し元に波及しない。
     *
     * <p>本メソッドは業務トランザクションのコミット後（{@code afterCommit}）にのみ起動されるため、
     * ここでの失敗が業務処理を巻き戻すことはない。</p>
     *
     * @param errorReportId エラーレポート ID
     * @param createdBy     操作者ユーザー ID（システム自動なら NULL）
     */
    @Async("event-pool")
    public void analyzeAsync(Long errorReportId, Long createdBy) {
        try {
            aiAnalysisService.analyzeSync(errorReportId, createdBy);
        } catch (Exception e) {
            log.error("AI 分析失敗: errorReportId={}", errorReportId, e);
        }
    }
}

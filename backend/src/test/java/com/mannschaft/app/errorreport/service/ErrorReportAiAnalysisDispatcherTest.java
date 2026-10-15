package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ErrorReportAiAnalysisDispatcher} 単体テスト（Issue #2990 L4）。
 *
 * <p>AC-10（即時分析パスの予算チェック）の検体は是正前 {@code ErrorReportAiAnalysisServiceTest} に
 * あったが、{@code analyzeAfterCommit} が Dispatcher へ移ったため本クラスへ移設した。</p>
 *
 * <h2>移設にあたって変えた検証内容と、その理由</h2>
 * <p>是正前の {@code analyzeAfterCommit_invokesWhenWithinBudget} は
 * 「{@code analyzeAfterCommit} を呼ぶと {@code provider.analyze} が呼ばれる」ことを検証していた。
 * これは <b>AI 分析が同期実行されることを前提にした表明</b>であり、是正で直した欠陥そのものを
 * 期待挙動として固定してしまっていた。もっとも素の Mockito 単体テストでは Spring プロキシが
 * 介在しないため {@code @Async} は元より効かず、<b>この検体は是正前後どちらでも欠陥を検出できない</b>
 * （モックが実行経路を消していた例）。そのため本クラスでは
 * 「非同期 Runner へ委譲したか」を検証対象に置き換える。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportAiAnalysisDispatcher 単体テスト")
class ErrorReportAiAnalysisDispatcherTest {

    @Mock
    private ErrorReportAiAnalysisAsyncRunner asyncRunner;
    @Mock
    private ErrorReportAiBudgetService budgetService;

    private ErrorReportProperties props;
    private ErrorReportAiAnalysisDispatcher dispatcher;

    private static final Long REPORT_ID = 100L;

    @BeforeEach
    void setUp() {
        props = new ErrorReportProperties();
        props.getAi().setEnabled(true);
        props.getAi().setModel("claude-haiku-4-5");
        dispatcher = new ErrorReportAiAnalysisDispatcher(asyncRunner, budgetService, props);
    }

    @Test
    @DisplayName("AC-10: 予算超過時は非同期分析を起動しない")
    void analyzeAfterCommit_skipsWhenBudgetExceeded() {
        given(budgetService.canExpend(anyInt())).willReturn(false);

        dispatcher.analyzeAfterCommit(REPORT_ID, null);

        verify(asyncRunner, never()).analyzeAsync(any(), any());
        // 後追いバッチが翌期に拾えるよう実コスト計上もされない
        verify(budgetService, never()).recordExpense(anyInt());
    }

    @Test
    @DisplayName("AC-10: 予算内なら非同期 Runner へ委譲する（トランザクション同期が無い場合は即時起動）")
    void analyzeAfterCommit_delegatesWhenWithinBudget() {
        given(budgetService.canExpend(anyInt())).willReturn(true);

        dispatcher.analyzeAfterCommit(REPORT_ID, null);

        // 自己呼び出しではなく別 Bean（プロキシ経由で @Async が効く）へ委譲していること
        verify(asyncRunner).analyzeAsync(REPORT_ID, null);
    }

    @Test
    @DisplayName("AC-10: 機能無効時は非同期分析を起動しない")
    void analyzeAfterCommit_skipsWhenDisabled() {
        props.getAi().setEnabled(false);

        dispatcher.analyzeAfterCommit(REPORT_ID, null);

        verify(asyncRunner, never()).analyzeAsync(any(), any());
    }
}

package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2-G — {@link ErrorReportAiBudgetMonitorBatch} の単体テスト。
 *
 * <p>P2-C で実装した予算到達アラート（80% / 100%）と Valkey フラグによる
 * 月内重複通知抑止が正しく動作することを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportAiBudgetMonitorBatch 単体テスト")
class ErrorReportAiBudgetMonitorBatchTest {

    @Mock
    private ErrorReportAiBudgetService budgetService;
    @Mock
    private ErrorReportNotifier notifier;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    @SuppressWarnings("rawtypes")
    private HashOperations hashOperations;

    private final ErrorReportProperties props = new ErrorReportProperties();

    private ErrorReportAiBudgetMonitorBatch batch;

    @BeforeEach
    void setUp() {
        // monthlyBudgetJpy = 5000 デフォルト
        props.getAi().setEnabled(true);
        props.getAi().setMonthlyBudgetJpy(5000);

        batch = new ErrorReportAiBudgetMonitorBatch(budgetService, notifier, props, redisTemplate);

        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    @DisplayName("AI 無効時はバッチ内で何もしない")
    void execute_skipsWhenAiDisabled() {
        props.getAi().setEnabled(false);

        batch.execute();

        verify(budgetService, never()).currentMonthlyExpense();
        verify(notifier, never()).notifyBudgetWarning(anyInt(), anyLong());
        verify(notifier, never()).notifyBudgetExceeded(anyInt(), anyLong());
    }

    @Test
    @DisplayName("予算 0 以下の場合は通知しない")
    void execute_skipsWhenBudgetIsZero() {
        props.getAi().setMonthlyBudgetJpy(0);
        given(budgetService.currentMonthlyExpense()).willReturn(100L);

        batch.execute();

        verify(notifier, never()).notifyBudgetWarning(anyInt(), anyLong());
        verify(notifier, never()).notifyBudgetExceeded(anyInt(), anyLong());
    }

    @Test
    @DisplayName("80% 未満の場合は通知しない")
    void execute_doesNotNotifyBelowThreshold() {
        // 5000 * 0.8 = 4000 未満
        given(budgetService.currentMonthlyExpense()).willReturn(3000L);

        batch.execute();

        verify(notifier, never()).notifyBudgetWarning(anyInt(), anyLong());
        verify(notifier, never()).notifyBudgetExceeded(anyInt(), anyLong());
    }

    @Test
    @DisplayName("80% 到達かつ未通知の場合は notifyBudgetWarning が呼ばれフラグがセットされる")
    void execute_notifiesAt80Percent() {
        given(budgetService.currentMonthlyExpense()).willReturn(4000L);
        given(hashOperations.get(anyString(), eq("alert80"))).willReturn(null);

        batch.execute();

        verify(notifier).notifyBudgetWarning(5000, 4000L);
        verify(hashOperations).put(anyString(), eq("alert80"), eq("true"));
        verify(redisTemplate).expire(anyString(), eq(Duration.ofDays(35)));
        verify(notifier, never()).notifyBudgetExceeded(anyInt(), anyLong());
    }

    @Test
    @DisplayName("80% 通知済みフラグがある場合は通知しない（重複抑止）")
    void execute_doesNotDuplicate80Notification() {
        given(budgetService.currentMonthlyExpense()).willReturn(4500L);
        given(hashOperations.get(anyString(), eq("alert80"))).willReturn("true");

        batch.execute();

        verify(notifier, never()).notifyBudgetWarning(anyInt(), anyLong());
    }

    @Test
    @DisplayName("100% 到達かつ未通知の場合は notifyBudgetExceeded が呼ばれる")
    void execute_notifiesAt100Percent() {
        given(budgetService.currentMonthlyExpense()).willReturn(5000L);
        given(hashOperations.get(anyString(), eq("alert100"))).willReturn(null);

        batch.execute();

        verify(notifier).notifyBudgetExceeded(5000, 5000L);
        verify(hashOperations).put(anyString(), eq("alert100"), eq("true"));
        verify(redisTemplate).expire(anyString(), eq(Duration.ofDays(35)));
        // 100% 分岐優先で 80% 通知は呼ばれない
        verify(notifier, never()).notifyBudgetWarning(anyInt(), anyLong());
    }

    @Test
    @DisplayName("100% 超過時も notifyBudgetExceeded が呼ばれる")
    void execute_notifiesWhenOverBudget() {
        given(budgetService.currentMonthlyExpense()).willReturn(7500L);
        given(hashOperations.get(anyString(), eq("alert100"))).willReturn(null);

        batch.execute();

        verify(notifier).notifyBudgetExceeded(5000, 7500L);
    }

    @Test
    @DisplayName("100% 通知済みフラグがある場合は通知しない（重複抑止）")
    void execute_doesNotDuplicate100Notification() {
        given(budgetService.currentMonthlyExpense()).willReturn(5500L);
        given(hashOperations.get(anyString(), eq("alert100"))).willReturn("true");

        batch.execute();

        verify(notifier, never()).notifyBudgetExceeded(anyInt(), anyLong());
    }
}

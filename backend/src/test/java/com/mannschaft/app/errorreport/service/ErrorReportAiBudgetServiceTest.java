package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2-C — {@link ErrorReportAiBudgetService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportAiBudgetService 単体テスト")
class ErrorReportAiBudgetServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private final ErrorReportProperties props = new ErrorReportProperties();

    private ErrorReportAiBudgetService service;

    @BeforeEach
    void setUp() {
        // monthlyBudgetJpy = 5000（デフォルト）
        service = new ErrorReportAiBudgetService(redisTemplate, props);
    }

    @Test
    @DisplayName("canExpend: 当月支出 + 予定 <= 予算なら true")
    void canExpend_returnsTrueWhenWithinBudget() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn("4000");

        assertThat(service.canExpend(500)).isTrue();
        assertThat(service.canExpend(1000)).isTrue();
    }

    @Test
    @DisplayName("canExpend: 加算で予算を超えると false")
    void canExpend_returnsFalseWhenExceeded() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn("4900");

        assertThat(service.canExpend(200)).isFalse();
    }

    @Test
    @DisplayName("recordExpense: 月初の初回 INCR で TTL 35日が設定される")
    void recordExpense_setsTtlOnFirstIncrement() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.increment(anyString(), eq(10L))).willReturn(10L);

        service.recordExpense(10);

        verify(valueOps).increment(anyString(), eq(10L));
        verify(redisTemplate).expire(anyString(), eq(Duration.ofDays(35)));
    }

    @Test
    @DisplayName("recordExpense: 2 回目以降は TTL を再設定しない")
    void recordExpense_skipsTtlOnSubsequentIncrement() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.increment(anyString(), eq(5L))).willReturn(15L);

        service.recordExpense(5);

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("recordExpense: 0 円以下はスキップ")
    void recordExpense_ignoresZeroOrNegative() {
        service.recordExpense(0);
        service.recordExpense(-1);
        verify(valueOps, never()).increment(anyString(), any(Long.class));
    }

    @Test
    @DisplayName("currentMonthlyExpense: NULL は 0L として扱う")
    void currentMonthlyExpense_handlesNull() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn(null);

        assertThat(service.currentMonthlyExpense()).isZero();
    }

    @Test
    @DisplayName("currentMonthlyExpense: 数値文字列をパースする")
    void currentMonthlyExpense_parsesValue() {
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get(anyString())).willReturn("1234");

        assertThat(service.currentMonthlyExpense()).isEqualTo(1234L);
    }
}

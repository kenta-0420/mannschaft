package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F12.5 Phase 2-F — {@link ErrorReportAiHealthMonitor} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportAiHealthMonitor 単体テスト")
class ErrorReportAiHealthMonitorTest {

    @Mock
    private ErrorReportAiAnalysisRepository aiAnalysisRepository;
    @Mock
    private ErrorReportNotifier notifier;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private ErrorReportAiHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new ErrorReportAiHealthMonitor(aiAnalysisRepository, notifier, redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("正常系: 失敗5件で初回通知が送信される")
    void 正常_閾値到達_初回通知() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 12, 0);
        given(aiAnalysisRepository.countByStatusAndCreatedAtAfter(eq("FAILED"), any()))
                .willReturn(5L);
        given(valueOperations.setIfAbsent(anyString(), eq("true"), eq(Duration.ofDays(1))))
                .willReturn(Boolean.TRUE);

        monitor.executeAt(now);

        verify(notifier).notifyAiHealthDegraded(5L);
    }

    @Test
    @DisplayName("正常系: 失敗4件以下では通知されない")
    void 正常_閾値未満_通知なし() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 12, 0);
        given(aiAnalysisRepository.countByStatusAndCreatedAtAfter(eq("FAILED"), any()))
                .willReturn(4L);

        monitor.executeAt(now);

        verify(notifier, never()).notifyAiHealthDegraded(any(Long.class));
        verify(valueOperations, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("正常系: 同日2回目は通知されない（setIfAbsent が false を返す）")
    void 正常_同日2回目_通知なし() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 6, 12, 0);
        given(aiAnalysisRepository.countByStatusAndCreatedAtAfter(eq("FAILED"), any()))
                .willReturn(10L);
        given(valueOperations.setIfAbsent(anyString(), eq("true"), eq(Duration.ofDays(1))))
                .willReturn(Boolean.FALSE);

        monitor.executeAt(now);

        verify(notifier, never()).notifyAiHealthDegraded(any(Long.class));
    }
}

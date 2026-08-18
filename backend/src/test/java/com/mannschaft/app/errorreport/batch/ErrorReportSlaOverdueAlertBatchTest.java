package com.mannschaft.app.errorreport.batch;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2715 CMP-055 ロットC-1 — {@link ErrorReportSlaOverdueAlertBatch} の単体テスト。
 * SLA超過通知の件名が受信者 locale に応じて組み立てられること・
 * 管理者一括通知で locale 解決が N+1 にならないことを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportSlaOverdueAlertBatch 単体テスト")
class ErrorReportSlaOverdueAlertBatchTest {

    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private UserLocaleCache userLocaleCache;

    @InjectMocks
    private ErrorReportSlaOverdueAlertBatch batch;

    private void useRealMessageSource() {
        ResourceBundleMessageSource realMessageSource = new ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        ReflectionTestUtils.setField(batch, "messageSource", realMessageSource);
    }

    private static ErrorReportEntity overdueReport(Long id, ErrorReportSeverity severity, Long assigneeId) {
        ErrorReportEntity report = ErrorReportEntity.builder()
                .errorMessage("TypeError: Cannot read property of null")
                .pageUrl("/page")
                .occurredAt(LocalDateTime.now())
                .status(ErrorReportStatus.NEW)
                .severity(severity)
                .errorHash("h")
                .occurrenceCount(3)
                .affectedUserCount(2)
                .firstOccurredAt(LocalDateTime.now())
                .lastOccurredAt(LocalDateTime.now())
                .assigneeId(assigneeId)
                .build();
        ReflectionTestUtils.setField(report, "id", id);
        return report;
    }

    @Test
    @DisplayName("担当者ありの場合: 担当者 locale が en なら件名が英語で組み立てられる")
    void assignee_en_localizesTitle() {
        useRealMessageSource();
        given(redisTemplate.hasKey(any())).willReturn(false);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of());
        given(userLocaleCache.getLocales(List.of())).willReturn(Map.of());
        given(userLocaleCache.getLocale(99L)).willReturn("en");
        given(errorReportRepository.findOverdueReports(any(), any()))
                .willReturn(List.of(overdueReport(100L, ErrorReportSeverity.CRITICAL, 99L)));

        batch.run();

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notify(
                eq(99L), eq("ERROR_REPORT_SLA_OVERDUE"), eq(NotificationPriority.HIGH),
                titleCaptor.capture(), any(),
                eq("ERROR_REPORT"), eq(100L),
                eq(NotificationScopeType.SYSTEM), eq(null),
                any(), eq(null));
        assertThat(titleCaptor.getValue()).isEqualTo("[SLA overdue] CRITICAL error is past its deadline");
    }

    @Test
    @DisplayName("担当者なしの場合: 管理者ごとの locale で件名が組み立てられ、locale 解決は getLocales 1回のみ（N+1防止）")
    void noAssignee_localizesPerAdmin_resolvesLocalesInBulk() {
        useRealMessageSource();
        given(redisTemplate.hasKey(any())).willReturn(false);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(1L, 2L));
        given(userLocaleCache.getLocales(List.of(1L, 2L))).willReturn(Map.of(1L, "en", 2L, "ja"));
        given(errorReportRepository.findOverdueReports(any(), any()))
                .willReturn(List.of(overdueReport(200L, ErrorReportSeverity.HIGH, null)));

        batch.run();

        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper, times(2)).notify(
                userIdCaptor.capture(), eq("ERROR_REPORT_SLA_OVERDUE"), eq(NotificationPriority.HIGH),
                titleCaptor.capture(), any(),
                eq("ERROR_REPORT"), eq(200L),
                eq(NotificationScopeType.SYSTEM), eq(null),
                any(), eq(null));

        int enIdx = userIdCaptor.getAllValues().indexOf(1L);
        int jaIdx = userIdCaptor.getAllValues().indexOf(2L);
        assertThat(titleCaptor.getAllValues().get(enIdx)).isEqualTo("[SLA overdue] HIGH error is past its deadline");
        assertThat(titleCaptor.getAllValues().get(jaIdx)).isEqualTo("[SLA超過] HIGH エラーが期限切れです");

        // AC-3: N+1 防止 — バルク解決 (getLocales) は 1 回、単体解決 (getLocale) は 0 回（担当者なしのため）。
        verify(userLocaleCache, times(1)).getLocales(any());
        verify(userLocaleCache, never()).getLocale(anyLong());
    }
}

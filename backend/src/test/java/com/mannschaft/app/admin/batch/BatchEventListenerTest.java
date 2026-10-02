package com.mannschaft.app.admin.batch;

import com.mannschaft.app.admin.batch.event.BatchCompletedEvent;
import com.mannschaft.app.admin.batch.event.BatchFailedEvent;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportAsyncExecutor;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link BatchEventListener} の単体テスト。
 *
 * <p>完了イベント / 失敗イベントを受けて、SYSTEM_ADMIN 通知配信および F12.5 起票が
 * 期待通り呼ばれることを Mockito で検証する。</p>
 */
@DisplayName("BatchEventListener 単体テスト")
class BatchEventListenerTest {

    private NotificationService notificationService;
    private UserRoleRepository userRoleRepository;
    private ErrorReportAsyncExecutor errorReportAsyncExecutor;
    private BatchEventListener listener;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        userRoleRepository = mock(UserRoleRepository.class);
        errorReportAsyncExecutor = mock(ErrorReportAsyncExecutor.class);
        // MessageSource は実物を使う（モックが引数をそのまま返す形だと鍵の欠落もフォーマット崩れも検出できないため）。
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("messages");
        messageSource.setDefaultEncoding("UTF-8");
        UserLocaleCache userLocaleCache = mock(UserLocaleCache.class);
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        listener = new BatchEventListener(
                notificationService, userRoleRepository, errorReportAsyncExecutor, messageSource, userLocaleCache);
    }

    @Test
    @DisplayName("onCompleted で SYSTEM_ADMIN 全員に LOW 通知が送られる")
    void shouldNotifySystemAdminsOnCompleted() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(10L, 20L));

        BatchJobLogEntity log = BatchJobLogEntity.builder().build();
        // id / processedCount を内部メソッド経由で擬似的にセット
        log.complete(7);
        BatchCompletedEvent event = new BatchCompletedEvent("sample-foo", log, Instant.now());

        listener.onCompleted(event);

        verify(notificationService, times(2)).createNotification(
                any(Long.class),
                eq(BatchEventListener.NOTIFICATION_TYPE_BATCH_COMPLETED),
                eq(NotificationPriority.LOW),
                contains("sample-foo"),
                contains("7"),
                eq("BATCH_JOB_LOG"),
                any(),
                eq(NotificationScopeType.SYSTEM),
                isNull(),
                any(String.class),
                isNull());
        verifyNoInteractions(errorReportAsyncExecutor);
    }

    @Test
    @DisplayName("onFailed で F12.5 起票と HIGH 通知が走る")
    void shouldRecordErrorAndNotifyOnFailed() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(10L));

        BatchJobLogEntity log = BatchJobLogEntity.builder().build();
        RuntimeException cause = new RuntimeException("kaboom!");
        BatchFailedEvent event = new BatchFailedEvent("sample-foo", log, cause, Instant.now());

        listener.onFailed(event);

        verify(errorReportAsyncExecutor).recordBackendException(
                eq(cause),
                eq("batch://sample-foo"),
                eq("system-batch"),
                isNull(),
                isNull(),
                eq(ErrorReportSeverity.HIGH));

        verify(notificationService).createNotification(
                eq(10L),
                eq(BatchEventListener.NOTIFICATION_TYPE_BATCH_FAILED),
                eq(NotificationPriority.HIGH),
                contains("sample-foo"),
                contains("kaboom"),
                eq("BATCH_JOB_LOG"),
                any(),
                eq(NotificationScopeType.SYSTEM),
                isNull(),
                any(String.class),
                isNull());
    }

    @Test
    @DisplayName("SYSTEM_ADMIN が 0 名でも例外を出さない")
    void shouldNotThrowWhenNoAdmins() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of());
        BatchJobLogEntity log = BatchJobLogEntity.builder().build();
        BatchCompletedEvent event = new BatchCompletedEvent("sample-foo", log, Instant.now());
        listener.onCompleted(event);
        // 通知サービスは呼ばれない
        verify(notificationService, times(0)).createNotification(
                any(Long.class), any(String.class), any(NotificationPriority.class),
                any(String.class), any(String.class), any(String.class), any(),
                any(NotificationScopeType.class), any(), any(String.class), any());
    }
}

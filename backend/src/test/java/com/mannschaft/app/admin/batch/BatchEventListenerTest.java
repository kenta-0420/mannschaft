package com.mannschaft.app.admin.batch;

import com.mannschaft.app.admin.batch.event.BatchCompletedEvent;
import com.mannschaft.app.admin.batch.event.BatchFailedEvent;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportAsyncExecutor;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.context.event.EventListener;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link BatchEventListener} の単体テスト。
 *
 * <p>完了イベント / 失敗イベントを受けて、SYSTEM_ADMIN 通知配信（{@link NotificationDeliveryRunner#sendOne}）および
 * F12.5 起票が期待通り呼ばれることを Mockito で検証する。実DB・実TXでの切り離しは
 * {@code BatchNotificationTransactionIT}（Issue #2997 G7-b）が担う。</p>
 */
@DisplayName("BatchEventListener 単体テスト")
class BatchEventListenerTest {

    private NotificationDeliveryRunner notificationDeliveryRunner;
    private UserRoleRepository userRoleRepository;
    private ErrorReportAsyncExecutor errorReportAsyncExecutor;
    private BatchEventListener listener;

    @BeforeEach
    void setUp() {
        notificationDeliveryRunner = mock(NotificationDeliveryRunner.class);
        userRoleRepository = mock(UserRoleRepository.class);
        errorReportAsyncExecutor = mock(ErrorReportAsyncExecutor.class);
        // MessageSource は実物を使う（モックが引数をそのまま返す形だと鍵の欠落もフォーマット崩れも検出できないため）。
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasenames("messages");
        messageSource.setDefaultEncoding("UTF-8");
        UserLocaleCache userLocaleCache = mock(UserLocaleCache.class);
        given(userLocaleCache.getLocales(any())).willReturn(Map.of());
        listener = new BatchEventListener(
                notificationDeliveryRunner, userRoleRepository, errorReportAsyncExecutor, messageSource, userLocaleCache);
    }

    @Test
    @DisplayName("onCompleted で SYSTEM_ADMIN 全員に LOW 通知が sendOne で送られる")
    void shouldNotifySystemAdminsOnCompleted() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(10L, 20L));

        BatchJobLogEntity log = BatchJobLogEntity.builder().build();
        log.complete(7);
        BatchCompletedEvent event = new BatchCompletedEvent("sample-foo", log, Instant.now());

        listener.onCompleted(event);

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(2)).sendOne(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationDeliveryRequest::recipientUserId)
                .containsExactly(10L, 20L);
        NotificationDeliveryRequest first = captor.getAllValues().get(0);
        assertThat(first.notificationType()).isEqualTo(BatchEventListener.NOTIFICATION_TYPE_BATCH_COMPLETED);
        assertThat(first.priority()).isEqualTo(NotificationPriority.LOW);
        assertThat(first.title()).contains("sample-foo");
        assertThat(first.body()).contains("7");
        assertThat(first.sourceType()).isEqualTo("BATCH_JOB_LOG");
        assertThat(first.scopeType()).isEqualTo(NotificationScopeType.SYSTEM);
        assertThat(first.scopeId()).isNull();
        assertThat(first.actionUrl()).startsWith("/system-admin/batch-jobs");
        assertThat(first.actorId()).isNull();
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
                org.mockito.ArgumentMatchers.eq(cause),
                org.mockito.ArgumentMatchers.eq("batch://sample-foo"),
                org.mockito.ArgumentMatchers.eq("system-batch"),
                isNull(),
                isNull(),
                org.mockito.ArgumentMatchers.eq(ErrorReportSeverity.HIGH));

        NotificationDeliveryRequest request = sentRequests(1).get(0);
        assertThat(request.recipientUserId()).isEqualTo(10L);
        assertThat(request.notificationType()).isEqualTo(BatchEventListener.NOTIFICATION_TYPE_BATCH_FAILED);
        assertThat(request.priority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(request.title()).contains("sample-foo");
        assertThat(request.body()).contains("kaboom");
    }

    @Test
    @DisplayName("SYSTEM_ADMIN が 0 名でも例外を出さず、通知は0件")
    void shouldNotThrowWhenNoAdmins() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of());
        BatchJobLogEntity log = BatchJobLogEntity.builder().build();

        listener.onCompleted(new BatchCompletedEvent("sample-foo", log, Instant.now()));
        listener.onFailed(new BatchFailedEvent("sample-foo", log, new RuntimeException("x"), Instant.now()));

        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("管理者1人への配送が失敗しても、残りの管理者への配送を続ける（完了・失敗とも）")
    void shouldIsolateFailurePerAdmin() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(10L, 20L, 30L));
        willThrow(new IllegalStateException("配送失敗（検証用）"))
                .given(notificationDeliveryRunner).sendOne(
                        org.mockito.ArgumentMatchers.argThat(r -> r != null && Long.valueOf(10L).equals(r.recipientUserId())));
        BatchJobLogEntity log = BatchJobLogEntity.builder().build();

        listener.onCompleted(new BatchCompletedEvent("sample-foo", log, Instant.now()));
        listener.onFailed(new BatchFailedEvent("sample-foo", log, new RuntimeException("x"), Instant.now()));

        // 失敗対象(10)への到達も含めて、完了・失敗それぞれ 3 人分すべてに試行している。
        verify(notificationDeliveryRunner, times(6)).sendOne(any());
    }

    @Test
    @DisplayName("原因が null の失敗は「(no cause)」を本文にする")
    void shouldUseNoCauseWhenCauseIsNull() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(10L));

        listener.onFailed(new BatchFailedEvent("sample-foo", BatchJobLogEntity.builder().build(), null, Instant.now()));

        assertThat(sentRequests(1).get(0).body()).contains("(no cause)");
    }

    @Test
    @DisplayName("原因のメッセージが null なら例外クラス名を本文にする")
    void shouldUseClassNameWhenMessageIsNull() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(10L));

        listener.onFailed(new BatchFailedEvent("sample-foo", BatchJobLogEntity.builder().build(),
                new IllegalStateException(), Instant.now()));

        assertThat(sentRequests(1).get(0).body()).contains("IllegalStateException");
    }

    @Test
    @DisplayName("原因メッセージは200字ちょうどまでそのまま、201字は200字に切り詰める")
    void shouldTruncateCauseAtBoundary() {
        given(userRoleRepository.findSystemAdminUserIds()).willReturn(List.of(10L));
        String exactly200 = "a".repeat(200);
        String over201 = "b".repeat(200) + "c";

        listener.onFailed(new BatchFailedEvent("n", BatchJobLogEntity.builder().build(),
                new RuntimeException(exactly200), Instant.now()));
        listener.onFailed(new BatchFailedEvent("n", BatchJobLogEntity.builder().build(),
                new RuntimeException(over201), Instant.now()));

        List<NotificationDeliveryRequest> sent = sentRequests(2);
        assertThat(sent.get(0).body()).contains(exactly200);
        assertThat(sent.get(1).body()).contains("b".repeat(200)).doesNotContain("c");
    }

    @Test
    @DisplayName("TX 境界の宣言: 素の @EventListener ではなく @Async(event-pool) + AFTER_COMMIT(fallbackExecution=true)")
    void shouldDeclareAfterCommitBoundary() throws NoSuchMethodException {
        for (Method m : List.of(
                BatchEventListener.class.getMethod("onCompleted", BatchCompletedEvent.class),
                BatchEventListener.class.getMethod("onFailed", BatchFailedEvent.class))) {
            assertThat(m.getAnnotation(EventListener.class)).as(m.getName() + " は素の @EventListener にしない").isNull();
            TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
            assertThat(tel).as(m.getName()).isNotNull();
            assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
            assertThat(tel.fallbackExecution()).isTrue();
            Async async = m.getAnnotation(Async.class);
            assertThat(async).as(m.getName()).isNotNull();
            assertThat(async.value()).isEqualTo("event-pool");
        }
    }

    private List<NotificationDeliveryRequest> sentRequests(int expected) {
        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner, times(expected)).sendOne(captor.capture());
        return captor.getAllValues();
    }
}

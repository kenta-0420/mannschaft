package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.event.ErrorReportAssignedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRaisedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRegressionDetectedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportResolvedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportSeverityEscalatedEvent;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issue #2990 L11 — {@link ErrorReportNotificationListener} の単体テスト。
 *
 * <p>攻め口: 空・0件・null（reportId が null / 対象行が消えている / assigneeId が null）、
 * 境界値（{@code slackEnabled} の真偽）、途中失敗（配送が例外を投げても呼び出し元へ伝播しない）。
 * 受信者ごとの被害半径は {@code ErrorReportNotifierTest.PerRecipientIsolation} が固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ErrorReportNotificationListener 単体テスト（#2990 L11）")
class ErrorReportNotificationListenerTest {

    private static final Long REPORT_ID = 4242L;

    @Mock
    private ErrorReportRepository errorReportRepository;
    @Mock
    private ErrorReportNotifier errorReportNotifier;

    @InjectMocks
    private ErrorReportNotificationListener listener;

    private static ErrorReportEntity report() {
        LocalDateTime now = LocalDateTime.now();
        return ErrorReportEntity.builder()
                .id(REPORT_ID)
                .errorMessage("boom")
                .pageUrl("/page")
                .occurredAt(now)
                .status(ErrorReportStatus.NEW)
                .severity(ErrorReportSeverity.CRITICAL)
                .errorHash("h")
                .occurrenceCount(1)
                .affectedUserCount(0)
                .firstOccurredAt(now)
                .lastOccurredAt(now)
                .build();
    }

    @Nested
    @DisplayName("新規記録イベント")
    class Raised {

        @Test
        @DisplayName("slackEnabled=true: Slack と SYSTEM_ADMIN の両方へ配送する")
        void slackEnabled_deliversBoth() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));

            listener.onErrorReportRaised(new ErrorReportRaisedEvent(REPORT_ID, true));

            verify(errorReportNotifier).notifySlack(any(ErrorReportEntity.class));
            verify(errorReportNotifier).notifySystemAdmins(any(ErrorReportEntity.class));
        }

        @Test
        @DisplayName("slackEnabled=false: Slack は抑制され SYSTEM_ADMIN だけへ配送する")
        void slackSuppressed_deliversSystemAdminsOnly() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));

            listener.onErrorReportRaised(new ErrorReportRaisedEvent(REPORT_ID, false));

            verify(errorReportNotifier, never()).notifySlack(any());
            verify(errorReportNotifier).notifySystemAdmins(any(ErrorReportEntity.class));
        }

        @Test
        @DisplayName("reportId が null: 読み直しにも配送にも進まない")
        void nullReportId_doesNothing() {
            listener.onErrorReportRaised(new ErrorReportRaisedEvent(null, true));

            verifyNoInteractions(errorReportRepository);
            verifyNoInteractions(errorReportNotifier);
        }

        @Test
        @DisplayName("コミット後に対象行が消えている: 配送を中止し例外は投げない")
        void missingReport_abortsQuietly() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

            assertThatCode(() -> listener.onErrorReportRaised(
                    new ErrorReportRaisedEvent(REPORT_ID, true))).doesNotThrowAnyException();

            verifyNoInteractions(errorReportNotifier);
        }

        @Test
        @DisplayName("途中失敗: 配送が例外を投げてもリスナー外へ伝播しない")
        void deliveryFailure_isNotPropagated() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));
            willThrow(new RuntimeException("Slack down"))
                    .given(errorReportNotifier).notifySlack(any(ErrorReportEntity.class));

            assertThatCode(() -> listener.onErrorReportRaised(
                    new ErrorReportRaisedEvent(REPORT_ID, true))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("被害半径: Slack が落ちても SYSTEM_ADMIN プッシュは配送される")
        void slackFailure_doesNotBlockSystemAdminPush() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));
            willThrow(new RuntimeException("Slack down"))
                    .given(errorReportNotifier).notifySlack(any(ErrorReportEntity.class));

            assertThatCode(() -> listener.onErrorReportRaised(
                    new ErrorReportRaisedEvent(REPORT_ID, true))).doesNotThrowAnyException();

            verify(errorReportNotifier).notifySystemAdmins(any(ErrorReportEntity.class));
        }
    }

    @Nested
    @DisplayName("重要度昇格イベント")
    class Escalated {

        @Test
        @DisplayName("昇格前後の severity をそのまま配送へ渡す")
        void deliversWithBothSeverities() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));

            listener.onSeverityEscalated(new ErrorReportSeverityEscalatedEvent(
                    REPORT_ID, ErrorReportSeverity.HIGH, ErrorReportSeverity.CRITICAL));

            verify(errorReportNotifier).notifyEscalation(any(ErrorReportEntity.class),
                    eq(ErrorReportSeverity.HIGH), eq(ErrorReportSeverity.CRITICAL));
        }

        @Test
        @DisplayName("対象行が消えていれば配送しない")
        void missingReport_abortsQuietly() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

            listener.onSeverityEscalated(new ErrorReportSeverityEscalatedEvent(
                    REPORT_ID, ErrorReportSeverity.HIGH, ErrorReportSeverity.CRITICAL));

            verifyNoInteractions(errorReportNotifier);
        }
    }

    @Nested
    @DisplayName("リグレッションイベント")
    class Regression {

        @Test
        @DisplayName("読み直した行で再発通知を配送する")
        void delivers() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));

            listener.onRegressionDetected(new ErrorReportRegressionDetectedEvent(REPORT_ID));

            verify(errorReportNotifier).notifyRegression(any(ErrorReportEntity.class));
        }

        @Test
        @DisplayName("途中失敗: 配送が例外を投げてもリスナー外へ伝播しない")
        void deliveryFailure_isNotPropagated() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));
            willThrow(new RuntimeException("down"))
                    .given(errorReportNotifier).notifyRegression(any(ErrorReportEntity.class));

            assertThatCode(() -> listener.onRegressionDetected(
                    new ErrorReportRegressionDetectedEvent(REPORT_ID))).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("解決イベント")
    class Resolved {

        @Test
        @DisplayName("読み直した行で報告者通知を配送する")
        void delivers() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));

            listener.onErrorReportResolved(new ErrorReportResolvedEvent(REPORT_ID));

            verify(errorReportNotifier).notifyResolution(any(ErrorReportEntity.class));
        }

        @Test
        @DisplayName("対象行が消えていれば配送しない")
        void missingReport_abortsQuietly() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.empty());

            listener.onErrorReportResolved(new ErrorReportResolvedEvent(REPORT_ID));

            verifyNoInteractions(errorReportNotifier);
        }
    }

    @Nested
    @DisplayName("担当者割り当てイベント")
    class Assigned {

        @Test
        @DisplayName("担当者へ通知を配送する")
        void delivers() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));

            listener.onErrorReportAssigned(new ErrorReportAssignedEvent(REPORT_ID, 99L));

            verify(errorReportNotifier).notifyAssignment(any(ErrorReportEntity.class), eq(99L));
        }

        @Test
        @DisplayName("assigneeId が null（担当解除）なら読み直しも配送もしない")
        void nullAssignee_doesNothing() {
            listener.onErrorReportAssigned(new ErrorReportAssignedEvent(REPORT_ID, null));

            verifyNoInteractions(errorReportRepository);
            verifyNoInteractions(errorReportNotifier);
        }

        @Test
        @DisplayName("途中失敗: 配送が例外を投げてもリスナー外へ伝播しない")
        void deliveryFailure_isNotPropagated() {
            given(errorReportRepository.findById(REPORT_ID)).willReturn(Optional.of(report()));
            willThrow(new RuntimeException("down"))
                    .given(errorReportNotifier).notifyAssignment(any(ErrorReportEntity.class), anyLong());

            assertThatCode(() -> listener.onErrorReportAssigned(
                    new ErrorReportAssignedEvent(REPORT_ID, 99L))).doesNotThrowAnyException();
        }
    }
}

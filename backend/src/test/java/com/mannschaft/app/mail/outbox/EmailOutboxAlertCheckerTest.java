package com.mannschaft.app.mail.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F09.18 Phase 18-e: {@link EmailOutboxAlertChecker} 単体テスト。
 *
 * <p>閾値ロジックの境界値を検証する。ログ出力は {@code assertThatCode} で例外が発生しないことを確認する
 * (SLF4J の log.warn/error が正常に呼び出せることの確認)。</p>
 */
@DisplayName("EmailOutboxAlertChecker 単体テスト")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailOutboxAlertCheckerTest {

    @Mock
    private EmailOutboxRepository repository;

    private EmailOutboxAlertChecker checker;

    @BeforeEach
    void setUp() {
        checker = new EmailOutboxAlertChecker(repository);

        // デフォルト: 全ゼロ（各テストで必要に応じてオーバーライド）
        when(repository.countByStatus(anyString())).thenReturn(0L);
        when(repository.findFirstByStatusOrderByCreatedAtAsc(anyString())).thenReturn(Optional.empty());
        when(repository.countByStatusSince(anyString(), any(LocalDateTime.class))).thenReturn(0L);
    }

    // -----------------------------------------------------------------------
    // checkQueueDepthPending
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pending=1001 のとき checkQueueDepthPending() が例外なく実行される (WARN ログ出力)")
    void checkAlerts_pendingExceedsWarn_logsWarn() {
        when(repository.countByStatus(EmailOutboxStatus.PENDING.name()))
                .thenReturn(EmailOutboxAlertChecker.WARN_QUEUE_DEPTH_PENDING + 1);

        // log.warn が呼ばれても例外が起きないことを確認
        assertThatCode(() -> checker.checkQueueDepthPending()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("pending=1000 (閾値ちょうど) のとき checkQueueDepthPending() は WARN しない")
    void checkAlerts_pendingAtThreshold_doesNotWarn() {
        when(repository.countByStatus(EmailOutboxStatus.PENDING.name()))
                .thenReturn(EmailOutboxAlertChecker.WARN_QUEUE_DEPTH_PENDING);

        // 閾値ちょうどは警告しない (> であるため)
        assertThatCode(() -> checker.checkQueueDepthPending()).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // checkSuccessRate
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("sent=90, dead=10 (rate=0.9) のとき checkSuccessRate() が例外なく実行される (CRITICAL ログ出力)")
    void checkAlerts_successRateCritical_logsError() {
        when(repository.countByStatusSince(
                eq(EmailOutboxStatus.SENT.name()),
                any(LocalDateTime.class))).thenReturn(90L);
        when(repository.countByStatusSince(
                eq(EmailOutboxStatus.DEAD_LETTER.name()),
                any(LocalDateTime.class))).thenReturn(10L);

        // rate = 0.9 < CRITICAL_SUCCESS_RATE(0.95) → log.error が呼ばれるが例外は起きない
        assertThatCode(() -> checker.checkSuccessRate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sent=0, dead=0 のとき checkSuccessRate() は何もしない (実績ゼロはチェック対象外)")
    void checkAlerts_noTraffic_skipsCheck() {
        when(repository.countByStatusSince(anyString(), any(LocalDateTime.class))).thenReturn(0L);

        assertThatCode(() -> checker.checkSuccessRate()).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // AC3: checkDeadLetterDepth — 直近24h窓で判定すること
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3(a): 直近24h の DEAD_LETTER=11 件のとき CRITICAL ログを出力する")
    void checkDeadLetterDepth_24hWindow_exceeds_logsCritical() {
        // 直近24h の DEAD_LETTER 件数 = 閾値超え
        when(repository.countByStatusSince(
                eq(EmailOutboxStatus.DEAD_LETTER.name()),
                any(LocalDateTime.class)))
                .thenReturn(EmailOutboxAlertChecker.CRITICAL_QUEUE_DEPTH_DEAD_LETTER + 1);

        assertThatCode(() -> checker.checkDeadLetterDepth()).doesNotThrowAnyException();

        // countByStatusSince が DEAD_LETTER に対して呼ばれていること（24h窓）
        verify(repository).countByStatusSince(
                eq(EmailOutboxStatus.DEAD_LETTER.name()),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("AC3(b): 直近24h の DEAD_LETTER=0 件のとき CRITICAL ログを出力しない (過去の死信は対象外)")
    void checkDeadLetterDepth_24hWindow_zero_doesNotAlert() {
        // 直近24h の DEAD_LETTER 件数 = 0（過去に蓄積した死信は24h窓外の想定）
        when(repository.countByStatusSince(
                eq(EmailOutboxStatus.DEAD_LETTER.name()),
                any(LocalDateTime.class)))
                .thenReturn(0L);

        assertThatCode(() -> checker.checkDeadLetterDepth()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC3: checkDeadLetterDepth は countByStatus（全期間）ではなく countByStatusSince（24h窓）を使う")
    void checkDeadLetterDepth_uses_countByStatusSince_not_countByStatus() {
        when(repository.countByStatusSince(
                eq(EmailOutboxStatus.DEAD_LETTER.name()),
                any(LocalDateTime.class)))
                .thenReturn(0L);

        checker.checkDeadLetterDepth();

        // countByStatusSince が呼ばれていること（24h窓）
        verify(repository).countByStatusSince(
                eq(EmailOutboxStatus.DEAD_LETTER.name()),
                any(LocalDateTime.class));

        // 全期間の countByStatus は DEAD_LETTER に対して呼ばれていないこと
        verify(repository, never()).countByStatus(eq(EmailOutboxStatus.DEAD_LETTER.name()));
    }
}

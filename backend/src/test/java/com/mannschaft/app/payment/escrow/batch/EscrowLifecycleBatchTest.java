package com.mannschaft.app.payment.escrow.batch;

import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowLifecycleService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.willReturn;
import static org.mockito.Mockito.willThrow;

/**
 * F22.1 第三陣: {@link EscrowLifecycleBatch} 単体テスト。
 *
 * <p>test-first。Clock を固定し、抽出クエリの境界引数（猶予基準時刻）/ 1 件失敗で他件継続 / 各状態の
 * 委譲を検証する。実処理は {@link EscrowLifecycleService} モックへ委譲済みのためここではバッチの
 * オーケストレーション（抽出→個別委譲→件数集計→個別 try/catch）のみを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EscrowLifecycleBatch 単体テスト（抽出境界・個別失敗分離）")
class EscrowLifecycleBatchTest {

    @Mock private EscrowTransactionRepository escrowTransactionRepository;
    @Mock private EscrowLifecycleService escrowLifecycleService;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 10, 12, 0, 0);
    private final Clock clock = Clock.fixed(
            NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));

    private EscrowLifecycleBatch batch() {
        return new EscrowLifecycleBatch(escrowTransactionRepository, escrowLifecycleService, clock);
    }

    private EscrowTransactionEntity escrow(UUID id, EscrowStatus status) {
        EscrowTransactionEntity e = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT).sourceId(1L).sourceParticipantId(2L)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(9L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(UUID.randomUUID())
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(status)
                .build();
        e.setId(id);
        return e;
    }

    @Test
    @DisplayName("PENDING_CONFIRMATION 抽出は created_at < now-72h で行い、各件を委譲して件数集計")
    void cancelsPendingWithGraceBoundary() {
        EscrowLifecycleBatch b = batch();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        LocalDateTime expectedBefore = NOW.minusHours(72L);
        given(escrowTransactionRepository.findByStatusAndCreatedAtBefore(
                EscrowStatus.PENDING_CONFIRMATION, expectedBefore))
                .willReturn(List.of(escrow(id1, EscrowStatus.PENDING_CONFIRMATION),
                        escrow(id2, EscrowStatus.PENDING_CONFIRMATION)));
        given(escrowLifecycleService.cancelExpiredPendingConfirmation(id1)).willReturn(true);
        given(escrowLifecycleService.cancelExpiredPendingConfirmation(id2)).willReturn(false);

        int count = b.cancelExpiredPendingConfirmations(NOW);

        assertThat(count).isEqualTo(1);
        verify(escrowLifecycleService).cancelExpiredPendingConfirmation(id1);
        verify(escrowLifecycleService).cancelExpiredPendingConfirmation(id2);
        // 抽出が猶予基準時刻（now-72h）で行われたこと。
        verify(escrowTransactionRepository).findByStatusAndCreatedAtBefore(
                EscrowStatus.PENDING_CONFIRMATION, expectedBefore);
    }

    @Test
    @DisplayName("PENDING 取消で 1 件が例外でも他件は継続する（個別失敗分離）")
    void pendingFailureDoesNotStopOthers() {
        EscrowLifecycleBatch b = batch();
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        given(escrowTransactionRepository.findByStatusAndCreatedAtBefore(
                eq(EscrowStatus.PENDING_CONFIRMATION), eq(NOW.minusHours(72L))))
                .willReturn(List.of(escrow(bad, EscrowStatus.PENDING_CONFIRMATION),
                        escrow(good, EscrowStatus.PENDING_CONFIRMATION)));
        willThrow(new RuntimeException("stripe down"))
                .given(escrowLifecycleService).cancelExpiredPendingConfirmation(bad);
        willReturn(true).given(escrowLifecycleService).cancelExpiredPendingConfirmation(good);

        int count = b.cancelExpiredPendingConfirmations(NOW);

        // 1 件目が落ちても 2 件目は処理され、成功 1 件として集計される。
        assertThat(count).isEqualTo(1);
        verify(escrowLifecycleService).cancelExpiredPendingConfirmation(good);
    }

    @Test
    @DisplayName("HELD 抽出は hold_expires_at <= now+2h で行い、HELD/AUTHORIZED 取消へ委譲")
    void cancelsHeldWithExpiryThreshold() {
        EscrowLifecycleBatch b = batch();
        UUID id = UUID.randomUUID();
        LocalDateTime expectedThreshold = NOW.plusHours(2L);
        given(escrowTransactionRepository.findByStatusAndHoldExpiresAtLessThanEqual(
                EscrowStatus.HELD, expectedThreshold))
                .willReturn(List.of(escrow(id, EscrowStatus.HELD)));
        given(escrowLifecycleService.cancelExpiredHeldOrAuthorized(id)).willReturn(true);

        int count = b.cancelExpiredByHoldExpiry(EscrowStatus.HELD, NOW);

        assertThat(count).isEqualTo(1);
        verify(escrowTransactionRepository).findByStatusAndHoldExpiresAtLessThanEqual(
                EscrowStatus.HELD, expectedThreshold);
        verify(escrowLifecycleService).cancelExpiredHeldOrAuthorized(id);
    }

    @Test
    @DisplayName("AUTHORIZED 抽出も hold_expires_at <= now+2h で行い取消へ委譲")
    void cancelsAuthorizedWithExpiryThreshold() {
        EscrowLifecycleBatch b = batch();
        UUID id = UUID.randomUUID();
        LocalDateTime expectedThreshold = NOW.plusHours(2L);
        given(escrowTransactionRepository.findByStatusAndHoldExpiresAtLessThanEqual(
                EscrowStatus.AUTHORIZED, expectedThreshold))
                .willReturn(List.of(escrow(id, EscrowStatus.AUTHORIZED)));
        given(escrowLifecycleService.cancelExpiredHeldOrAuthorized(id)).willReturn(true);

        int count = b.cancelExpiredByHoldExpiry(EscrowStatus.AUTHORIZED, NOW);

        assertThat(count).isEqualTo(1);
        verify(escrowTransactionRepository).findByStatusAndHoldExpiresAtLessThanEqual(
                EscrowStatus.AUTHORIZED, expectedThreshold);
    }

    @Test
    @DisplayName("run(): 3 系統（PENDING/HELD/AUTHORIZED）の抽出を Clock 基準で起動する")
    void run_invokesAllThreeQueries() {
        EscrowLifecycleBatch b = batch();
        given(escrowTransactionRepository.findByStatusAndCreatedAtBefore(
                eq(EscrowStatus.PENDING_CONFIRMATION), eq(NOW.minusHours(72L)))).willReturn(List.of());
        given(escrowTransactionRepository.findByStatusAndHoldExpiresAtLessThanEqual(
                eq(EscrowStatus.HELD), eq(NOW.plusHours(2L)))).willReturn(List.of());
        given(escrowTransactionRepository.findByStatusAndHoldExpiresAtLessThanEqual(
                eq(EscrowStatus.AUTHORIZED), eq(NOW.plusHours(2L)))).willReturn(List.of());

        b.run();

        verify(escrowTransactionRepository).findByStatusAndCreatedAtBefore(
                EscrowStatus.PENDING_CONFIRMATION, NOW.minusHours(72L));
        verify(escrowTransactionRepository).findByStatusAndHoldExpiresAtLessThanEqual(
                EscrowStatus.HELD, NOW.plusHours(2L));
        verify(escrowTransactionRepository).findByStatusAndHoldExpiresAtLessThanEqual(
                EscrowStatus.AUTHORIZED, NOW.plusHours(2L));
    }
}

package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditAlertSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationCreditExpiryBatch} 単体テスト（Issue #2990 L4）。
 *
 * <p>是正前の本クラスは、バッチ内に private で持っていた期限アラート送信メソッドを
 * {@code ReflectionTestUtils.invokeMethod} で直接叩き、件名・本文の i18n を検証していた。
 * 当該メソッドは {@link NotificationCreditAlertSender} へ移設したため、その検体は
 * {@code NotificationCreditAlertSenderTest} へ移した。本クラスは移設後の責務、すなわち
 * <b>オーケストレーション（項目TXの実行と、そのコミット後の通知委譲）</b>を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationCreditExpiryBatch 単体テスト")
class NotificationCreditExpiryBatchTest {

    @Mock
    private NotificationCreditPurchaseRepository purchaseRepository;
    @Mock
    private NotificationCreditExpiryRunner expiryRunner;
    @Mock
    private NotificationCreditAlertSender alertSender;

    @InjectMocks
    private NotificationCreditExpiryBatch batch;

    /**
     * 検索結果として返す購入エンティティ。
     *
     * <p>モックではなく実インスタンスを使う。{@code mock()} を {@code given(...)} の引数の中で
     * 生成すると Mockito が入れ子のスタブ化とみなし {@code UnfinishedStubbingException} になるため
     * （実際に踏んだ）。本テストが必要とするのは {@code getId()} だけなので、
     * {@code @SuperBuilder} が公開している {@code id(...)} で組み立てれば足りる。</p>
     */
    private static NotificationCreditPurchaseEntity purchase(Long id) {
        return NotificationCreditPurchaseEntity.builder().id(id).build();
    }

    /** 30日前・7日前・失効いずれの検索も既定では空にしておく。 */
    private void noTargets() {
        given(purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent30dFalse(
                any(), any(), eq(NotificationCreditPurchaseStatus.PAID))).willReturn(List.of());
        given(purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent7dFalse(
                any(), any(), eq(NotificationCreditPurchaseStatus.PAID))).willReturn(List.of());
        given(purchaseRepository.findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
                any(), eq(NotificationCreditPurchaseStatus.PAID))).willReturn(List.of());
    }

    @Test
    @DisplayName("失効処理: 項目TXを実行し、そのコミット後に失効アラートを委譲する")
    void 失効処理は項目TXの後に通知する() {
        noTargets();
        given(purchaseRepository.findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
                any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(10L)));
        given(expiryRunner.expireOne(10L))
                .willReturn(new NotificationCreditExpiryRunner.ExpiryOutcome(1L, 100L));

        batch.runBatch();

        // 通知は「項目TXが終わったあと」でなければならない（逆だと通知だけ残る不整合になる）
        InOrder order = inOrder(expiryRunner, alertSender);
        order.verify(expiryRunner).expireOne(10L);
        order.verify(alertSender).sendCreditExpiredAlert(1L, 100L);
    }

    @Test
    @DisplayName("失効処理: 消費済み（失効0通）ならアラートを送らない")
    void 失効0通なら通知しない() {
        noTargets();
        given(purchaseRepository.findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
                any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(10L)));
        given(expiryRunner.expireOne(10L))
                .willReturn(new NotificationCreditExpiryRunner.ExpiryOutcome(1L, 0L));

        batch.runBatch();

        verify(alertSender, never()).sendCreditExpiredAlert(anyLong(), anyLong());
    }

    @Test
    @DisplayName("失効処理: 対象が消えていた項目はスキップし、後続を止めない")
    void 対象消失はスキップして継続する() {
        noTargets();
        given(purchaseRepository.findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
                any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(10L), purchase(11L)));
        given(expiryRunner.expireOne(10L)).willReturn(null);
        given(expiryRunner.expireOne(11L))
                .willReturn(new NotificationCreditExpiryRunner.ExpiryOutcome(2L, 50L));

        batch.runBatch();

        verify(alertSender, never()).sendCreditExpiredAlert(eq(1L), anyLong());
        verify(alertSender).sendCreditExpiredAlert(2L, 50L);
    }

    @Test
    @DisplayName("1件の通知失敗が後続の項目を巻き添えにしない（Issue #2990 の本体）")
    void 通知失敗は後続を止めない() {
        noTargets();
        given(purchaseRepository.findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
                any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(10L), purchase(11L)));
        given(expiryRunner.expireOne(10L))
                .willReturn(new NotificationCreditExpiryRunner.ExpiryOutcome(1L, 100L));
        given(expiryRunner.expireOne(11L))
                .willReturn(new NotificationCreditExpiryRunner.ExpiryOutcome(2L, 50L));
        doThrow(new DataIntegrityViolationException("通知の永続化に失敗"))
                .when(alertSender).sendCreditExpiredAlert(1L, 100L);

        batch.runBatch();

        // 1件目の通知が落ちても 2件目の失効処理と通知は実行される
        verify(expiryRunner).expireOne(11L);
        verify(alertSender).sendCreditExpiredAlert(2L, 50L);
    }

    @Test
    @DisplayName("30日前・7日前アラート: フラグ更新の項目TX後に、残り日数つきで通知を委譲する")
    void 期限アラートは残り日数つきで委譲される() {
        noTargets();
        given(purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent30dFalse(
                any(), any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(20L)));
        given(purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent7dFalse(
                any(), any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(21L)));
        LocalDate expiresOn = LocalDate.of(2026, 12, 1);
        given(expiryRunner.markAlertSent(20L, 30))
                .willReturn(new NotificationCreditExpiryRunner.AlertTarget(1L, 20L, expiresOn));
        given(expiryRunner.markAlertSent(21L, 7))
                .willReturn(new NotificationCreditExpiryRunner.AlertTarget(2L, 21L, expiresOn));

        batch.runBatch();

        verify(alertSender).sendExpiryAlert(1L, 20L, expiresOn, 30);
        verify(alertSender).sendExpiryAlert(2L, 21L, expiresOn, 7);
    }

    @Test
    @DisplayName("期限アラート: 対象が消えていたら通知しない")
    void 期限アラート対象消失は通知しない() {
        noTargets();
        given(purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent30dFalse(
                any(), any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(20L)));
        given(expiryRunner.markAlertSent(20L, 30)).willReturn(null);

        batch.runBatch();

        verify(alertSender, never()).sendExpiryAlert(anyLong(), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("投入拒否: 期限アラートの非同期投入が拒否されたら同期送信で送り直す（永久欠落の防止）")
    void 期限アラートの投入拒否は同期送信へフォールバックする() {
        noTargets();
        given(purchaseRepository.findByExpiresAtBetweenAndPaymentStatusAndAlertSent30dFalse(
                any(), any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(31L)));
        LocalDate expiresOn = LocalDate.now().plusDays(30);
        given(expiryRunner.markAlertSent(31L, 30))
                .willReturn(new NotificationCreditExpiryRunner.AlertTarget(7L, 31L, expiresOn));
        // event-pool は AbortPolicy。投入拒否は @Async メソッド本体の try/catch より前に起きる。
        doThrow(new RejectedExecutionException("event-pool saturated"))
                .when(alertSender).sendExpiryAlert(eq(7L), eq(31L), any(), eq(30));

        batch.runBatch();

        // 是正前はここでログを出して終わり。alert_sent_30d は立ったままなので
        // 次回バッチの検索条件（AlertSent30dFalse）から外れ、通知は二度と再試行されなかった。
        verify(alertSender).sendExpiryAlertNow(eq(7L), eq(31L), eq(expiresOn), eq(30));
    }

    @Test
    @DisplayName("投入拒否: 失効通知の非同期投入が拒否されたら同期送信で送り直す（残高差引は巻き戻せないため欠落は許されない）")
    void 失効通知の投入拒否は同期送信へフォールバックする() {
        noTargets();
        given(purchaseRepository.findByExpiresAtBeforeAndPaymentStatusAndExpiredAtIsNull(
                any(), eq(NotificationCreditPurchaseStatus.PAID)))
                .willReturn(List.of(purchase(41L)));
        given(expiryRunner.expireOne(41L))
                .willReturn(new NotificationCreditExpiryRunner.ExpiryOutcome(8L, 250L));
        doThrow(new RejectedExecutionException("event-pool saturated"))
                .when(alertSender).sendCreditExpiredAlert(eq(8L), eq(250L));

        batch.runBatch();

        verify(alertSender).sendCreditExpiredAlertNow(eq(8L), eq(250L));
    }
}

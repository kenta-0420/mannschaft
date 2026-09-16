package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

/**
 * 検体: {@code @Async} の自己呼び出しによる失効（バッチ形・多段委譲）。
 *
 * <p>本番の {@code NotificationCreditExpiryBatch:97,121,178} を最小再現したもの。
 * バッチ全体が単一の書き込みトランザクション（{@code @Transactional runBatch}）であり、
 * その配下の {@code private} メソッドから {@code @Async protected} メソッドを自己呼び出しする。
 * {@code @Async} が失効した結果、通知作成が {@code runBatch} のトランザクション内で確定し、
 * かつ通知側の失敗が {@code runBatch} 全体を rollback-only にしうる。
 *
 * <p>呼び出し元が {@code private} で、かつ {@code @Transactional} が<b>祖先メソッドにしか無い</b>
 * 多段委譲の形である点が要点。番人は自己呼び出しそのものを検出するため、
 * TX 文脈の伝播を追えなくても（本番人の限界）この形を取りこぼさない。
 *
 * <p>正しい形は {@code NotificationCreditMonthlyResetBatch} のように、
 * {@code runBatch} に {@code @Transactional} を付けず、別 Bean（Runner / AlertSender）へ委譲すること。
 */
public class AsyncSelfInvocationBatchFixture {

    private final HelperStub notificationHelper = new HelperStub();

    /** バッチ全体が単一の書き込みトランザクション。 */
    @Transactional
    public void runBatch() {
        process30DayAlert();
        process7DayAlert();
        processExpiry();
    }

    private void process30DayAlert() {
        sendExpiryAlertAsync(1L, 30);
    }

    private void process7DayAlert() {
        sendExpiryAlertAsync(1L, 7);
    }

    private void processExpiry() {
        sendCreditExpiredAlertAsync(1L, 100L);
    }

    /** 自己呼び出しの対象①。 */
    @Async
    protected void sendExpiryAlertAsync(Long organizationId, int days) {
        notificationHelper.notify(organizationId, "EXPIRY", "失効予告", "本文" + days);
    }

    /** 自己呼び出しの対象②。 */
    @Async
    protected void sendCreditExpiredAlertAsync(Long organizationId, long expiredCredits) {
        notificationHelper.notify(organizationId, "EXPIRED", "失効", "本文" + expiredCredits);
    }
}

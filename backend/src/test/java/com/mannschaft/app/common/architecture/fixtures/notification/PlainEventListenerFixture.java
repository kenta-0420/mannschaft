package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 検体: 通知を発火するリスナーが素の {@code @EventListener} である（＝業務コミット前に走る）。
 *
 * <p>本番の {@code ScheduleReminderNotificationListener:59} と
 * {@code TeamSlotNoteNotifyListener:66} を最小再現したもの。
 * 素の {@code @EventListener} は {@code publishEvent} の呼び出しスレッド上で<b>同期実行</b>され、
 * 発行元の業務トランザクションがまだ開いたままである。つまり通知が業務コミット前に作られ、
 * 業務側がロールバックすると通知だけが残る（あるいは通知の DB 例外が業務TXを巻き添えにする）。
 *
 * <p>{@code @Transactional(REQUIRES_NEW)} を足しても救われないことを
 * {@link #onSlotNoteUpdatedWithRequiresNew} で示す。TX 参加は切れるが「業務コミット後」という
 * 因果は依然として保証されない。
 */
public class PlainEventListenerFixture {

    private final HelperStub notificationHelper = new HelperStub();

    /** 負例: 素の {@code @EventListener}。同期・同一トランザクションで通知が確定する。 */
    @EventListener
    public void onReminderNotification(Object event) {
        notificationHelper.notifyAllPreAuthorized(event, "REMINDER", "件名", "本文");
    }

    /** 負例: {@code REQUIRES_NEW} を足しても AFTER_COMMIT の代用にはならない。 */
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onSlotNoteUpdatedWithRequiresNew(Object event) {
        try {
            notificationHelper.notify(event, "SLOT_NOTE", "件名", "本文");
        } catch (RuntimeException e) {
            // 握りつぶしても因果の逆転は直らない。
        }
    }

    /** 正例: AFTER_COMMIT を明示したリスナー。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReminderAfterCommit(Object event) {
        notificationHelper.notifyAllPreAuthorized(event, "REMINDER", "件名", "本文");
    }

    /** 正例: 通知を発火しない素の {@code @EventListener} は対象外（監査ログ等の別用途）。 */
    @EventListener
    public void onSomethingWithoutNotification(Object event) {
        String audit = String.valueOf(event);
        assert audit != null;
    }
}

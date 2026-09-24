package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RepositoryStub;
import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.RunnerStub;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 通知番人の検体（負例・正例）— {@code @Transactional} 文脈まわりのトポロジー。
 *
 * <p>クラスには意図的にアノテーションを付けない（クラス単位の TX 文脈が混ざると、
 * メソッド単位のトポロジーを1クラスに並べられなくなるため）。
 *
 * <p>メソッド名は<b>必ず ASCII</b> にすること。番人の字句走査は Java 既定の {@code \w}
 * （ASCII のみ）でメソッド宣言を拾うため、日本語メソッド名の検体は番人から見えず、
 * 「検出されないこと」を検出できたと誤認する偽の緑になる。
 *
 * <p><b>「@Async / REQUIRES_NEW / NOT_SUPPORTED 単独は AFTER_COMMIT の代用ではない」</b>
 * という契約の核心を、それぞれ独立した負例として持つ。家老の原案（@Async 専用 Bean からなら
 * 呼んでよい）が却下された理由がここで実証される: いずれも TX 参加は切るが「業務コミット後」
 * という因果は保証せず、業務側のロールバックで通知だけ残る逆向きの不整合が通る。
 */
public class TxNotificationFixture {

    private final HelperStub notificationHelper = new HelperStub();
    private final RunnerStub notificationDeliveryRunner = new RunnerStub();
    private final RepositoryStub repository = new RepositoryStub();

    // ------------------------------------------------------------------
    // 負例: 検出されなければならない
    // ------------------------------------------------------------------

    /** CMP-056 の代表トポロジー: 業務TX内で通知を発火し、try/catch で失敗を握って継続する。 */
    @Transactional
    public void notifyInsideTryWithinTx(Long userId) {
        repository.save(userId);
        try {
            notificationHelper.notify(userId, "TYPE", "件名", "本文");
        } catch (RuntimeException e) {
            // 「DB 例外等に巻き込まれない設計とするため catch」という誤った信念の再現。
            // 実際には通知側の DB 例外で業務TXに rollback-only が立ち、業務処理ごと巻き戻る。
        }
    }

    /** try を持たない単発の通知。#2990 の一覧にも入っていなかった形。 */
    @Transactional
    public void bareNotifyWithinTx(Long userId) {
        repository.save(userId);
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /** {@code NOT_SUPPORTED} は TX を中断するが「業務コミット後」を保証しない。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void notifyWithNotSupported(Long userId) {
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /** {@code REQUIRES_NEW} は独立TXになるが、業務コミット前に通知が先に確定する（逆向きの不整合）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyWithRequiresNew(Long userId) {
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /** {@code @Async} を付けても AFTER_COMMIT の代用にはならない。 */
    @Async("event-pool")
    @Transactional
    public void notifyWithAsyncAndTx(Long userId) {
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /** 配送 Runner を許可された入口以外（業務サービス）から直接呼ぶ。 */
    @Transactional
    public void sendOneFromBusinessService(Long userId) {
        repository.save(userId);
        notificationDeliveryRunner.sendOne(userId);
    }

    /** 許可された入口でない非TXメソッドからの sendOne 直呼びも同様に禁止。 */
    public void sendOneFromPlainMethod(Long userId) {
        notificationDeliveryRunner.sendOne(userId);
    }

    /**
     * バッチで最も多い形: {@code @Transactional} な入口から、アノテーションの無いヘルパへ委譲して通知する。
     *
     * <p>自己呼び出しではプロキシを経ないため、ヘルパ側に {@code @Transactional} が無くても
     * 入口のトランザクションがそのまま生きている。番人はこの伝播を追わないと
     * {@code ActionMemoReminderBatchService} / {@code TeamMemberTermReminderBatch} /
     * {@code AttendanceRequirementBatchService} の3件（いずれも #2990 に記載済み）を丸ごと取り逃す。
     */
    @Transactional
    public void txEntryDelegatingToHelper() {
        notifyFromUnannotatedHelper(1L);
    }

    /** 上の入口から呼ばれるヘルパ。アノテーションは無いが実行時は入口の TX 内で走る。 */
    void notifyFromUnannotatedHelper(Long userId) {
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    // ------------------------------------------------------------------
    // 正例: 違反として挙げてはならない
    // ------------------------------------------------------------------

    /** 正規形: AFTER_COMMIT を明示した配送リスナーから Runner を1件ずつ呼ぶ。 */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommitListener(Long userId) {
        notificationDeliveryRunner.sendOne(userId);
    }

    /** {@code @TransactionalEventListener} の phase 既定値は AFTER_COMMIT なので、省略も正例。 */
    @Async("event-pool")
    @TransactionalEventListener
    public void afterCommitListenerDefaultPhase(Long userId) {
        notificationDeliveryRunner.sendOne(userId);
    }

    /** 通知を一切発火しない業務メソッドは対象外。 */
    @Transactional
    public void businessMethodWithoutNotification(Long userId) {
        repository.save(userId);
    }
}

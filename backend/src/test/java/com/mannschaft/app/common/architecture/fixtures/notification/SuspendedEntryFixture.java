package com.mannschaft.app.common.architecture.fixtures.notification;

import com.mannschaft.app.common.architecture.fixtures.notification.NotificationFixtureStubs.HelperStub;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知番人の検体 — TX 文脈の起点から {@code NEVER} / {@code NOT_SUPPORTED} を外す変更（Issue #2997 G4-b / M5）の
 * 陰性・陽性対照。
 *
 * <p>{@code NotificationFanoutWorker#poll} は {@code @Transactional(propagation = NEVER)} の入口である。
 * {@code NEVER} は「TX が無いこと」を要求する宣言なので、そこから同一クラス内で到達するヘルパ
 * ({@code processReady} -> {@code processOne}) は<b>業務TXの内側ではない</b>。以前の番人は
 * {@code @Transactional} という綴りだけで起点にしていたため、これを TX 内と誤検知していた。</p>
 *
 * <p>一方、<b>自己呼び出しではプロキシを経ない</b>ので、REQUIRED な入口から呼ばれた先に付いた
 * {@code NEVER} / {@code NOT_SUPPORTED} は効かず、入口のトランザクションがそのまま生きている。
 * そのため「REQUIRED の入口 -> 自己呼出の NEVER/NOT_SUPPORTED -> 通知」は引き続き違反として挙げなければならない
 * （自己呼出で効かない宣言を免除の根拠にしてはならない）。</p>
 *
 * <p>メソッド名は必ず ASCII にすること（字句走査の都合。{@link TxNotificationFixture} 参照）。</p>
 */
public class SuspendedEntryFixture {

    private final HelperStub notificationHelper = new HelperStub();

    // ------------------------------------------------------------------
    // 陰性: 違反として挙げてはならない（起点が NEVER のみ）
    // ------------------------------------------------------------------

    /** 起点: TX が無いことを要求する入口。ここから到達する先は業務TXの内側ではない。 */
    @Transactional(propagation = Propagation.NEVER) public void pollWithNever() {
        processReadyJobs();
    }

    /** 起点: TX を中断する入口。ここから到達する先も業務TXの内側ではない。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void pollWithNotSupported() {
        processReadyJobsSuspended();
    }

    void processReadyJobs() {
        processOneJob(1L);
    }

    /** 陰性: NEVER の入口からだけ到達する通知。 */
    void processOneJob(Long jobId) {
        try {
            // Fanout の processOne と同型（失敗はジョブ行へ記録する設計）。
            notificationHelper.notify(jobId, "TYPE", "件名", "本文");
        } catch (RuntimeException e) {
            // 検体なので何もしない。
        }
    }

    void processReadyJobsSuspended() {
        processOneSuspendedJob(1L);
    }

    /** 陰性: NOT_SUPPORTED の入口からだけ到達する通知。 */
    void processOneSuspendedJob(Long jobId) {
        notificationHelper.notify(jobId, "TYPE", "件名", "本文");
    }

    // ------------------------------------------------------------------
    // 陽性対照: 違反として挙げなければならない（REQUIRED の入口から自己呼出で到達）
    // ------------------------------------------------------------------

    /** 起点: 既定の REQUIRED。業務TXを開く。 */
    @Transactional
    public void txEntry() {
        selfCalledNever();
        selfCalledNotSupported();
    }

    /** 自己呼出ではプロキシを経ないため、この NEVER は効かず txEntry のTXが生きている（public でプロキシ入口にもなりうる形でも、起点から外すだけで閉包の伝播は止めない）。 */
    @Transactional(propagation = Propagation.NEVER)
    public void selfCalledNever() {
        fireAfterSelfCalledNever(2L);
    }

    /** 陽性対照: REQUIRED の入口 -> 自己呼出の NEVER -> 通知。 */
    void fireAfterSelfCalledNever(Long userId) {
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }

    /** 自己呼出ではプロキシを経ないため、この NOT_SUPPORTED は効かず txEntry のTXが生きている。 */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void selfCalledNotSupported() {
        fireAfterSelfCalledNotSupported(3L);
    }

    /** 陽性対照: REQUIRED の入口 -> 自己呼出の NOT_SUPPORTED -> 通知。 */
    void fireAfterSelfCalledNotSupported(Long userId) {
        notificationHelper.notify(userId, "TYPE", "件名", "本文");
    }
}

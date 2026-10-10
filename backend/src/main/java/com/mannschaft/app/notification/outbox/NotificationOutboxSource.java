package com.mannschaft.app.notification.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 送り手ドメインの outbox 表を通知ドメインの relay から操作するための SPI（docs/architecture/notification_outbox.md §4）。
 *
 * <p>通知ドメインが interface を持ち、送り手ドメイン（team、6-E' で social）が実装する
 * （前例: {@code notification.fanout.FanoutRecipientSource}）。実装は<b>自ドメインの Repository だけ</b>を触り、
 * 各メソッドを {@code @Transactional(propagation = REQUIRES_NEW)} の独立した tx で行う（relay は tx を持たない）。
 * {@code MANDATORY} にしてはならない（D-3P-2）。</p>
 */
public interface NotificationOutboxSource {

    /** source の名前（メトリクスの tag {@code source}・起こしイベントの宛先）。例: {@code team}。 */
    String name();

    /**
     * {@code status='PENDING' AND next_attempt_at <= :now} を {@code id} 昇順に最大 {@code limit} 行、
     * {@code FOR UPDATE SKIP LOCKED} で取り、RELAYING・新しい {@code claim_token}・{@code claimed_at=:now} にして返す。
     */
    List<NotificationOutboxMessage> claim(int limit, Instant now);

    /**
     * RELAYED と {@code relayed_at=:now} に印を付ける。{@code id} と {@code claim_token} と RELAYING が一致したときだけ当たる。
     *
     * @return 当たったら true。古い世代（回収後に別の relay が claim し直した）なら false（何も変えない）
     */
    boolean markRelayed(UUID id, UUID claimToken, Instant now);

    /**
     * 取り込みの失敗を記録する。{@code attempt_count+1}・{@code last_error}・{@code claim_token=NULL} とし、
     * {@code dead} なら DEAD と {@code dead_at=:now}、そうでなければ PENDING と {@code next_attempt_at}。
     *
     * @return 当たったら true。古い世代なら false
     */
    boolean markFailed(UUID id, UUID claimToken, String error, Instant nextAttemptAt, boolean dead, Instant now);

    /**
     * 読めない版の行を、失敗回数を増やさず PENDING に戻して {@code next_attempt_at} まで先送りする。
     *
     * @return 当たったら true。古い世代なら false
     */
    boolean deferUnsupportedVersion(UUID id, UUID claimToken, Instant nextAttemptAt, Instant now);

    /** {@code claimed_at < :claimedBefore} の RELAYING を PENDING・{@code claim_token=NULL} に戻し、件数を返す。 */
    int recoverStuck(Instant claimedBefore, Instant now);

    /** {@code relayed_at < :relayedBefore} の RELAYED と {@code dead_at < :deadBefore} の DEAD を削除し、件数を返す。 */
    int sweep(Instant relayedBefore, Instant deadBefore);

    /** 最古の PENDING の {@code created_at}（無ければ空）。ゲージ {@code oldest_pending_age_seconds} に使う。 */
    Optional<Instant> oldestPendingCreatedAt();
}

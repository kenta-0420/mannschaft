package com.mannschaft.app.billing.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * {@link BillingCheckoutReconciliationQueue} の実装（BC-23 の補償退避）。
 *
 * <p>「Stripe 側に Checkout Session が実在するのに DB 側が倒れた」事実を、V198 で新設した
 * {@code billing_checkout_reconciliations} へ耐久化する。従来は marker 付き ERROR ログのみで、
 * 未回収の件数を機械的に数えられなかった（金銭が絡む穴）。以後の回収対象は
 * {@code SELECT COUNT(*) FROM billing_checkout_reconciliations WHERE status='PENDING'} で数えられる。</p>
 *
 * <h2>冪等性</h2>
 * <p>{@code uk_bcr_session} により同一 Session の再 enqueue は行を増やさず、{@code attempt_count} を
 * 積んで {@code status} を PENDING へ戻す（新たな失敗観測なので再び回収対象に載せる）。並行 enqueue の
 * INSERT が UNIQUE 違反になった場合は、既に他方が同じ事実を残しているため下の「最後の砦」へ落ちる。</p>
 *
 * <h2>トランザクション</h2>
 * <p>本 port は「呼び出し元の DB 更新が倒れた直後」に呼ばれる。外側の tx が rollback-only であっても
 * 退避行だけは必ず残す必要があるため {@link Propagation#REQUIRES_NEW} で独立させる。</p>
 *
 * <h2>最後の砦</h2>
 * <p>DB への退避自体が落ちた場合に限り、{@value #MARKER} を先頭に置いた ERROR ログを例外つきで残す
 * （握りつぶしではなく、DB が使えないときの唯一残る記録）。ここで再送出すると呼び出し元の
 * 「照合キューへ退避して 502」という設計上の経路そのものを失うため、記録に徹する。
 * Checkout URL・return state token・PII は出さない。出すのは Stripe の不透明 ID
 * （{@code cs_...} / {@code cus_...}）と退避の識別子までに留める。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BillingCheckoutReconciliationQueueAdapter implements BillingCheckoutReconciliationQueue {

    /** 運用アラートが購読する marker。変更する場合は監視設定と同時に行うこと。 */
    static final String MARKER = "BILLING_CHECKOUT_RECONCILIATION_REQUIRED";

    private final BillingCheckoutReconciliationJpaRepository reconciliationRepository;
    private final Clock clock;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(String stripeSessionId, String stripeCustomerRef, UUID idempotencyId) {
        Instant now = clock.instant();
        try {
            if (reconciliationRepository.recordRetry(stripeSessionId, now) == 1) {
                log.warn("{} requeued stripeSessionId={} idempotencyId={}",
                        MARKER, stripeSessionId, idempotencyId);
                return;
            }
            insert(stripeSessionId, stripeCustomerRef, idempotencyId, now);
            log.warn("{} enqueued stripeSessionId={} stripeCustomerRef={} idempotencyId={}",
                    MARKER, stripeSessionId, stripeCustomerRef, idempotencyId);
        } catch (RuntimeException e) {
            log.error("{} persist failed stripeSessionId={} stripeCustomerRef={} idempotencyId={}",
                    MARKER, stripeSessionId, stripeCustomerRef, idempotencyId, e);
        }
    }

    /** 並行 enqueue が同時に INSERT した場合は UNIQUE 違反として呼び出し元の記録経路へ倒す。 */
    private void insert(String stripeSessionId, String stripeCustomerRef, UUID idempotencyId, Instant now) {
        reconciliationRepository.saveAndFlush(BillingCheckoutReconciliationEntity.builder()
                .stripeSessionRef(stripeSessionId)
                .stripeCustomerRef(stripeCustomerRef)
                .idempotencyId(idempotencyId)
                .status(BillingCheckoutReconciliationStatus.PENDING)
                .attemptCount(1)
                .lastErrorAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }
}

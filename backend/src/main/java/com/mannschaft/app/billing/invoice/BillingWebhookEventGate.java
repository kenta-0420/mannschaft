package com.mannschaft.app.billing.invoice;

import com.mannschaft.app.billing.invoice.StripeBillingObjectView.EventEnvelope;
import com.mannschaft.app.payment.WebhookIdempotencyService;
import com.mannschaft.app.payment.WebhookProcessStatus;
import com.mannschaft.app.payment.service.StripeWebhookRetryableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * F20.1 PR5-A3: billing 所有イベントの共通ゲート（冪等・所有記録・失敗回数・再送判断）。
 *
 * <p>webhook の受信記録は {@code stripe_webhook_events} 1 表に閉じる。<b>リトライ台帳の新テーブルは作らない</b>
 * （AC-13）。1 行に {@code attempt_count} / {@code failed_at} / {@code process_status} を持たせ、
 * 5 回を超えた失敗は {@code FAILED} で確定して再送要求（5xx）を止める。</p>
 *
 * <p><b>失敗の扱い（握り潰さない）</b></p>
 * <ul>
 *   <li>{@link BillingInvoiceProjectionRejectedException}（fail-closed の恒久拒否）＝再送しても直らない。
 *       {@code FAILED} を記録して 200 を返す（再送ストームを起こさない）。</li>
 *   <li>その他の実行時例外＝一時失敗。{@code attempt_count} を加算し
 *       {@link StripeWebhookRetryableException} に包んで再送出する（Controller が 5xx を返す・AC-10）。</li>
 *   <li>ただし {@link #MAX_ATTEMPTS} 回を超えたら {@code FAILED} で確定し再送出しない（無限再送の停止）。</li>
 * </ul>
 *
 * <p><b>トランザクション</b>: 受信記録の更新は {@link WebhookIdempotencyService} 側で
 * {@code REQUIRES_NEW} に乗る。ハンドラ本体がロールバックしても記録は残り、Stripe の再送で拾い直せる
 * （AC-21）。逆に「注釈が同じクラスに書いてあるから同一トランザクション」と読んではならない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingWebhookEventGate {

    /** これを超える失敗で {@code FAILED} 確定（再送要求を止める）。 */
    public static final int MAX_ATTEMPTS = 5;

    private final WebhookIdempotencyService idempotencyService;
    private final StripeBillingPayloadParser parser;

    /**
     * 戻り値を持たないハンドラ用（成功時は {@code PROCESSED} 確定）。
     *
     * <p>{@link #runWithStatus} と名前を分けているのは、{@code Runnable} と
     * {@code Supplier} の両方に適合してしまうラムダ（例: 値を返すメソッド1つだけの式本体、
     * 例外を投げるだけの本体）でオーバーロード解決が曖昧にならないようにするため。</p>
     */
    public boolean run(EventEnvelope env, String payload, String stripeObjectRef,
                       UUID billingContractId, UUID billingCustomerId, Runnable body) {
        return runWithStatus(env, payload, stripeObjectRef, billingContractId, billingCustomerId, () -> {
            body.run();
            return WebhookProcessStatus.PROCESSED;
        });
    }

    /**
     * 冪等ゲートを通してハンドラを実行する。
     *
     * @return 常に {@code true}（billing 所有として消費した）。所有判定は呼び出し元で済ませておくこと。
     */
    public boolean runWithStatus(EventEnvelope env, String payload, String stripeObjectRef,
                                 UUID billingContractId, UUID billingCustomerId,
                                 Supplier<WebhookProcessStatus> body) {
        boolean shouldProcess = idempotencyService.tryBegin(
                env.eventId(), env.type(), env.livemode(),
                parser.sha256(payload), stripeObjectRef, billingContractId, billingCustomerId);
        if (!shouldProcess) {
            return true;
        }

        WebhookProcessStatus result;
        try {
            result = body.get();
        } catch (BillingInvoiceProjectionRejectedException e) {
            // 恒久拒否。再送しても同じ検体なので FAILED で確定し、200 を返して再送を止める。
            idempotencyService.markPermanentlyFailed(env.eventId());
            log.warn("F20.1 PR5: fail-closed で投影を拒否しました（再送しない）: eventId={}, type={}, reason={}",
                    env.eventId(), env.type(), e.getMessage());
            return true;
        } catch (RuntimeException e) {
            int attempts = idempotencyService.markFailedWithAttempt(env.eventId(), MAX_ATTEMPTS);
            if (attempts >= MAX_ATTEMPTS) {
                log.error("F20.1 PR5: 失敗が {} 回に達したため FAILED で確定します（再送要求を止める）: eventId={}, type={}",
                        attempts, env.eventId(), env.type(), e);
                return true;
            }
            log.warn("F20.1 PR5: billing 所有イベントの一時失敗。再送に委ねます（{}回目）: eventId={}, type={}",
                    attempts, env.eventId(), env.type(), e);
            throw new StripeWebhookRetryableException(
                    "billing 所有 webhook の処理に失敗しました: eventId=" + env.eventId(), e);
        }
        idempotencyService.markProcessed(env.eventId(), result);
        return true;
    }

    /**
     * PR5 で扱わないイベントを「受信したが確定しない」状態で記録する（AC-22 / 23 / 24）。
     *
     * <p>{@code PROCESSED}/{@code IGNORED} にしてしまうと、PR6 でこの種別を実装したときに
     * 冪等ゲートが「確定済み」と判定して<b>永久に拾えなくなる</b>。{@code RECEIVED} のまま残せば、
     * 種別（既存の {@code type} 列）だけで滞留の理由が判別でき、新規列も要らない。</p>
     */
    public void recordPending(EventEnvelope env, String payload, String stripeObjectRef) {
        idempotencyService.tryBegin(env.eventId(), env.type(), env.livemode(),
                parser.sha256(payload), stripeObjectRef, null, null);
        log.info("F20.1 PR5: 本 PR では扱わないイベントのため RECEIVED のまま保留します: eventId={}, type={}",
                env.eventId(), env.type());
    }
}

package com.mannschaft.app.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Billing Center PR6b-1: {@link BillingPlanChangeGateway} の Stripe 実装（第14隊）。
 *
 * <p>{@link StripeBillingPaymentGateway} と同じ流儀で、Stripe SDK 依存は payment ドメインの
 * {@link StripePaymentProvider} に封じ込める（本クラスは {@code com.stripe} を一切 import しない）。
 * 本クラスが担うのは「Stripe が返した値を billing の語彙へ写す」ことだけであり、
 * <b>金額の計算・状態の判断は一切行わない</b>。</p>
 *
 * <h2>なぜ本クラスが必要だったか</h2>
 * <p>ポート {@link BillingPlanChangeGateway} は試練Aが発注書として置いたもので、本実装が入るまで
 * <b>実装クラスが1つも存在しなかった</b>。{@code BillingPlanChangeService} 等3つの {@code @Service} が
 * 必須注入していたため、IT が {@code @MockitoBean} で覆っていない経路——すなわち<b>本番の
 * ApplicationContext——は起動できなかった</b>。ダミー実装や {@code @ConditionalOnMissingBean} で
 * 起動だけ通すのは対処療法であり、本クラスは実際に Stripe を呼ぶ本番実装である。</p>
 *
 * <h2>Stripe の意味論（公式「更新の保留」）</h2>
 * <ul>
 *   <li>{@code proration_behavior=always_invoice} … 差額を即時に請求書へ起こす</li>
 *   <li>{@code payment_behavior=pending_if_incomplete} … 支払いが通らなければ適用を保留する</li>
 *   <li><b>{@code pending_update} は支払いが失敗した／追加認証が要るときだけ返る。</b>
 *       同期的に支払いが成功した場合は返らない（E6'）。したがって
 *       {@code pending_update} の有無は「追加認証が要るか」の判定にそのまま使える</li>
 * </ul>
 *
 * <h2>見積り額と請求額の一致（{@code proration_date}）</h2>
 * <p>Stripe は「実際の按分を見積りと完全に一致させるには、実適用時にも {@code proration_date} を渡せ」と
 * している。本実装は<b>見積り時に按分基準日時を固定して Stripe へ渡し、同じ値を
 * {@link PlanChangeQuote#prorationAt()} で返す</b>。呼び出し側はそれを
 * {@code billing_change_previews.proration_at} へ保存し、適用時に
 * {@link PlanChangeApplyCommand#prorationDate()} として戻す。これを怠ると、preview の有効期限
 * （最大10分・AC-5）ぶんの経過時間で<b>利用者が承認した額と実際の請求額がずれる</b>。</p>
 *
 * <h2>実機（Stripe テストモード）での要確認事項</h2>
 * <p>見積りで {@code expand=total_tax_amounts.tax_rate} を指定して税名・税率を Stripe から運んでいるが、
 * <b>実 API での動作は未検証</b>である（ローカルに Docker が無く、CI も Stripe を呼ばない）。
 * テストモードでの疎通時に、この展開が受理されること・税名/税率が実際に埋まることを確認すること。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeBillingPlanChangeGateway implements BillingPlanChangeGateway {

    /** 税率のパーセントを basis points へ写す倍率（10.00% → 1000bp）。 */
    private static final BigDecimal PERCENT_TO_BASIS_POINTS = BigDecimal.valueOf(100);

    private final StripePaymentProvider stripePaymentProvider;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * AC-1/AC-2: Stripe の見積り請求書をそのまま {@link PlanChangeQuote} へ写す。
     *
     * <p><b>日割りをこちらで計算しない。</b>金額の唯一の出所は Stripe が返した見積り請求書である。
     * 税抜額・税額も Stripe の値をそのまま運び、こちらで税込から逆算しない。</p>
     */
    @Override
    public PlanChangeQuote previewPlanChange(PlanChangePreviewCommand command) {
        // 按分の基準日時をここで1回だけ決め、Stripe へ渡し、同じ値を呼び出し側へ返す
        // （呼び出し側が preview 行へ保存し、適用時に proration_date として戻す）。
        Instant prorationAt = clock.instant();
        StripePaymentProvider.InvoicePreviewInfo preview =
                stripePaymentProvider.previewSubscriptionPlanChange(
                        command.subscriptionRef(),
                        command.targetStripePriceRef(),
                        command.quantity() == null ? null : command.quantity().longValue(),
                        PRORATION_BEHAVIOR_ALWAYS_INVOICE,
                        prorationAt.getEpochSecond());

        return new PlanChangeQuote(
                preview.currency(),
                zeroIfNull(preview.amountDue()),
                zeroIfNull(preview.totalExcludingTax()),
                zeroIfNull(preview.taxAmount()),
                preview.taxDisplayName(),
                toBasisPoints(preview.taxPercentage()),
                toInstant(preview.periodStartEpochSec()),
                toInstant(preview.periodEndEpochSec()),
                prorationAt);
    }

    /**
     * AC-29/AC-30/AC-31: {@code always_invoice} ＋ {@code pending_if_incomplete} で Stripe へ適用する。
     *
     * <p>冪等キー（{@code billing-operation-{operationId}}）と metadata（{@link #METADATA_OPERATION_ID_KEY}）は
     * 呼び出し側が組み立てたものをそのまま渡す。metadata は payment ドメイン側で
     * {@code putAllMetadata} による<b>差分マージ</b>になっており、引継の {@code handoverRequestId} 等を
     * 巻き添えで消さない。</p>
     */
    @Override
    public PlanChangeApplyResult applyPlanChange(PlanChangeApplyCommand command) {
        StripePaymentProvider.SubscriptionPlanChangeInfo applied =
                stripePaymentProvider.changeSubscriptionPlan(
                        command.subscriptionRef(),
                        command.targetStripePriceRef(),
                        command.quantity() == null ? null : command.quantity().longValue(),
                        command.prorationBehavior(),
                        command.paymentBehavior(),
                        command.metadata(),
                        command.stripeIdempotencyKey(),
                        // 見積り時の基準日時をそのまま渡す（ここで now を採ると見積り額とずれる）。
                        command.prorationDate() == null ? null : command.prorationDate().getEpochSecond());

        Instant pendingUpdateExpiresAt = toInstant(applied.pendingUpdateExpiresAtEpochSec());
        return new PlanChangeApplyResult(
                applied.latestInvoiceRef(),
                applied.latestInvoiceStatus(),
                applied.pendingUpdatePresent(),
                pendingUpdateExpiresAt,
                applied.pendingUpdatePresent()
                        ? writeTargetSnapshot(applied.pendingUpdateItems(), pendingUpdateExpiresAt) : null,
                // pending_update が返った＝まだ適用されていない。効力発生時刻はまだ存在しない。
                applied.pendingUpdatePresent() ? null : clock.instant());
    }

    /**
     * AC-48/AC-54: 追加認証の client secret を Stripe から<b>都度取得</b>する。
     *
     * <p>戻り値の {@code clientSecret} は短命な秘密であり、本クラスは<b>保存もログ出力もしない</b>
     * （AC-57/AC-58）。取得のたびに Stripe を叩くことで、別端末・再ログインからの再開（AC-71）が成立する。</p>
     */
    @Override
    public Optional<PaymentAction> retrievePaymentAction(String subscriptionRef, String invoiceRef) {
        return stripePaymentProvider.retrieveSubscriptionPaymentAction(subscriptionRef, invoiceRef)
                .map(action -> new PaymentAction(
                        action.type(), action.clientSecret(), toInstant(action.expiresAtEpochSec())));
    }

    /**
     * AC-79（E2'）: {@code pending_update} の適用内容を、確定時に現在 items と突き合わせられる形で残す。
     *
     * <p>形式は {@code StripeBillingPayloadParser#targetPriceRefFromSnapshot} が読む
     * {@code {"items":[{"id":…,"price":…,"quantity":…}],"expiresAt":…}} に揃える。
     * 生の Stripe payload をそのまま焼くことはしない（AC-139: raw payload を DB に残さない）。</p>
     *
     * @param items     {@code pending_update.subscription_items}
     * @param expiresAt {@code pending_update.expires_at}（null 可）
     * @return スナップショット JSON（書けなければ null）
     */
    private String writeTargetSnapshot(
            List<StripePaymentProvider.SubscriptionItemDetail> items, Instant expiresAt) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StripePaymentProvider.SubscriptionItemDetail item : items) {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", item.itemId());
            row.put("price", item.priceRef());
            row.put("quantity", item.quantity());
            rows.add(row);
        }
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("items", rows);
        snapshot.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            // ここで例外を伝播させると、Stripe 側では既に pending_update が立っているのに
            // API が 502 を返して change だけ FAILED になる（実体と台帳が食い違う）。
            // 照合材料が欠けたことは webhook 側が「一致しない＝確定しない」として安全側に倒すため、
            // null を返して失敗を記録に残す。
            log.error("PR6b-1: pending_update のスナップショットを JSON 化できなかった: itemCount={}",
                    items.size(), e);
            return null;
        }
    }

    /**
     * 税率のパーセントを basis points へ写す（10.00% → 1000bp）。
     *
     * @param percentage Stripe の税率（null 可）
     * @return basis points（判らなければ null）
     */
    private Integer toBasisPoints(BigDecimal percentage) {
        if (percentage == null) {
            return null;
        }
        return percentage.multiply(PERCENT_TO_BASIS_POINTS)
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /**
     * Stripe 由来の unix 秒を {@link Instant} へ変換する（null は null のまま）。
     *
     * @param epochSec unix 秒（null 可）
     * @return 変換結果
     */
    private Instant toInstant(Long epochSec) {
        return epochSec == null ? null : Instant.ofEpochSecond(epochSec);
    }

    /**
     * 金額の null を 0 として扱う（{@code PlanChangeQuote} は primitive で受ける）。
     *
     * @param value Stripe の金額（null 可）
     * @return 最小貨幣単位の金額
     */
    private long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }
}

package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * {@link BillingStripeCheckoutGateway} の Stripe 実装（BC-13 / BC-16）。
 *
 * <p>方針:</p>
 * <ul>
 *   <li>{@code mode=subscription}。<b>inline price_data は使わず</b>、保存済み Stripe Price
 *       （{@code stripePriceRef}）を line item の price として参照する
 *       （{@code StripePaymentProviderImpl#createBillingSubscriptionCheckoutSession} は inline 方式で
 *       価格版の同一性を担保できないため流用しない）。</li>
 *   <li>{@code payment_method_types} は指定しない（Dashboard の dynamic payment methods に委ねる）。</li>
 *   <li>{@code automatic_tax} は<b>有効化しない</b>。有効な税登録が確認できておらず、有効化しても
 *       Stripe は無言で 0 円課税になるだけで誤解を生む（Stripe skill: stripe-best-practices/billing）。</li>
 *   <li>metadata（PII を含まない 4 点）は session と subscription_data の<b>両方</b>へ載せる。</li>
 *   <li>{@code expires_at} は呼び出し元が月境界から導いた値をそのまま使う。</li>
 *   <li>Idempotency-Key を {@link RequestOptions} に渡し、再送で Session が二重作成されないようにする。</li>
 * </ul>
 *
 * <p><b>復帰 URL:</b> {@link BillingReturnStateService} で署名付き state を発行し
 * {@code {base}/billing/checkout/success|cancel?state=...} を組み立てる。base は
 * {@code app.base-url}（正準プロパティ・ハードコード禁止）から取る。state の有効期限は
 * BC-16 の契約どおり <b>min(Session expiry + 15分, 発行から 24時間)</b> とする
 * （{@link BillingReturnStateService#issue} は 24 時間超を拒否する）。</p>
 *
 * <p><b>ログ:</b> Checkout URL・state token・client secret・raw payload は一切出さない。
 * 出すのは Stripe の Session ID（不透明な識別子）までに留める。</p>
 */
@Slf4j
@Component
class BillingStripeCheckoutGatewayImpl implements BillingStripeCheckoutGateway {

    /** BC-16: Session 失効後も復帰できるようにする state の猶予。 */
    private static final Duration RETURN_STATE_GRACE = Duration.ofMinutes(15);

    /** BC-16: state の発行からの絶対上限（{@link BillingReturnStateService} と同一）。 */
    private static final Duration RETURN_STATE_MAX_LIFETIME = Duration.ofHours(24);

    /** 復帰先タブ（課金ハブのプラン面）。 */
    private static final String RETURN_TAB = "plan";

    private static final String SUCCESS_PATH = "/billing/checkout/success";
    private static final String CANCEL_PATH = "/billing/checkout/cancel";

    private final Clock clock;
    private final BillingReturnStateService returnStateService;
    private final String baseUrl;

    BillingStripeCheckoutGatewayImpl(@Qualifier("wallClock") Clock clock,
                                     BillingReturnStateService returnStateService,
                                     @Value("${app.base-url}") String baseUrl) {
        this.clock = clock;
        this.returnStateService = returnStateService;
        // 末尾スラッシュは application.yml の規約上付かないが、付いた場合の二重スラッシュを防ぐ。
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public BillingStripeCheckoutResult createSubscription(BillingStripeCheckoutRequest request) {
        long actorId = SecurityUtils.getCurrentUserId();
        Instant stateExpiry = returnStateExpiry(request.expiresAt());
        String successUrl = request.successUrl() != null ? request.successUrl()
                : returnUrl(SUCCESS_PATH, BillingReturnStateService.Purpose.CHECKOUT_SUCCESS,
                        request, actorId, stateExpiry);
        String cancelUrl = request.cancelUrl() != null ? request.cancelUrl()
                : returnUrl(CANCEL_PATH, BillingReturnStateService.Purpose.CHECKOUT_CANCEL,
                        request, actorId, stateExpiry);

        SessionCreateParams.Builder params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(request.stripeCustomerRef())
                .setSuccessUrl(successUrl)
                .setCancelUrl(cancelUrl)
                .setExpiresAt(request.expiresAt().getEpochSecond())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPrice(request.stripePriceRef())
                        .build());
        putMetadata(request.sessionMetadata(), params::putMetadata);

        SessionCreateParams.SubscriptionData.Builder subscriptionData =
                SessionCreateParams.SubscriptionData.builder();
        putMetadata(request.subscriptionMetadata(), subscriptionData::putMetadata);
        params.setSubscriptionData(subscriptionData.build());

        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(request.stripeIdempotencyKey())
                .build();
        try {
            Session session = Session.create(params.build(), options);
            log.info("PR4 Checkout Session 作成: sessionId={}, expiresAt={}",
                    session.getId(), session.getExpiresAt());
            return new BillingStripeCheckoutResult(session.getId(), session.getUrl(),
                    session.getExpiresAt() == null
                            ? request.expiresAt()
                            : Instant.ofEpochSecond(session.getExpiresAt()));
        } catch (StripeException e) {
            // URL / state / payload は出さない。突合に要る契約 ID だけ残す。
            log.error("PR4 Checkout Session 作成失敗: billingContractId={}",
                    request.sessionMetadata() == null
                            ? null : request.sessionMetadata().get("billingContractId"), e);
            throw new BusinessException(EntitlementErrorCode.STRIPE_UNAVAILABLE, e);
        }
    }

    /** BC-16: min(Session expiry + 15分, 発行から 24時間)。 */
    private Instant returnStateExpiry(Instant sessionExpiry) {
        Instant graced = sessionExpiry.plus(RETURN_STATE_GRACE);
        Instant hardCap = clock.instant().plus(RETURN_STATE_MAX_LIFETIME);
        return graced.isAfter(hardCap) ? hardCap : graced;
    }

    /**
     * 署名付き state を発行して復帰 URL を組み立てる。
     *
     * <p>actor は {@link SecurityUtils#getCurrentUserId()} から解決する。本 gateway は認証済み
     * HTTP 入口（{@link BillingCheckoutController}）からのみ呼ばれ、state の nonce は actor に束縛して
     * CAS 消費するため、actor が取れない呼び出しは成立させてはならない（fail-closed）。</p>
     */
    private String returnUrl(String path, BillingReturnStateService.Purpose purpose,
                             BillingStripeCheckoutRequest request, long actorId, Instant expiresAt) {
        Map<String, String> metadata = request.sessionMetadata();
        BillingReturnStateService.ReturnState state = new BillingReturnStateService.ReturnState(
                purpose,
                EntitlementScopeKind.valueOf(metadata.get("scopeKind")),
                Long.parseLong(metadata.get("scopeId")),
                actorId,
                RETURN_TAB,
                null,
                null,
                UUID.fromString(metadata.get("billingCustomerId")),
                clock.instant(),
                expiresAt,
                UUID.randomUUID().toString());
        String token = returnStateService.issue(state);
        return baseUrl + path + "?state=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private void putMetadata(Map<String, String> metadata, BiConsumer<String, String> sink) {
        if (metadata == null) {
            return;
        }
        metadata.forEach(sink);
    }
}

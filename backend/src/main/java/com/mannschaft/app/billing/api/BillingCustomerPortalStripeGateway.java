package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.stripe.exception.StripeException;
import com.stripe.model.billingportal.Configuration;
import com.stripe.model.billingportal.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponse;
import com.stripe.param.billingportal.SessionCreateParams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PR5 Billing Center: Stripe Customer Portal セッション発行の Stripe 実装（AC-64〜AC-68 / AC-73 / AC-74）。
 *
 * <p>正本: {@code docs/features/F20.1_entitlement_billing/05_billing_center.md} :348 / :350 / :370。</p>
 *
 * <ul>
 *   <li><b>configuration は環境変数固定</b>（{@code STRIPE_BILLING_PORTAL_CONFIGURATION_ID}）。
 *       Stripe 既定 configuration へ落ちることを許さない。</li>
 *   <li><b>起動時照合</b>: {@link ApplicationReadyEvent} で configuration を 5 秒 timeout で取得し、
 *       {@code subscription_update} / {@code subscription_cancel} / {@code subscription_pause} が
 *       無効、{@code payment_method_update} / {@code invoice_history} / {@code customer_update} が
 *       有効であることを照合する。不一致・取得不能なら <b>アプリは起動したまま</b> Portal 開始だけを
 *       {@code ENTITLEMENT_027}(503) で拒否する（fail-closed）。{@code System.exit} は使わない
 *       — Portal は課金ハブの一機能に過ぎず、これで全機能を落とすのは過剰である。</li>
 *   <li><b>return_url は固定</b>: {@code {base}/billing/portal/return}。リクエスト由来の
 *       return URL は受け取らない（open redirect を構造的に不可能にする）。</li>
 *   <li><b>state の exp は issuedAt + 30 分</b>（Checkout の「Session expiry + 15 分」とは別契約）。</li>
 *   <li><b>順序</b>: nonce 登録 → Stripe セッション作成 → URL 返却。逆順だと
 *       「Stripe は成功したが DB が落ちた」窓が構造的に生まれ、Portal 専用の照合キューが要る。</li>
 * </ul>
 *
 * <p><b>ログ</b>: Portal URL・state token は一切出さない。出すのは Stripe の Session ID までに留める。</p>
 */
@Slf4j
@Component
class BillingCustomerPortalStripeGateway implements BillingCustomerPortalGateway {

    /** AC-64: configuration ID を固定する環境変数名（正本 §348）。 */
    static final String CONFIGURATION_ENV = "STRIPE_BILLING_PORTAL_CONFIGURATION_ID";

    /** AC-67: 唯一の復帰先。PR4 の {@code BillingReturnController#portalReturn} が受ける。 */
    static final String RETURN_PATH = "/billing/portal/return";

    /** AC-68: PORTAL_RETURN state の寿命（発行時刻 + 30 分）。 */
    static final Duration RETURN_STATE_LIFETIME = Duration.ofMinutes(30);

    /** AC-65: 起動時照合・セッション作成ともに Stripe API の timeout は 5 秒。 */
    static final int STRIPE_TIMEOUT_MILLIS = 5_000;

    /** 復帰先タブ（課金ハブの支払い面）。 */
    private static final String RETURN_TAB = "payment";

    /** AC-66: Portal から PLAN/ADDON を触らせないため、無効でなければならない機能。 */
    private static final List<String> FEATURES_MUST_BE_DISABLED =
            List.of("subscription_update", "subscription_cancel", "subscription_pause");

    /** 正本 §348 が Portal に許す機能。無効化されていたら configuration 取り違えとみなす。 */
    private static final List<String> FEATURES_MUST_BE_ENABLED =
            List.of("payment_method_update", "invoice_history", "customer_update");

    private final Clock clock;
    private final BillingReturnStateService returnStateService;
    private final String baseUrl;
    private final String configurationId;
    private final StripePortalConfigurationReader configurationReader;

    /**
     * 起動時照合を通った configuration。{@code null} の間は Portal 開始を 503 で拒否する。
     * 照合は起動時 1 回で、以後は再取得しない（Stripe 側の設定変更は再起動で反映する）。
     */
    private volatile BillingCustomerPortalConfigurationSnapshot verifiedSnapshot;

    @org.springframework.beans.factory.annotation.Autowired
    BillingCustomerPortalStripeGateway(
            @Qualifier("wallClock") Clock clock,
            BillingReturnStateService returnStateService,
            @Value("${app.base-url}") String baseUrl,
            @Value("${" + CONFIGURATION_ENV + ":}") String configurationId,
            ObjectMapper objectMapper) {
        this(clock, returnStateService, baseUrl, configurationId,
                (id, options) -> parseConfiguration(objectMapper, id, options));
    }

    /**
     * Stripe から configuration の <b>生の応答本文</b>を読む。
     *
     * <p>SDK 28.2.0 の型付きモデル（{@code Configuration.Features}）には
     * {@code subscription_pause} のフィールドが無く、{@code toJson()} も型付きフィールドしか
     * 出力しないため、型付き経由では「pause が有効になっている」ことを検知できない
     * （＝ AC-66 が黙って偽 green になる）。生の応答本文を読むのはこのためである。</p>
     */
    private static JsonNode parseConfiguration(ObjectMapper objectMapper, String configurationId,
                                               RequestOptions options) throws StripeException {
        StripeResponse response = Configuration.retrieve(configurationId, options).getLastResponse();
        if (response == null || response.body() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to parse Stripe portal configuration payload", e);
        }
    }

    /** テスト専用: Stripe への実 API 呼び出しを差し替える。 */
    BillingCustomerPortalStripeGateway(Clock clock, BillingReturnStateService returnStateService,
                                       String baseUrl, String configurationId,
                                       StripePortalConfigurationReader configurationReader) {
        this.clock = clock;
        this.returnStateService = returnStateService;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.configurationId = configurationId == null ? "" : configurationId.trim();
        this.configurationReader = configurationReader;
    }

    /** Stripe の configuration 取得。テストから差し替えるための唯一の外部 I/O 境界。 */
    @FunctionalInterface
    interface StripePortalConfigurationReader {
        JsonNode read(String configurationId, RequestOptions options) throws StripeException;
    }

    /**
     * AC-65: 起動完了時に configuration を照合する。失敗しても例外を投げず（＝アプリは起動する）、
     * 照合済み snapshot を持たないことで Portal 開始だけが fail-closed になる。
     */
    @EventListener(ApplicationReadyEvent.class)
    void verifyConfigurationOnStartup() {
        this.verifiedSnapshot = verifyConfiguration();
    }

    /** 照合本体（起動時イベントから分離し、テストから直接呼べるようにする）。 */
    BillingCustomerPortalConfigurationSnapshot verifyConfiguration() {
        if (configurationId.isEmpty()) {
            log.error("Stripe Customer Portal の configuration ID が未設定（環境変数 {}）。"
                    + "Portal 開始は fail-closed で拒否する", CONFIGURATION_ENV);
            return null;
        }
        RequestOptions options = RequestOptions.builder()
                .setConnectTimeout(STRIPE_TIMEOUT_MILLIS)
                .setReadTimeout(STRIPE_TIMEOUT_MILLIS)
                .build();
        JsonNode configuration;
        try {
            configuration = configurationReader.read(configurationId, options);
        } catch (StripeException | RuntimeException e) {
            log.error("Stripe Customer Portal の configuration を取得できない。Portal 開始は拒否する", e);
            return null;
        }
        List<String> mismatches = collectMismatches(configuration);
        if (!mismatches.isEmpty()) {
            log.error("Stripe Customer Portal の configuration が期待と不一致。Portal 開始は拒否する: {}",
                    mismatches);
            return null;
        }
        log.info("Stripe Customer Portal の configuration 照合に成功した");
        return new BillingCustomerPortalConfigurationSnapshot(configurationId, clock.instant());
    }

    /** 期待と食い違う点を列挙する（空なら照合成功）。 */
    private List<String> collectMismatches(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            return List.of("configuration payload is absent");
        }
        if (!configuration.path("active").asBoolean(false)) {
            return List.of("active must be true");
        }
        JsonNode features = configuration.path("features");
        if (!features.isObject()) {
            return List.of("features is absent");
        }
        List<String> mismatches = new ArrayList<>();
        for (String feature : FEATURES_MUST_BE_DISABLED) {
            if (featureEnabled(features, feature)) {
                mismatches.add(feature + " must be disabled");
            }
        }
        for (String feature : FEATURES_MUST_BE_ENABLED) {
            if (!featureEnabled(features, feature)) {
                mismatches.add(feature + " must be enabled");
            }
        }
        return mismatches;
    }

    /**
     * 機能の有効/無効。Stripe が返さない機能は Portal 上に現れないため「無効」とみなす
     * （SDK 28.2.0 の {@code Configuration.Features} には {@code subscription_pause} の
     * 型付きフィールドが無いため、生の応答本文から一様に読む）。
     */
    private boolean featureEnabled(JsonNode features, String feature) {
        return features.path(feature).path("enabled").asBoolean(false);
    }

    @Override
    public BillingCustomerPortalResult createSession(BillingCustomerPortalRequest request) {
        BillingCustomerPortalConfigurationSnapshot verified = verifiedSnapshot;
        if (verified == null) {
            // AC-65: 照合できていない configuration では Portal を開かない（既定 configuration へ落ちない）。
            throw new BusinessException(EntitlementErrorCode.PORTAL_UNAVAILABLE);
        }
        // AC-70: ここへ到達する時点で耐久冪等性の判定（begin）は HTTP 入口で既に済んでおり、
        // 同一キー・body 相違は Stripe を一度も呼ばずに 409 で打ち切られている。
        // 本 gateway はその決着したキーを Stripe 側の二重作成防止へ束縛するだけである。
        String idempotencyKey = request.stripeIdempotencyKey();
        Instant issuedAt = clock.instant();

        // AC-74: nonce 登録が先。Stripe が先だと「Stripe 成功・DB 失敗」の孤児 Session が生まれる。
        String signedState = returnStateService.issue(new BillingReturnStateService.ReturnState(
                BillingReturnStateService.Purpose.PORTAL_RETURN,
                request.scopeKind(), request.scopeId(), request.actorId(), RETURN_TAB,
                null, null, request.billingCustomerId(),
                issuedAt, issuedAt.plus(RETURN_STATE_LIFETIME), UUID.randomUUID().toString()));
        // AC-67: 復帰先は固定。リクエストからは一切受け取らない。
        String destination = baseUrl + RETURN_PATH
                + "?state=" + URLEncoder.encode(signedState, StandardCharsets.UTF_8);

        SessionCreateParams params = SessionCreateParams.builder()
                .setCustomer(request.stripeCustomerRef())
                .setConfiguration(verified.configuration())
                .setReturnUrl(destination)
                .build();
        RequestOptions options = RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .setConnectTimeout(STRIPE_TIMEOUT_MILLIS)
                .setReadTimeout(STRIPE_TIMEOUT_MILLIS)
                .build();
        try {
            Session session = Session.create(params, options);
            log.info("PR5 Portal Session 作成: sessionId={}", session.getId());
            return new BillingCustomerPortalResult(session.getUrl(), issuedAt);
        } catch (StripeException e) {
            // AC-73: 握りつぶさず 502 へ写す。URL・state token は出さない。
            log.error("PR5 Portal Session 作成失敗: billingCustomerId={}", request.billingCustomerId(), e);
            throw new BusinessException(EntitlementErrorCode.STRIPE_UNAVAILABLE, e);
        }
    }
}

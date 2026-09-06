package com.mannschaft.app.billing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.billing.EntitlementErrorCode;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.billingportal.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.billingportal.SessionCreateParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR5 Portal gateway の <b>振る舞い</b>テスト（AC-64〜AC-68 / AC-73 / AC-74）。
 *
 * <p>Stripe の実 API は叩かず、configuration 取得は差し替え、{@code Session.create} は静的モックで
 * 捕捉する。{@link BillingPortalSessionContractTrialTest} が「そう書かれているか」を測るのに対し、
 * 本テストは「そう動くか」——起動時照合の合否、fail-closed の 503、Stripe へ渡す configuration と
 * return_url、state の 30 分、nonce 登録が Stripe より前であること——を実行で測る。</p>
 */
@DisplayName("PR5 Portal gateway の振る舞い")
class BillingCustomerPortalStripeGatewayTest {

    private static final String BASE_URL = "https://app.example.test";
    private static final String CONFIGURATION_ID = "bpc_test_0001";
    private static final Instant NOW = Instant.parse("2026-09-05T10:00:00Z");
    private static final UUID CUSTOMER_ID = UUID.fromString("00000000-0000-7000-8000-000000000501");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final BillingReturnStateService returnStateService = mock(BillingReturnStateService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 正本 §348 が要求する configuration（変更・解約・停止は無効、支払方法等は有効）。 */
    private static ObjectNode validConfiguration() {
        try {
            return (ObjectNode) OBJECT_MAPPER.readTree("""
                    {"active": true, "features": {
                      "subscription_update": {"enabled": false},
                      "subscription_cancel": {"enabled": false},
                      "subscription_pause": {"enabled": false},
                      "payment_method_update": {"enabled": true},
                      "invoice_history": {"enabled": true},
                      "customer_update": {"enabled": true}
                    }}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode configurationWithEnabled(String feature) {
        ObjectNode configuration = validConfiguration();
        ((ObjectNode) configuration.path("features").path(feature)).put("enabled", true);
        return configuration;
    }

    private BillingCustomerPortalStripeGateway gateway(
            String configurationId, BillingCustomerPortalStripeGateway.StripePortalConfigurationReader reader) {
        return new BillingCustomerPortalStripeGateway(
                clock, returnStateService, BASE_URL, configurationId, reader);
    }

    private static BillingCustomerPortalRequest request() {
        return new BillingCustomerPortalRequest(7_001L, EntitlementScopeKind.USER, 7_001L,
                CUSTOMER_ID, "cus_test_501", "billing-portal:key-501");
    }

    @Test
    @DisplayName("AC64_起動時照合は環境変数の configuration ID を Stripe へ問い合わせる")
    void AC64_環境変数のconfigurationIDを照合する() {
        List<String> requested = new ArrayList<>();
        BillingCustomerPortalStripeGateway gateway = gateway(CONFIGURATION_ID, (id, options) -> {
            requested.add(id);
            return validConfiguration();
        });

        assertThat(gateway.verifyConfiguration())
                .extracting(BillingCustomerPortalConfigurationSnapshot::configuration)
                .isEqualTo(CONFIGURATION_ID);
        assertThat(requested).containsExactly(CONFIGURATION_ID);
    }

    @Test
    @DisplayName("AC65_Stripe API の timeout は接続・読み取りとも 5 秒")
    void AC65_timeoutは5秒() {
        List<RequestOptions> captured = new ArrayList<>();
        gateway(CONFIGURATION_ID, (id, options) -> {
            captured.add(options);
            return validConfiguration();
        }).verifyConfiguration();

        assertThat(captured).singleElement().satisfies(options -> {
            assertThat(options.getConnectTimeout()).isEqualTo(5_000);
            assertThat(options.getReadTimeout()).isEqualTo(5_000);
        });
    }

    @Test
    @DisplayName("AC65_configuration ID 未設定なら Stripe を呼ばず照合不成立とする")
    void AC65_未設定ならStripeを呼ばない() {
        List<String> requested = new ArrayList<>();
        BillingCustomerPortalStripeGateway gateway = gateway("", (id, options) -> {
            requested.add(id);
            return validConfiguration();
        });

        assertThat(gateway.verifyConfiguration()).isNull();
        assertThat(requested).isEmpty();
    }

    @Test
    @DisplayName("AC65_configuration を取得できなければ照合不成立とする（起動は妨げない）")
    void AC65_取得不能なら照合不成立() {
        BillingCustomerPortalStripeGateway gateway = gateway(CONFIGURATION_ID, (id, options) -> {
            throw new ApiConnectionException("stripe is unreachable");
        });

        assertThat(gateway.verifyConfiguration()).isNull();
    }

    @Test
    @DisplayName("AC65_照合が成立していない間は Stripe を呼ばず 503（ENTITLEMENT_027）で拒否する")
    void AC65_未照合ならfailClosed503() {
        BillingCustomerPortalStripeGateway gateway =
                gateway(CONFIGURATION_ID, (id, options) -> validConfiguration());

        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            assertThatThrownBy(() -> gateway.createSession(request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EntitlementErrorCode.PORTAL_UNAVAILABLE);
            sessions.verifyNoInteractions();
        }
        verify(returnStateService, never()).issue(any());
    }

    @Test
    @DisplayName("AC66_subscription_update が有効な configuration は照合を通さない")
    void AC66_subscriptionUpdate有効は不合格() {
        assertThat(gateway(CONFIGURATION_ID,
                (id, options) -> configurationWithEnabled("subscription_update")).verifyConfiguration())
                .isNull();
    }

    @Test
    @DisplayName("AC66_subscription_cancel が有効な configuration は照合を通さない")
    void AC66_subscriptionCancel有効は不合格() {
        assertThat(gateway(CONFIGURATION_ID,
                (id, options) -> configurationWithEnabled("subscription_cancel")).verifyConfiguration())
                .isNull();
    }

    @Test
    @DisplayName("AC66_subscription_pause が有効な configuration は照合を通さない")
    void AC66_subscriptionPause有効は不合格() {
        assertThat(gateway(CONFIGURATION_ID,
                (id, options) -> configurationWithEnabled("subscription_pause")).verifyConfiguration())
                .isNull();
    }

    @Test
    @DisplayName("AC66_請求書履歴・支払方法が無効な configuration は取り違えとみなす")
    void AC66_許可機能が無効なら不合格() {
        ObjectNode configuration = validConfiguration();
        ((ObjectNode) configuration.path("features").path("invoice_history")).put("enabled", false);
        assertThat(gateway(CONFIGURATION_ID, (id, options) -> configuration).verifyConfiguration())
                .isNull();
    }

    @Test
    @DisplayName("AC64_AC67_AC68_AC74_照合済みなら nonce 登録 → Stripe の順で発行し、固定 return_url と 30 分 state を使う")
    void AC64_AC67_AC68_AC74_発行の全体像() {
        BillingCustomerPortalStripeGateway gateway =
                gateway(CONFIGURATION_ID, (id, options) -> validConfiguration());
        gateway.verifyConfigurationOnStartup();
        when(returnStateService.issue(any())).thenReturn("kid.payload.signature");

        Session created = mock(Session.class);
        when(created.getId()).thenReturn("bps_test_1");
        when(created.getUrl()).thenReturn("https://billing.stripe.com/session/abc");

        ArgumentCaptor<SessionCreateParams> params = ArgumentCaptor.forClass(SessionCreateParams.class);
        BillingCustomerPortalResult result;
        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.create(any(SessionCreateParams.class), any(RequestOptions.class)))
                    .thenAnswer(invocation -> {
                        // AC-74: Stripe に到達した時点で nonce は既に登録済みでなければならない。
                        verify(returnStateService).issue(any());
                        return created;
                    });
            result = gateway.createSession(request());
            sessions.verify(() -> Session.create(params.capture(), any(RequestOptions.class)));
        }

        assertThat(result.portalUrl()).isEqualTo("https://billing.stripe.com/session/abc");
        assertThat(result.issuedAt()).isEqualTo(NOW);

        // AC-64: 環境変数で固定した configuration を必ず渡す（Stripe 既定へ落ちない）。
        assertThat(params.getValue().getConfiguration()).isEqualTo(CONFIGURATION_ID);
        // AC-67: return_url は固定パスのみ。要求由来の URL は入り込まない。
        assertThat(params.getValue().getReturnUrl())
                .startsWith(BASE_URL + BillingCustomerPortalStripeGateway.RETURN_PATH + "?state=");

        // AC-68: PORTAL_RETURN state の exp は発行時刻 + 30 分。
        ArgumentCaptor<BillingReturnStateService.ReturnState> state =
                ArgumentCaptor.forClass(BillingReturnStateService.ReturnState.class);
        verify(returnStateService).issue(state.capture());
        assertThat(state.getValue().purpose())
                .isEqualTo(BillingReturnStateService.Purpose.PORTAL_RETURN);
        assertThat(state.getValue().issuedAt()).isEqualTo(NOW);
        assertThat(state.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    @DisplayName("AC73_Stripe 側の失敗は握りつぶさず 502（ENTITLEMENT_025）へ写す")
    void AC73_Stripe失敗は502() {
        BillingCustomerPortalStripeGateway gateway =
                gateway(CONFIGURATION_ID, (id, options) -> validConfiguration());
        gateway.verifyConfigurationOnStartup();
        when(returnStateService.issue(any())).thenReturn("kid.payload.signature");

        try (MockedStatic<Session> sessions = mockStatic(Session.class)) {
            sessions.when(() -> Session.create(any(SessionCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiConnectionException("stripe is unreachable"));
            assertThatThrownBy(() -> gateway.createSession(request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EntitlementErrorCode.STRIPE_UNAVAILABLE);
        }
    }
}

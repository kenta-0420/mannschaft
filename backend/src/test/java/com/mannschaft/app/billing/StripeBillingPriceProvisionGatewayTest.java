package com.mannschaft.app.billing;

import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 出陣隊（第4陣）: {@link StripeBillingPriceProvisionGateway} の純UT。
 *
 * <h2>なぜこのクラスが要るか</h2>
 * <p>{@link BillingPriceProvisionGateway} は本コミットまで実装クラスが {@code TEMP_STUB}
 * （3メソッドすべて {@link UnsupportedOperationException}）であり、Stripe への Product/Price 作成が
 * 一歩も動かなかった。{@code StripeBillingPlanChangeGatewayTest} と同じ流儀で、
 * (1) 本番実装が {@code @Service} として実在すること（＝ApplicationContext が起動できること）と、
 * (2) {@link StripePaymentProvider} へ渡す引数（productKind/productKey の metadata・
 * idempotencyKey・tax code）と、そこから受け取る値の写像を、Stripe を一切呼ばずに固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("価格改定（price-revisions）: Stripe Provision ゲートウェイ")
class StripeBillingPriceProvisionGatewayTest {

    private static final UUID REVISION_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID BAND_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @Mock
    private StripePaymentProvider stripePaymentProvider;

    @Captor
    private ArgumentCaptor<Map<String, String>> metadataCaptor;

    private StripeBillingPriceProvisionGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new StripeBillingPriceProvisionGateway(stripePaymentProvider);
    }

    // ═════════ コンテキスト充足（欠落の再発防止） ═════════

    @Test
    @DisplayName("BillingPriceProvisionGateway の本番実装が @Service として実在する")
    void 本番実装がBeanとして実在する() {
        assertThat(BillingPriceProvisionGateway.class)
                .as("ポートに本番実装が無いと、provision/retry/reconcile を必須注入する @Service が起動できない")
                .isAssignableFrom(StripeBillingPriceProvisionGateway.class);
        assertThat(StripeBillingPriceProvisionGateway.class.isAnnotationPresent(Service.class))
                .as("component scan に拾われる必要がある")
                .isTrue();
    }

    // ═════════ resolveOrCreateProduct（決定9改訂・AC-74/75/83/84） ═════════

    @Test
    @DisplayName("resolveOrCreateProduct: productKind/productKey を metadata に焼き、tax code・idempotencyKey をそのまま渡す")
    void resolveOrCreateProductは引数をそのまま渡す() {
        given(stripePaymentProvider.resolveOrCreateProduct(
                eq("prod_deterministic_1"), eq("PLAN:FULL"), metadataCaptor.capture(),
                eq("txcd_standard"), eq("price-product-create:prod_deterministic_1")))
                .willReturn(new StripePaymentProvider.ProductResolutionInfo("prod_deterministic_1", true));

        BillingPriceProvisionGateway.ProductResolution result = gateway.resolveOrCreateProduct(
                new BillingPriceProvisionGateway.ProductResolutionCommand(
                        BillingProductKind.PLAN, "FULL", "txcd_standard",
                        "prod_deterministic_1", "price-product-create:prod_deterministic_1"));

        assertThat(result.stripeProductId()).isEqualTo("prod_deterministic_1");
        assertThat(result.newlyCreated()).isTrue();
        assertThat(metadataCaptor.getValue())
                .containsEntry("productKind", "PLAN")
                .containsEntry("productKey", "FULL");
    }

    @Test
    @DisplayName("resolveOrCreateProduct: 同時作成競合時の newlyCreated=false もそのまま写す")
    void resolveOrCreateProductは既存Product解決結果もそのまま写す() {
        given(stripePaymentProvider.resolveOrCreateProduct(
                eq("prod_deterministic_2"), eq("ADDON:ads.hide"), metadataCaptor.capture(),
                isNull(), eq("price-product-create:prod_deterministic_2")))
                .willReturn(new StripePaymentProvider.ProductResolutionInfo("prod_deterministic_2", false));

        BillingPriceProvisionGateway.ProductResolution result = gateway.resolveOrCreateProduct(
                new BillingPriceProvisionGateway.ProductResolutionCommand(
                        BillingProductKind.ADDON, "ads.hide", null,
                        "prod_deterministic_2", "price-product-create:prod_deterministic_2"));

        assertThat(result.stripeProductId()).isEqualTo("prod_deterministic_2");
        assertThat(result.newlyCreated()).isFalse();
    }

    // ═════════ createPrice（AC-79〜82/86） ═════════

    @Test
    @DisplayName("createPrice: band snapshot の値・metadata・idempotencyKey をそのまま渡す")
    void createPriceは引数をそのまま渡す() {
        Map<String, String> metadata = Map.of("revisionId", REVISION_ID.toString(), "bandId", BAND_ID.toString());
        given(stripePaymentProvider.createPriceForRevision(
                eq("prod_x"), eq(4_400L), eq("jpy"), eq("month"), eq(1), eq("EXCLUSIVE"),
                eq(metadata), eq("price-band-create:" + BAND_ID)))
                .willReturn("price_created_1");

        BillingPriceProvisionGateway.PriceCreationResult result = gateway.createPrice(
                new BillingPriceProvisionGateway.PriceCreationCommand(
                        REVISION_ID, BAND_ID, "prod_x", 4_400L, "jpy", "month", 1, "EXCLUSIVE",
                        metadata, "price-band-create:" + BAND_ID));

        assertThat(result.stripePriceId()).isEqualTo("price_created_1");
        verify(stripePaymentProvider).createPriceForRevision(
                "prod_x", 4_400L, "jpy", "month", 1, "EXCLUSIVE", metadata, "price-band-create:" + BAND_ID);
    }

    // ═════════ findPriceByMetadata（AC-91/96・第5版重大3: tax_code まで含む全属性照合） ═════════

    @Test
    @DisplayName("findPriceByMetadata: 発見できた場合、Product の tax_code・metadata を含めて snapshot へ写す")
    void findPriceByMetadataは発見時にProductのtaxCodeとmetadataを写す() {
        given(stripePaymentProvider.findPriceByRevisionAndBandMetadata(
                REVISION_ID.toString(), BAND_ID.toString()))
                .willReturn(Optional.of(new StripePaymentProvider.PriceMetadataSnapshot(
                        "price_found_1", 4_400L, "jpy", "month", 1, "txcd_standard", "EXCLUSIVE",
                        Map.of("productKind", "PLAN", "productKey", "FULL"),
                        Map.of("revisionId", REVISION_ID.toString(), "bandId", BAND_ID.toString(),
                                "environmentId", "test"))));

        Optional<BillingPriceProvisionGateway.PriceSnapshot> result =
                gateway.findPriceByMetadata(REVISION_ID, BAND_ID);

        assertThat(result).isPresent();
        BillingPriceProvisionGateway.PriceSnapshot snapshot = result.orElseThrow();
        assertThat(snapshot.stripePriceId()).isEqualTo("price_found_1");
        assertThat(snapshot.unitAmount()).isEqualTo(4_400L);
        assertThat(snapshot.currency()).isEqualTo("jpy");
        assertThat(snapshot.recurringInterval()).isEqualTo("month");
        assertThat(snapshot.recurringIntervalCount()).isEqualTo(1);
        assertThat(snapshot.productKind()).isEqualTo("PLAN");
        assertThat(snapshot.productKey()).isEqualTo("FULL");
        assertThat(snapshot.productTaxCode()).isEqualTo("txcd_standard");
        assertThat(snapshot.taxBehavior()).isEqualTo("EXCLUSIVE");
        assertThat(snapshot.environmentId())
                .as("AC-79: 環境識別子は Price 自身の metadata（Product ではない）から読み戻す")
                .isEqualTo("test");
    }

    @Test
    @DisplayName("findPriceByMetadata: 見つからなければ empty をそのまま返す（フォールバック生成をしない）")
    void findPriceByMetadataは未発見時にemptyを返す() {
        given(stripePaymentProvider.findPriceByRevisionAndBandMetadata(
                REVISION_ID.toString(), BAND_ID.toString()))
                .willReturn(Optional.empty());

        Optional<BillingPriceProvisionGateway.PriceSnapshot> result =
                gateway.findPriceByMetadata(REVISION_ID, BAND_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findPriceByMetadata: Product metadata に productKind/productKey が無ければ null のまま返す（症状を隠さない）")
    void findPriceByMetadataはproductメタデータ欠落時にnullのまま返す() {
        given(stripePaymentProvider.findPriceByRevisionAndBandMetadata(
                REVISION_ID.toString(), BAND_ID.toString()))
                .willReturn(Optional.of(new StripePaymentProvider.PriceMetadataSnapshot(
                        "price_found_2", 1_000L, "jpy", "month", 1, null, "INCLUSIVE", Map.of(), Map.of())));

        BillingPriceProvisionGateway.PriceSnapshot snapshot =
                gateway.findPriceByMetadata(REVISION_ID, BAND_ID).orElseThrow();

        assertThat(snapshot.productKind()).isNull();
        assertThat(snapshot.environmentId()).isNull();
        assertThat(snapshot.productKey()).isNull();
    }
}

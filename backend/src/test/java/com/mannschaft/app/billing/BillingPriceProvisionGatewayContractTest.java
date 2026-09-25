package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 試練隊（第2陣）起動スモーク: {@link BillingPriceProvisionGateway} の本番実装が実在することの
 * 軽量固定（PR6b-1 第14隊の {@code StripeBillingPlanChangeGatewayTest} と同じ狙い）。
 *
 * <h2>なぜこの検体が要るか</h2>
 * <p>{@code PriceRevisionProvisionService} / {@code PriceRevisionRetryProvisionService} /
 * {@code BillingPriceProvisionRecoveryService}（E群・F群）は {@link BillingPriceProvisionGateway} を
 * 必須注入する。IT は {@code @MockitoBean} でポートを覆うため、本番実装（{@code @Service}）が1つも
 * 無い欠落は「ApplicationContext が起動できない」という形でしか現れず、Docker の無いローカルでは
 * 一度も観測されない（PR6b-1 で実際に起きた事故そのもの）。本クラスはリフレクションのみで
 * (1) 本番実装クラスが存在し (2) {@link BillingPriceProvisionGateway} を実装し
 * (3) {@code @Service} で component scan に拾われることを、Docker 無しでも固定する。</p>
 *
 * <p>クラス名 {@code StripeBillingPriceProvisionGateway} は本試練が発注する名前であり、出陣隊が
 * 変更する場合は本テストも合わせて更新すること。</p>
 */
class BillingPriceProvisionGatewayContractTest {

    @Test
    @DisplayName("BillingPriceProvisionGatewayの本番実装がBeanとして実在する")
    void 本番実装がBeanとして実在する() {
        assertThat(BillingPriceProvisionGateway.class)
                .as("ポートに本番実装が無いと、必須注入している PriceRevisionProvisionService 等が起動できない")
                .isAssignableFrom(StripeBillingPriceProvisionGateway.class);
        assertThat(StripeBillingPriceProvisionGateway.class.isAnnotationPresent(Service.class))
                .as("component scan に拾われる必要がある")
                .isTrue();
    }
}

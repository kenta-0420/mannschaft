package com.mannschaft.app.payment.stripe;

import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionCollection;
import com.stripe.param.SubscriptionListParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * 柱③-B PR-2 請求支払者の引継: {@link StripePaymentProviderImpl#listSubscriptionsByCustomer} の
 * <b>全ページ走査</b>（{@code autoPagingIterable()}）を検証する単体試験（設計書 §3.2 R4-P1-1・AC-33）。
 *
 * <p>List API は1リクエスト最大100件のページング型であり、目的のサブスクは2ページ目以降に存在しうる。
 * 1ページ目（{@code getData()}）だけを見る実装は「未作成」と誤判定し、二重サブスク＝二重課金に直結するため、
 * 本試験では<b>目的の1件を最終ページ側（151件中の末尾）に置き</b>、かつ {@code getData()} が
 * 一度も呼ばれないことを併せて検証する（1ページ目だけを見る実装では偽 green にならない）。</p>
 */
@DisplayName("StripePaymentProviderImpl 引継サブスク列挙の全ページ走査（AC-33）")
class StripePaymentProviderHandoverPagingTest {

    private final StripePaymentProviderImpl provider = new StripePaymentProviderImpl();

    @Test
    @DisplayName("has_more を追い切り、最終ページ側にある目的のサブスクも列挙する")
    void listSubscriptionsByCustomerWalksAllPages() {
        List<Subscription> all = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            all.add(stubSubscription("sub_noise_" + i, "active", Map.of()));
        }
        // 目的のサブスクは2ページ目以降（151件目）に置く。1ページ目だけ見る実装では見つからない。
        all.add(stubSubscription("sub_target", "trialing", Map.of("handoverRequestId", "HR-1")));

        SubscriptionCollection collection = mock(SubscriptionCollection.class);
        given(collection.autoPagingIterable()).willReturn(all);

        try (MockedStatic<Subscription> mocked = mockStatic(Subscription.class)) {
            mocked.when(() -> Subscription.list(any(SubscriptionListParams.class))).thenReturn(collection);

            List<StripePaymentProvider.SubscriptionDetail> result =
                    provider.listSubscriptionsByCustomer("cus_paging");

            assertThat(result).hasSize(151);
            assertThat(result.get(150).subscriptionId()).isEqualTo("sub_target");
            assertThat(result.get(150).metadata()).containsEntry("handoverRequestId", "HR-1");

            ArgumentCaptor<SubscriptionListParams> captor = ArgumentCaptor.forClass(SubscriptionListParams.class);
            mocked.verify(() -> Subscription.list(captor.capture()));
            SubscriptionListParams params = captor.getValue();
            assertThat(params.getCustomer()).isEqualTo("cus_paging");
            assertThat(params.getStatus()).isEqualTo(SubscriptionListParams.Status.ALL);
            assertThat(params.getLimit()).isEqualTo(100L);
        }

        // 1ページ目のみを見る実装（getData()）で済ませていないことを機械的に担保する。
        then(collection).should(never()).getData();
    }

    @Test
    @DisplayName("metadata が null の Subscription は空 Map として詰める（null を返さない）")
    void nullMetadataBecomesEmptyMap() {
        Subscription sub = stubSubscription("sub_nometa", "active", null);
        SubscriptionCollection collection = mock(SubscriptionCollection.class);
        given(collection.autoPagingIterable()).willReturn(List.of(sub));

        try (MockedStatic<Subscription> mocked = mockStatic(Subscription.class)) {
            mocked.when(() -> Subscription.list(any(SubscriptionListParams.class))).thenReturn(collection);

            List<StripePaymentProvider.SubscriptionDetail> result =
                    provider.listSubscriptionsByCustomer("cus_x");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).metadata()).isNotNull().isEmpty();
        }
    }

    @Test
    @DisplayName("retrieveSubscriptionDetail は cancel_at_period_end=null を false 扱いにする")
    void retrieveSubscriptionDetailTreatsNullCancelAtPeriodEndAsFalse() {
        Subscription sub = mock(Subscription.class);
        given(sub.getId()).willReturn("sub_r");
        given(sub.getStatus()).willReturn("trialing");
        given(sub.getCancelAtPeriodEnd()).willReturn(null);
        given(sub.getCurrentPeriodStart()).willReturn(1_700_000_000L);
        given(sub.getCurrentPeriodEnd()).willReturn(1_702_000_000L);
        given(sub.getPendingSetupIntent()).willReturn("seti_1");
        given(sub.getMetadata()).willReturn(Map.of("handoverRequestId", "HR-2"));

        try (MockedStatic<Subscription> mocked = mockStatic(Subscription.class)) {
            mocked.when(() -> Subscription.retrieve("sub_r")).thenReturn(sub);

            StripePaymentProvider.SubscriptionDetail detail = provider.retrieveSubscriptionDetail("sub_r");

            assertThat(detail.subscriptionId()).isEqualTo("sub_r");
            assertThat(detail.status()).isEqualTo("trialing");
            assertThat(detail.cancelAtPeriodEnd()).isFalse();
            assertThat(detail.currentPeriodStart()).isEqualTo(1_700_000_000L);
            assertThat(detail.currentPeriodEnd()).isEqualTo(1_702_000_000L);
            assertThat(detail.pendingSetupIntentId()).isEqualTo("seti_1");
            assertThat(detail.metadata()).containsEntry("handoverRequestId", "HR-2");
        }
    }

    private Subscription stubSubscription(String id, String status, Map<String, String> metadata) {
        Subscription sub = mock(Subscription.class);
        given(sub.getId()).willReturn(id);
        given(sub.getStatus()).willReturn(status);
        given(sub.getMetadata()).willReturn(metadata);
        return sub;
    }
}

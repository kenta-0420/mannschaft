package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC-71 の <b>宣言値</b>（scope ごと 10 回 / 1 時間）が、実際に共通レートリミット基盤へ
 * その値で渡ることを測る振る舞いテスト。
 *
 * <p>{@link BillingPortalSessionContractTrialTest} の source 走査は「10」「ofHours(1)」という
 * 字面がどこかに在ることしか見ない。ここでは呼び出し引数そのものを捕らえる。</p>
 */
@DisplayName("PR5 Portal の回数制限（AC-71）")
class BillingCustomerPortalRateLimiterAdapterTest {

    private final ValkeyRateLimiter rateLimiter = mock(ValkeyRateLimiter.class);
    private final BillingCustomerPortalRateLimiterAdapter adapter =
            new BillingCustomerPortalRateLimiterAdapter(rateLimiter);

    @Test
    @DisplayName("scope ごとのキーで 10 回 / 1 時間の固定ウィンドウを消費する")
    void scopeごとに10回毎時を消費する() {
        when(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new RateLimitResult(true, 10, 9, 0L, 1L));

        assertThat(adapter.tryConsume(EntitlementScopeKind.TEAM, 42L)).isTrue();

        ArgumentCaptor<String> zone = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Duration> window = ArgumentCaptor.forClass(Duration.class);
        verify(rateLimiter).tryConsume(zone.capture(), key.capture(), limit.capture(), window.capture());

        assertThat(zone.getValue()).isEqualTo(BillingCustomerPortalRateLimiterAdapter.ZONE);
        assertThat(key.getValue())
                .as("制限主体は認証主体ではなく対象 scope である")
                .isEqualTo("TEAM:42");
        assertThat(limit.getValue()).isEqualTo(10);
        assertThat(window.getValue()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("上限超過は false を返す（呼び出し側が 429 に写す）")
    void 上限超過はfalse() {
        when(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new RateLimitResult(false, 10, 0, 0L, 60L));

        assertThat(adapter.tryConsume(EntitlementScopeKind.USER, 7L)).isFalse();
    }

    @Test
    @DisplayName("scope が違えばキーが分かれる（TEAM:1 と USER:1 を混ぜない）")
    void scope種別が違えばキーも違う() {
        when(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new RateLimitResult(true, 10, 9, 0L, 1L));

        adapter.tryConsume(EntitlementScopeKind.USER, 1L);
        adapter.tryConsume(EntitlementScopeKind.TEAM, 1L);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter, org.mockito.Mockito.times(2))
                .tryConsume(anyString(), key.capture(), anyInt(), any());
        assertThat(key.getAllValues()).containsExactly("USER:1", "TEAM:1");
    }
}

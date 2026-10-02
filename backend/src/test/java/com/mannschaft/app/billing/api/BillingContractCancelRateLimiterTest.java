package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * PR6a AC-55 / AC-56: 解約・撤回の回数制限。
 *
 * <p>Valkey が答えられない環境（{@code StringRedisTemplate} が mock の統合テスト、Valkey 障害時）でも
 * 上限が効くことを測る。共通基盤は fail-open で通してしまうため、プロセス内カウンタが最後の歯止めになる。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PR6a 解約/撤回の回数制限（AC-55 / AC-56）")
class BillingContractCancelRateLimiterTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-09-12T03:15:00Z"), ZoneOffset.UTC);

    @Mock
    private ValkeyRateLimiter valkeyRateLimiter;

    private BillingContractCancelRateLimiter limiter() {
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any(Duration.class)))
                .willReturn(RateLimitResult.failOpen(10, 0L, 1L));
        return new BillingContractCancelRateLimiter(valkeyRateLimiter, FIXED);
    }

    @Test
    @DisplayName("AC-55: 同一 scope は10回まで通り11回目は拒否される（Valkey が fail-open でも効く）")
    void AC55_11回目は拒否される() {
        BillingContractCancelRateLimiter limiter = limiter();

        for (int i = 1; i <= 10; i++) {
            assertThat(limiter.tryConsume(EntitlementScopeKind.USER, 7L))
                    .as("%d 回目は通る", i).isTrue();
        }
        assertThat(limiter.tryConsume(EntitlementScopeKind.USER, 7L))
                .as("11 回目は拒否").isFalse();
    }

    @Test
    @DisplayName("AC-56: cancel と撤回は同一バケット — 呼び分けに関係なく scope 単位で数える")
    void AC56_scope単位の単一バケット() {
        BillingContractCancelRateLimiter limiter = limiter();

        // 解約→撤回の往復（5往復＝10回）まで通り、11回目で止まる。
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume(EntitlementScopeKind.TEAM, 99L)).isTrue();
            assertThat(limiter.tryConsume(EntitlementScopeKind.TEAM, 99L)).isTrue();
        }
        assertThat(limiter.tryConsume(EntitlementScopeKind.TEAM, 99L)).isFalse();
    }

    @Test
    @DisplayName("別 scope のバケットは独立している（他人の解約で自分が 429 にならない）")
    void 別scopeは独立() {
        BillingContractCancelRateLimiter limiter = limiter();

        for (int i = 0; i < 11; i++) {
            limiter.tryConsume(EntitlementScopeKind.TEAM, 1L);
        }
        assertThat(limiter.tryConsume(EntitlementScopeKind.TEAM, 2L)).isTrue();
        assertThat(limiter.tryConsume(EntitlementScopeKind.USER, 1L)).isTrue();
    }

    @Test
    @DisplayName("Valkey が拒否したらプロセス内カウンタが残っていても拒否する（制限は緩まない）")
    void Valkeyの拒否が優先される() {
        given(valkeyRateLimiter.tryConsume(anyString(), anyString(), anyInt(), any(Duration.class)))
                .willReturn(new RateLimitResult(false, 10, 0L, 0L, 1L));
        BillingContractCancelRateLimiter limiter =
                new BillingContractCancelRateLimiter(valkeyRateLimiter, FIXED);

        assertThat(limiter.tryConsume(EntitlementScopeKind.USER, 7L)).isFalse();
    }
}

package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.MeterRegistry;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** 実ValkeyRateLimiterを通し、外部Redis通信だけを置き換える。内部Service/Repo/security mockは使わない。 */
class LoyaltyRateLimitsTest {
    private StringRedisTemplate redis;
    private LoyaltyRateLimits limits;
    @BeforeEach @SuppressWarnings("unchecked") void fixture() {
        redis = mock(StringRedisTemplate.class);
        Map<String, Long> counts = new HashMap<>();
        doAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            return counts.merge(keys.getFirst(), 1L, Long::sum);
        }).when(redis).execute(any(RedisScript.class), anyList(), anyString());
        var beans = new StaticListableBeanFactory(Map.of("redis", redis));
        var limiter = new ValkeyRateLimiter(beans.getBeanProvider(StringRedisTemplate.class),
                new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
        ReflectionTestUtils.invokeMethod(limiter, "setClock", Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC));
        limits = new LoyaltyRateLimits(limiter);
    }
    @Test void AC18_previewのactor60回までは通し61回目を正確な429コードで拒否する() {
        for (int i = 0; i < 60; i++) limits.preview(1L, 10L);
        denied(() -> limits.preview(1L, 10L));
    }
    @Test void AC18_書込60回とpreview60回は別actor窓である() {
        for (int i = 0; i < 60; i++) { limits.write(1L, 10L); limits.preview(1L, 10L); }
        denied(() -> limits.write(1L, 10L));
        denied(() -> limits.preview(1L, 10L));
    }
    @Test void AC18_TEAM合計1000回を異なるactorと操作間で共有する() {
        for (long i = 1; i <= 1000; i++) {
            if (i % 2 == 0) limits.write(i, 10L); else limits.preview(i, 10L);
        }
        denied(() -> limits.write(1001L, 10L));
        assertThatCode(() -> limits.write(1002L, 11L)).doesNotThrowAnyException();
    }
    @Test void AC18_actor窓はTEAMを変更しても回避できず別actorには波及しない() {
        for (int i = 0; i < 60; i++) limits.preview(1L, 10L);
        denied(() -> limits.preview(1L, 11L));
        assertThatCode(() -> limits.preview(2L, 10L)).doesNotThrowAnyException();
    }
    @Test @SuppressWarnings("unchecked") void AC18_Valkey障害は既存failOpen方針を維持する() {
        doThrow(new RedisConnectionFailureException("external-unavailable"))
                .when(redis).execute(any(RedisScript.class), anyList(), anyString());
        assertThatCode(() -> limits.write(1L, 10L)).doesNotThrowAnyException();
        assertThatCode(() -> limits.preview(1L, 10L)).doesNotThrowAnyException();
    }
    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(LoyaltyErrorCode.LOYALTY_012);
    }
}

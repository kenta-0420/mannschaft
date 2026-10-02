package com.mannschaft.app.recruitment.filter;

import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketMutationRateLimitFilterTest {

    private ValkeyRateLimiter rateLimiter;
    private MarketMutationRateLimitFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rateLimiter = mock(ValkeyRateLimiter.class);
        when(rateLimiter.tryConsume(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(new RateLimitResult(true, 30, 29, 1L, 0L));
        ObjectProvider<ValkeyRateLimiter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rateLimiter);
        filter = new MarketMutationRateLimitFilter(provider);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("42", "n/a", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void personalListingCreation_isLimitedToThirtyPerHourPerUser() throws Exception {
        invoke("/api/v1/me/market/listings");

        verify(rateLimiter).tryConsume(eq("market:personal-listing-create"), eq("u:42"),
                eq(30), eq(Duration.ofHours(1)));
    }

    @Test
    void reportCreation_isLimitedToTenPerMinutePerUser() throws Exception {
        invoke("/api/v1/reports");

        verify(rateLimiter).tryConsume(eq("moderation:report-create"), eq("u:42"),
                eq(10), eq(Duration.ofMinutes(1)));
    }

    private void invoke(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, mock(FilterChain.class));
        assertThat(response.getStatus()).isEqualTo(200);
    }
}

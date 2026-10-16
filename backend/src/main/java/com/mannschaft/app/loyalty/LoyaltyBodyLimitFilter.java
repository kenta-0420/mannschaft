package com.mannschaft.app.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** チームポイントJSON本文の4KB境界。認証フィルターの後で検査する。 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class LoyaltyBodyLimitFilter extends OncePerRequestFilter {
    public LoyaltyBodyLimitFilter(ObjectMapper objectMapper) {}

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        chain.doFilter(request, response);
    }
}

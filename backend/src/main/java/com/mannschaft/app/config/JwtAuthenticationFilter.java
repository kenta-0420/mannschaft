package com.mannschaft.app.config;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * JWT認証フィルター。Authorization ヘッダーの Bearer トークン、または
 * HttpOnly Cookie の access_token を検証し、SecurityContextHolder に認証情報をセットする。
 *
 * <p>優先順位:</p>
 * <ol>
 *   <li>Authorization: Bearer ヘッダー（後方互換）</li>
 *   <li>access_token Cookie（HttpOnly Cookie 移行後のブラウザクライアント）</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCESS_TOKEN_COOKIE = "access_token";

    private final AuthTokenService tokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = tokenService.parseAccessToken(token);

            String userId = claims.getSubject();
            @SuppressWarnings("unchecked")
            List<String> roles = claims.get("roles", List.class);
            // access token の jti を取得（SecurityUtils.getCurrentSessionHash() で session_hash 計算に使用）
            String jtiClaim = claims.getId();

            List<SimpleGrantedAuthority> authorities = roles != null
                    ? roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()
                    : List.of();

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            // jti を details に格納して SecurityUtils から取得可能にする
            if (jtiClaim != null && !jtiClaim.isBlank()) {
                authentication.setDetails(Map.of("jti", jtiClaim));
            } else {
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            }

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (BusinessException e) {
            log.debug("JWT認証失敗: {}", e.getMessage());
            // SecurityContext をクリアして匿名アクセスとして処理を続行
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    /**
     * リクエストから JWT アクセストークンを取り出す。
     * Authorization: Bearer ヘッダーを優先し、なければ access_token Cookie を確認する。
     *
     * @param request HTTP リクエスト
     * @return トークン文字列、取得できなかった場合は null
     */
    private String extractToken(HttpServletRequest request) {
        // 1. Authorization: Bearer ヘッダー（後方互換 / モバイルアプリ向け）
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }

        // 2. access_token HttpOnly Cookie（ブラウザクライアント向け）
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            return Arrays.stream(cookies)
                    .filter(c -> ACCESS_TOKEN_COOKIE.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }
}

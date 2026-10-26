package com.mannschaft.app.config;

import com.mannschaft.app.auth.service.AuthTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link JwtAuthenticationFilter} の roles → authority 変換に関する単体テスト。
 *
 * <p>認可基盤完全根治 Phase 1（{@code docs/security/03_role_authority_model.md} §3.2.3）の検証。
 * roles に {@code "SYSTEM_ADMIN"} が含まれるトークンから {@code ROLE_SYSTEM_ADMIN} authority が
 * 自動構築されること（フィルタ無改修で SecurityConfig の hasRole が機能回復すること）を保証する。</p>
 *
 * <p>{@link Claims} はモックせず、実 JWT を {@link Jwts} で構築して
 * {@code tokenService.parseAccessToken} の戻り値とする。これにより Claims の各 getter を個別
 * スタブする必要がなくなり、{@code UnfinishedStubbingException} を回避する（フィルタの変換ロジック
 * のみに関心を絞る）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter ロール変換 単体テスト")
class JwtAuthenticationFilterRoleTest {

    /** 実 Claims 構築用の署名鍵（検証はしないため任意の 32 バイト以上で良い）。 */
    private static final String TEST_SECRET = "test-secret-key-must-be-at-least-32-bytes-long!!";
    private static final SecretKey SIGNING_KEY =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    @Mock
    private AuthTokenService tokenService;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(tokenService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** roles claim を持つ実 Claims を構築する（モックではなく本物の JWT パース結果）。 */
    private Claims realClaims(String subject, List<String> roles) {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(subject)
                .id(UUID.randomUUID().toString())
                .claim("roles", roles)
                .issuer("mannschaft")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(SIGNING_KEY)
                .compact();
        return Jwts.parser()
                .verifyWith(SIGNING_KEY)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private HttpServletRequest requestWithBearer(String token) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getHeader(HttpHeaders.AUTHORIZATION)).willReturn("Bearer " + token);
        return request;
    }

    @Test
    @DisplayName("SYSTEM_ADMIN を含む roles から ROLE_MEMBER と ROLE_SYSTEM_ADMIN が構築される")
    void doFilterInternal_SYSTEM_ADMIN_authority構築() throws Exception {
        // Given
        String token = "dummy-system-admin-token";
        given(tokenService.parseAccessToken(token))
                .willReturn(realClaims("1", List.of("MEMBER", "SYSTEM_ADMIN")));
        HttpServletRequest request = requestWithBearer(token);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        // When
        filter.doFilter(request, response, chain);

        // Then
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("1");
        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertThat(authorities).containsExactlyInAnyOrder("ROLE_MEMBER", "ROLE_SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("MEMBER のみの roles からは ROLE_MEMBER のみが構築される（ROLE_SYSTEM_ADMIN は付かない）")
    void doFilterInternal_MEMBERのみ_SYSTEM_ADMIN無し() throws Exception {
        // Given
        String token = "dummy-member-token";
        given(tokenService.parseAccessToken(token))
                .willReturn(realClaims("2", List.of("MEMBER")));
        HttpServletRequest request = requestWithBearer(token);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        // When
        filter.doFilter(request, response, chain);

        // Then
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertThat(authorities).containsExactly("ROLE_MEMBER");
        assertThat(authorities).doesNotContain("ROLE_SYSTEM_ADMIN");
    }
}

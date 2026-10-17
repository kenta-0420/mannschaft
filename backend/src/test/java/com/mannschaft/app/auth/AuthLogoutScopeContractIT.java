package com.mannschaft.app.auth;

import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.event.LogoutEvent;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthLoginController#logout の本人スコープ契約。
 * 実JWTフィルタ・Service・MySQLを通し、通常Cookieと署名済み認証の失効範囲を確認する。
 * DBの確定状態を観測するためテスト全体を@Transactionalで包まない。
 */
@AutoConfigureMockMvc
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AuthLogoutScopeContractIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEvents events;

    private final Map<String, String> redisValues = new HashMap<>();
    private long actorId;
    private long otherActorId;
    private String accessToken;
    private String rawRefreshToken;
    private Long sessionId;
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void 本人と他端末の独立セッションを用意する() {
        actorId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 70_000_000L;
        otherActorId = actorId + 1;
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> mockedValueOps = mock(ValueOperations.class);
        valueOps = mockedValueOps;
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(redisTemplate.hasKey(anyString())).willAnswer(invocation -> redisValues.containsKey(invocation.getArgument(0)));
        given(valueOps.get(anyString())).willAnswer(invocation -> redisValues.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redisValues.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        accessToken = authTokenService.issueAccessToken(actorId, List.of("MEMBER"));
        rawRefreshToken = UUID.randomUUID().toString();
        sessionId = saveSession(actorId, rawRefreshToken).getId();
    }

    @AfterEach
    void 所有行だけを片付ける() {
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id IN (?, ?)", actorId, otherActorId);
    }

    @Test
    void 無引数POST_本人Cookieだけ失効して他端末と別人を維持する() throws Exception {
        String jti = accessJti();
        RefreshTokenEntity anotherDevice = saveSession(actorId, UUID.randomUUID().toString());
        RefreshTokenEntity anotherActor = saveSession(otherActorId, UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("access_token", accessToken), new Cookie("refresh_token", rawRefreshToken)))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("access_token", 0))
                .andExpect(cookie().maxAge("refresh_token", 0));

        assertThat(isRevoked(sessionId)).isTrue();
        assertThat(isRevoked(anotherDevice.getId())).isFalse();
        assertThat(isRevoked(anotherActor.getId())).isFalse();
        assertThat(authTokenService.isJtiBlacklisted(jti)).isTrue();
        assertThat(events.stream(LogoutEvent.class).map(LogoutEvent::getUserId).toList()).containsExactly(actorId);
    }

    @Test
    void legacyCookie_本人セッションを失効する() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("access_token", accessToken),
                                new Cookie("refresh_token_hash", authTokenService.hashToken(rawRefreshToken))))
                .andExpect(status().isOk());
        assertThat(isRevoked(sessionId)).isTrue();
    }

    @Test
    void 両Cookie_rawを優先してlegacyの他端末を維持する() throws Exception {
        RefreshTokenEntity legacyDevice = saveSession(actorId, UUID.randomUUID().toString());
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("access_token", accessToken), new Cookie("refresh_token", rawRefreshToken),
                                new Cookie("refresh_token_hash", legacyDevice.getTokenHash())))
                .andExpect(status().isOk());
        assertThat(isRevoked(sessionId)).isTrue();
        assertThat(isRevoked(legacyDevice.getId())).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh_token", "refresh_token_hash"})
    void 他人Cookieと偽造query_本人accessだけ失効し別人に到達しない(String cookieName) throws Exception {
        String foreignRaw = UUID.randomUUID().toString();
        RefreshTokenEntity foreign = saveSession(otherActorId, foreignRaw);
        String foreignAccess = authTokenService.issueAccessToken(otherActorId, List.of("MEMBER"));
        String foreignJti = authTokenService.parseAccessToken(foreignAccess).getId();
        String actorJti = accessJti();
        mockMvc.perform(post("/api/v1/auth/logout").param("jti", foreignJti).param("exp", "9999999999")
                        .param("userId", String.valueOf(otherActorId))
                        .cookie(new Cookie("access_token", accessToken), new Cookie(cookieName,
                                "refresh_token".equals(cookieName) ? foreignRaw : foreign.getTokenHash())))
                .andExpect(status().isOk());
        assertThat(isRevoked(foreign.getId())).isFalse();
        assertThat(isRevoked(sessionId)).isFalse();
        assertThat(authTokenService.isJtiBlacklisted(actorJti)).isTrue();
        assertThat(authTokenService.isJtiBlacklisted(foreignJti)).isFalse();
        assertThat(events.stream(LogoutEvent.class)).isEmpty();
    }

    @Test
    void refreshCookieなし_現在のaccessを失効する() throws Exception {
        String jti = accessJti();
        mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("access_token", accessToken)))
                .andExpect(status().isOk());
        assertThat(authTokenService.isJtiBlacklisted(jti)).isTrue();
        assertThat(isRevoked(sessionId)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "malformed-refresh"})
    void 空や不正refreshCookie_本人accessだけ失効する(String rawToken) throws Exception {
        String jti = accessJti();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("access_token", accessToken), new Cookie("refresh_token", rawToken)))
                .andExpect(status().isOk());
        assertThat(authTokenService.isJtiBlacklisted(jti)).isTrue();
        assertThat(isRevoked(sessionId)).isFalse();
    }

    @Test
    void Valkey障害_本人refresh失効は実commitされる() throws Exception {
        doThrow(new RedisConnectionFailureException("意図したValkey障害"))
                .when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie("access_token", accessToken), new Cookie("refresh_token", rawRefreshToken)))
                .andExpect(status().isOk());

        assertThat(isRevoked(sessionId)).isTrue();
    }

    @Test
    void 管理者変身中_署名済み本人だけを失効する() throws Exception {
        accessToken = authTokenService.issueAccessToken(actorId, List.of("MEMBER", "SYSTEM_ADMIN"));
        String jti = accessJti();
        RefreshTokenEntity anotherDevice = saveSession(actorId, UUID.randomUUID().toString());
        RefreshTokenEntity targetActor = saveSession(otherActorId, UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-Admin-Impersonate-User-Id", otherActorId)
                        .cookie(new Cookie("access_token", accessToken), new Cookie("refresh_token", rawRefreshToken)))
                .andExpect(status().isOk());

        assertThat(isRevoked(sessionId)).isTrue();
        assertThat(isRevoked(anotherDevice.getId())).isFalse();
        assertThat(isRevoked(targetActor.getId())).isFalse();
        assertThat(authTokenService.isJtiBlacklisted(jti)).isTrue();
    }

    @Test
    void 匿名_失効処理へ到達しない() throws Exception {
        int status = mockMvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", rawRefreshToken)))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
        assertThat(isRevoked(sessionId)).isFalse();
        assertThat(redisValues).isEmpty();
    }

    private RefreshTokenEntity saveSession(long userId, String rawToken) {
        return refreshTokenRepository.save(RefreshTokenEntity.builder().userId(userId)
                .tokenHash(authTokenService.hashToken(rawToken)).jti(UUID.randomUUID().toString())
                .rememberMe(false).expiresAt(LocalDateTime.now().plusDays(7)).build());
    }

    private boolean isRevoked(Long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE id = ?", Boolean.class, id));
    }

    private String accessJti() {
        return authTokenService.parseAccessToken(accessToken).getId();
    }
}

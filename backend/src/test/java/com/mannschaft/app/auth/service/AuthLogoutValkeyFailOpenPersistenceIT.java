package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.RefreshTokenEntity;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

/**
 * Valkey（Redis）障害時に、セッション一斉無効化の <b>DB 側 revoke が fail-open で確実に永続化される</b>
 * ことを守る番人 結合テスト。
 *
 * <h2>このテストが守るバグ（PR #2071 検分で発見）</h2>
 * <p>{@link AuthSessionService#logoutAllDevices(Long)}（{@code @Transactional(REQUIRES_NEW)}）は、
 * Valkey 障害時も DB revoke を維持する意図で {@code authTokenService.setUserInvalidationTimestamp(...)} を
 * try-catch で囲んでいる（docs/security/02_cookie_and_session.md §4.1 の fail-open 方針）。しかし
 * {@link AuthTokenService} はクラスレベル {@code @Transactional} が付与されており、Valkey 専用の
 * {@code setUserInvalidationTimestamp} はプロキシ経由で <b>現在のトランザクションに参加</b>していた。
 * このため Valkey が例外を投げると:</p>
 * <ol>
 *   <li>内側の {@code @Transactional} メソッド境界で RuntimeException が捕捉され、
 *       現在のトランザクションが <b>rollback-only</b> にマークされる</li>
 *   <li>呼び出し元の try-catch で例外を握っても、コミット時に {@code UnexpectedRollbackException} が発生し、
 *       <b>DB revoke ごと巻き戻る</b></li>
 * </ol>
 * <p>結果、Valkey ダウン時にセッション無効化の DB 書き込みが消え、fail-open（Valkey を無視して DB で
 * 無効化を貫く）が実際には fail 側に倒れる。</p>
 *
 * <h2>根治</h2>
 * <p>{@link AuthTokenService} は DB を一切触らない（JWT + Valkey 専用）ため、クラスレベルの
 * {@code @Transactional} を撤去し、Valkey 操作が呼び出し元の DB トランザクションを rollback-only に
 * 汚さないようにする。これにより Valkey 例外は呼び出し元の try-catch に素通しで届き、DB revoke は残る。</p>
 *
 * <h2>なぜ純 Mockito UT では守れないのか（false-green）</h2>
 * <p>mock の {@code verify} ではトランザクション境界（rollback-only マーク → コミット時の
 * UnexpectedRollbackException）を一切踏まないため、クラスレベル {@code @Transactional} が残っていても緑のまま。
 * 本結合テストは実 MySQL（Testcontainers）＋実トランザクション境界を踏むため、この巻き戻りを検知できる。</p>
 *
 * <p>Docker（Testcontainers MySQL）が利用可能な環境でのみ実行される。</p>
 */
@DisplayName("Valkey障害時 セッション無効化DB revoke の fail-open 永続化 結合テスト")
// JUnit 5 の @EnabledIf は @Inherited ではないため、派生クラスでも明示的に再宣言する必要がある
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AuthLogoutValkeyFailOpenPersistenceIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private AuthSessionService authSessionService;

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** {@link AbstractMySqlIntegrationTest} が用意する {@code @MockitoBean} の Redis。 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * Valkey（Redis）が <b>障害中</b>（書き込みが例外を投げる）状態を模す。
     *
     * <p>{@code setUserInvalidationTimestamp} が呼ぶ {@code opsForValue().set(...)} が
     * {@link RedisConnectionFailureException}（{@code DataAccessException} = RuntimeException）を投げるようにする。
     * このバグは Valkey ダウン時に発生するため、意図的に例外を注入する。</p>
     */
    @BeforeEach
    void stubRedisDown() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        doThrow(new RedisConnectionFailureException("valkey down"))
                .when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    /**
     * Valkey 障害中に全デバイスログアウトを実行しても、当該ユーザーの全 refresh_token が
     * 実 DB 上で revoked_at NOT NULL になっている（fail-open で DB 無効化を貫く）。
     *
     * <p>本テストメソッドには {@code @Transactional} を付けない（付けるとテストの TX が全体を包み、
     * logoutAllDevices のコミット/ロールバック挙動を正しく観測できないため）。</p>
     */
    @Test
    @DisplayName("Valkey障害時_全デバイス無効化のDB revokeが巻き戻らず永続化される")
    void Valkey障害時_全デバイス無効化のDB_revokeが巻き戻らず永続化される() {
        // ── Given: あるユーザーに生存中の refresh_token を 2 件実コミット ──
        // userId は他テストと衝突しないよう UUID 下位ビットから一意採番する（refresh_tokens にクロスドメイン FK は無い）。
        long userId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 6_000_000L;

        RefreshTokenEntity tokenA = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(authTokenService.hashToken("valkey-down-A-" + userId))
                .jti(UUID.randomUUID().toString())
                .rememberMe(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        refreshTokenRepository.save(tokenA);

        RefreshTokenEntity tokenB = RefreshTokenEntity.builder()
                .userId(userId)
                .tokenHash(authTokenService.hashToken("valkey-down-B-" + userId))
                .jti(UUID.randomUUID().toString())
                .rememberMe(false)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        refreshTokenRepository.save(tokenB);

        Integer activeBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        assertThat(activeBefore).as("無効化前の生存トークン数").isEqualTo(2);

        // ── When: Valkey 障害中に全デバイスログアウト ──
        // fail-open 方針では Valkey 例外は握られ、logoutAllDevices は正常終了しなければならない。
        // 現状（AuthTokenService にクラスレベル @Transactional）では setUserInvalidationTimestamp の
        // 例外が REQUIRES_NEW tx を rollback-only にマークし、コミット時に UnexpectedRollbackException が飛ぶ。
        assertThatCode(() -> authSessionService.logoutAllDevices(userId))
                .as("Valkey 障害は fail-open で握られ、logoutAllDevices は例外を投げてはならない")
                .doesNotThrowAnyException();

        // ── Then: Valkey が落ちていても、全 refresh_token が実 DB 上で revoked_at NOT NULL である ──
        // JPA 一次キャッシュを介さず JDBC で実 DB の確定状態を読む（決定的証拠）。
        Integer activeAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        assertThat(activeAfter)
                .as("Valkey 障害時も DB 側の無効化は貫かれ、生存トークンは 0 でなければならない"
                        + "（クラスレベル @Transactional が残っていると rollback-only で巻き戻り 2 のまま）")
                .isEqualTo(0);
    }
}

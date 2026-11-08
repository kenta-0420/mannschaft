package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link ValkeyMfaEmailOtpService} の照合（compare-and-delete の Lua）を<b>実 Redis</b> で検証する（CMP-261012-0910）。
 *
 * <p>MFA の IT が使う Valkey 代役（{@code MfaValkeyFake}）は Lua を実行せず意味を模すだけのため、Lua 本文の正しさは
 * 代役の緑では証明できない。本テストは {@code ValkeyRateLimiterIntegrationTest} と同じ流儀（{@code @SpringBootTest}
 * を使わず、Testcontainers の {@code redis:7-alpine} に Lettuce + StringRedisTemplate を直接つなぐ）で、
 * 本物のスクリプトを本物の Redis で走らせる。照合に関係しない協力者（ユーザー・outbox・MFA セッション）はモック。</p>
 */
@DisplayName("ValkeyMfaEmailOtpService 照合の compare-and-delete（Testcontainers Redis）")
@EnabledIf("com.mannschaft.app.auth.service.ValkeyMfaEmailOtpServiceIntegrationTest#isDockerAvailable")
class ValkeyMfaEmailOtpServiceIntegrationTest {

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));

    private static final String LOGIN_SUBJECT = "session-hash-for-it";
    private static final String REAUTH_SUBJECT = MfaEmailOtpKeys.subjectOf(42L);

    private static StringRedisTemplate redisTemplate;
    private static LettuceConnectionFactory connectionFactory;

    private ValkeyMfaEmailOtpService service;

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void startContainer() {
        if (!isDockerAvailable()) {
            return;
        }
        try {
            REDIS.start();
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort("Redisコンテナ起動失敗（環境問題）: " + e.getMessage());
        }
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopContainer() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @BeforeEach
    void setUp() {
        connectionFactory.getConnection().serverCommands().flushAll();
        service = new ValkeyMfaEmailOtpService(redisTemplate, mock(UserRepository.class),
                mock(EmailOutboxService.class), mock(MfaSessionService.class));
    }

    private void storeCode(MfaEmailOtpPurpose purpose, String subject, String code) {
        redisTemplate.opsForValue().set(MfaEmailOtpKeys.codeKey(purpose, subject),
                ValkeyMfaEmailOtpService.hash(purpose, subject, code), ValkeyMfaEmailOtpService.CODE_TTL);
    }

    private String stored(MfaEmailOtpPurpose purpose, String subject) {
        return redisTemplate.opsForValue().get(MfaEmailOtpKeys.codeKey(purpose, subject));
    }

    @Test
    @DisplayName("一致したコードは成功し、コードは消費（削除）される")
    void 一致したコードは消費される() {
        storeCode(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "123456");

        assertThat(service.verify(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "123456"))
                .isEqualTo(MfaEmailOtpService.VerifyOutcome.MATCHED);
        assertThat(stored(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT)).as("消費後は残らない").isNull();
        assertThat(service.verify(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "123456"))
                .as("同じコードは 2 度通らない").isEqualTo(MfaEmailOtpService.VerifyOutcome.MISMATCHED);
    }

    @Test
    @DisplayName("一致しないコードは失敗し、保存中のコードは消さない")
    void 不一致は保存中のコードを消さない() {
        storeCode(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "123456");
        String before = stored(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT);

        assertThat(service.verify(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "654321"))
                .isEqualTo(MfaEmailOtpService.VerifyOutcome.MISMATCHED);
        assertThat(stored(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT)).as("保存中のコードはそのまま").isEqualTo(before);
    }

    @Test
    @DisplayName("再送で上書きされた後の旧コードは通らず、新しいコードは消されない（新しいコードは通る）")
    void 上書き後の旧コードは通らず新コードは残る() {
        storeCode(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "111111");
        storeCode(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "222222");
        String newer = stored(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT);

        assertThat(service.verify(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "111111"))
                .isEqualTo(MfaEmailOtpService.VerifyOutcome.MISMATCHED);
        assertThat(stored(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT)).as("新しいコードは消されない").isEqualTo(newer);
        assertThat(service.verify(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "222222"))
                .isEqualTo(MfaEmailOtpService.VerifyOutcome.MATCHED);
    }

    @Test
    @DisplayName("コード未送信（キー無し）は失敗し、キーを作らない")
    void 未送信は失敗する() {
        assertThat(service.verify(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT, "123456"))
                .isEqualTo(MfaEmailOtpService.VerifyOutcome.MISMATCHED);
        assertThat(stored(MfaEmailOtpPurpose.LOGIN, LOGIN_SUBJECT)).isNull();
    }

    @Test
    @DisplayName("別用途・別主体のハッシュとは一致しない（本人確認用の照合でログイン用の値は消さない）")
    void 別用途の値とは一致しない() {
        // 本人確認用のキーに、誤ってログイン用のハッシュが置かれていても一致しない（ハッシュに用途・主体を含む）
        redisTemplate.opsForValue().set(MfaEmailOtpKeys.codeKey(MfaEmailOtpPurpose.REAUTH, REAUTH_SUBJECT),
                ValkeyMfaEmailOtpService.hash(MfaEmailOtpPurpose.LOGIN, REAUTH_SUBJECT, "123456"));

        assertThat(service.verify(MfaEmailOtpPurpose.REAUTH, REAUTH_SUBJECT, "123456"))
                .isEqualTo(MfaEmailOtpService.VerifyOutcome.MISMATCHED);
        assertThat(stored(MfaEmailOtpPurpose.REAUTH, REAUTH_SUBJECT)).as("一致しない値は消さない").isNotNull();
    }
}

package com.mannschaft.app.common.architecture.plaintexttoken;

import com.mannschaft.app.common.token.BearerSecretValkeyKey;
import com.mannschaft.app.common.token.IssuedToken;
import com.mannschaft.app.common.token.PlaintextTokenDebt;
import com.mannschaft.app.common.token.SecretTokenVault;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * fixture（番人6 Valkey 側・AC-28 陽性）: 合言葉を Valkey に置くメソッド群。
 *
 * <p><b>実物と同じ形</b>にしてある: 接頭辞は {@code private static final String} のコンパイル時定数で、
 * javac が使用箇所へ値をインライン化するため、バイトコードには {@link #KEY_PREFIX} への
 * フィールド参照（GETSTATIC）が残らない。残るのは {@code BearerSecretValkeyKey.of} の INVOKESTATIC だけである
 * （本番の {@code PointCardShareTokenService}・{@code AuthOAuthLinkService} と同じ条件）。</p>
 *
 * <p>陽性: {@link #issueWithUuid(StringRedisTemplate)}、{@link #issueWithSecureRandom(StringRedisTemplate)}。
 * 陰性: {@link #issueViaVault(StringRedisTemplate, SecretTokenVault)}、
 * {@link #resolve(StringRedisTemplate, SecretTokenVault, String)}。
 * 免除: {@link #issueUnderDebt(StringRedisTemplate)}。</p>
 */
public class ValkeyBearerFixtureService {

    private static final String KEY_PREFIX = "fixture:bearer:";

    private static final Duration TTL = Duration.ofMinutes(5);

    private static final int RANDOM_BYTE_LENGTH = 32;

    private static final String STORED_VALUE = "owner";

    private final SecureRandom secureRandom = new SecureRandom();

    public String issueWithUuid(StringRedisTemplate redisTemplate) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(BearerSecretValkeyKey.of(KEY_PREFIX, token), STORED_VALUE, TTL);
        return token;
    }

    public String issueWithSecureRandom(StringRedisTemplate redisTemplate) {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        redisTemplate.opsForValue().set(BearerSecretValkeyKey.of(KEY_PREFIX, token), STORED_VALUE, TTL);
        return token;
    }

    @PlaintextTokenDebt(cmp = "CMP-260826-1457")
    public String issueUnderDebt(StringRedisTemplate redisTemplate) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(BearerSecretValkeyKey.of(KEY_PREFIX, token), STORED_VALUE, TTL);
        return token;
    }

    public String issueViaVault(StringRedisTemplate redisTemplate, SecretTokenVault vault) {
        IssuedToken issued = vault.issueBase64Url();
        redisTemplate.opsForValue().set(BearerSecretValkeyKey.of(KEY_PREFIX, issued.hash()), STORED_VALUE, TTL);
        return issued.plaintext();
    }

    public String resolve(StringRedisTemplate redisTemplate, SecretTokenVault vault, String token) {
        return redisTemplate.opsForValue().getAndDelete(BearerSecretValkeyKey.of(KEY_PREFIX, vault.hash(token)));
    }
}

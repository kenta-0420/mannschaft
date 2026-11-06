package com.mannschaft.app.auth.mfa;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.entity.TwoFactorAuthEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.Auth2faService;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 多要素認証(2FA)の穴埋め — 試練（red テスト）共通の土台。
 *
 * <p>方針（TEST_CONVENTION.md §3.1.2）: DB・認可・自前 Bean はモックしない。実 MySQL（Testcontainers）＋
 * {@code @AutoConfigureMockMvc} の実 Security フィルタ越しに叩く。外部境界の Valkey のみ
 * {@link MfaValkeyFake}（プロセス内代役）に差し替える。</p>
 *
 * <p>MFA セッションは<b>実際のログイン API（{@code POST /api/v1/auth/login}）</b>で発行させる
 * （トークンを Valkey に直接書かない。発行経路ごと検証するため）。</p>
 *
 * <p>本クラスのテストデータはコミットされる（並列試験で別スレッドから見えるよう、クラスに
 * {@code @Transactional} を付けない）。メールアドレスは UUID で一意化し他テストと干渉させない。</p>
 *
 * <p>足軽2（メールOTP）・足軽3（無効化/通知/回復）も本土台を継承してよい（各自のテストクラスは別に作ること）。</p>
 */
@AutoConfigureMockMvc
public abstract class MfaItSupport extends AbstractMySqlIntegrationTest {

    protected static final String PASSWORD = "Mfa-Passw0rd!2026";
    protected static final String USER_AGENT = "MfaIT/1.0 (CMP-261012-0910)";
    protected static final String LOGIN_PATH = "/api/v1/auth/login";
    protected static final String VALIDATE_PATH = "/api/v1/auth/2fa/validate";
    /** 現行実装・設計書（F01.1）の MFA セッションキー接頭辞。 */
    protected static final String MFA_SESSION_KEY_PREFIX = "mannschaft:auth:mfa_session_token:";
    /** 現行実装・設計書（F01.1 §TOTP コード検証フロー）の TOTP 使用済み印キー接頭辞。 */
    protected static final String TOTP_USED_KEY_PREFIX = "mannschaft:auth:totp_used:";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected TwoFactorAuthRepository twoFactorAuthRepository;
    @Autowired
    protected EncryptionService encryptionService;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected Auth2faService auth2faService;

    protected MfaValkeyFake valkey;

    /** 2FA 済みユーザーのフィクスチャ。 */
    public record MfaUser(long userId, String email, String totpSecret, List<String> backupCodes) {
    }

    /** 本テストが作ったユーザー（後始末用）。 */
    private final List<Long> ownedUserIds = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void installValkeyFake() {
        valkey = MfaValkeyFake.install(redisTemplate);
    }

    /**
     * 所有行の後始末（TEST_CONVENTION §3.1.2: commit したフィクスチャは所有行を cleanup する）。
     * users 行はメールアドレスを UUID で一意化しており他テストと干渉しないため残す
     * （ログイン経路の非同期リスナーが子行を作りうるため、users の物理削除は FK 次第で失敗する）。
     */
    @org.junit.jupiter.api.AfterEach
    void cleanUpOwnedRows() {
        for (Long userId : ownedUserIds) {
            jdbcTemplate.update("DELETE FROM two_factor_auth WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", userId);
        }
        ownedUserIds.clear();
    }

    // ─────────────────────────────────────────────────────────────
    // フィクスチャ
    // ─────────────────────────────────────────────────────────────

    /** 2FA 未設定のユーザーを作る。 */
    protected long createPlainUser() {
        String email = "mfa-it-" + UUID.randomUUID() + "@example.com";
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .lastName("多要素").firstName("試練").displayName("多要素 試練")
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone("Asia/Tokyo").isSearchable(true)
                .build();
        long id = userRepository.save(user).getId();
        ownedUserIds.add(id);
        return id;
    }

    /** TOTP 方式で 2FA 有効化済み・バックアップコード 8 件（Argon2id ハッシュ）のユーザー。 */
    protected MfaUser createTotpUser() {
        return createMfaUser(TwoFactorMethod.TOTP, 8, passwordEncoder::encode);
    }

    /** EMAIL 方式で 2FA 有効化済み・バックアップコード 8 件のユーザー（totp_secret は NULL）。 */
    protected MfaUser createEmailUser() {
        return createMfaUser(TwoFactorMethod.EMAIL, 8, passwordEncoder::encode);
    }

    /**
     * 2FA 有効化済みユーザーを作る。
     *
     * @param method      主手段
     * @param backupCount バックアップコード件数（0 なら {@code []}）
     * @param hasher      バックアップコードのハッシュ関数（Argon2id / 旧 BCrypt の切替用）
     */
    protected MfaUser createMfaUser(TwoFactorMethod method, int backupCount, Function<String, String> hasher) {
        String email = "mfa-it-" + UUID.randomUUID() + "@example.com";
        UserEntity user = UserEntity.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .lastName("多要素").firstName("試練").displayName("多要素 試練")
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone("Asia/Tokyo").isSearchable(true)
                .build();
        long userId = userRepository.save(user).getId();
        ownedUserIds.add(userId);

        String secret = method == TwoFactorMethod.TOTP ? randomBase32Secret() : null;
        List<String> codes = distinctBackupCodes(backupCount);
        List<String> hashes = codes.stream().map(hasher).toList();
        TwoFactorAuthEntity tfa = TwoFactorAuthEntity.builder()
                .userId(userId)
                .method(method)
                .totpSecret(secret == null ? null : encryptionService.encrypt(secret))
                .backupCodes(toJson(hashes))
                .isEnabled(true)
                .verifiedAt(LocalDateTime.now())
                .build();
        twoFactorAuthRepository.save(tfa);
        return new MfaUser(userId, email, secret, codes);
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────

    /** 実ログイン API を叩き、MFA セッショントークンを得る（2FA 要求であることも確認する）。 */
    protected String loginForMfaSession(MfaUser user) throws Exception {
        MvcResult result = login(user.email());
        assertThat(result.getResponse().getStatus())
                .as("ログイン（パスワード認証）は 200 で MFA 要求を返す: %s", body(result))
                .isEqualTo(200);
        JsonNode data = json(result).path("data");
        assertThat(data.path("mfaRequired").asBoolean()).as("mfaRequired").isTrue();
        String token = data.path("mfaSessionToken").asText(null);
        assertThat(token).as("mfaSessionToken").isNotBlank();
        return token;
    }

    protected MvcResult login(String email) throws Exception {
        return mockMvc.perform(post(LOGIN_PATH).servletPath(LOGIN_PATH)
                        .header("User-Agent", USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andReturn();
    }

    /** 新形式 {@code {mfaSessionToken, method, code}} で validate を叩く。 */
    protected MvcResult validate(String mfaSessionToken, String method, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mfaSessionToken", mfaSessionToken);
        if (method != null) {
            body.put("method", method);
        }
        body.put("code", code);
        return validateRaw(objectMapper.writeValueAsString(body));
    }

    protected MvcResult validateRaw(String jsonBody) throws Exception {
        return mockMvc.perform(post(VALIDATE_PATH).servletPath(VALIDATE_PATH)
                        .header("User-Agent", USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andReturn();
    }

    protected JsonNode json(MvcResult result) throws Exception {
        String body = body(result);
        return body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** エラー応答の {@code error.code}（無ければ null）。 */
    protected String errorCode(MvcResult result) throws Exception {
        return json(result).path("error").path("code").asText(null);
    }

    // ─────────────────────────────────────────────────────────────
    // TOTP
    // ─────────────────────────────────────────────────────────────

    /** 現在時刻の正しい TOTP コード。 */
    protected String currentTotp(String base32Secret) {
        return totpAt(base32Secret, Instant.now());
    }

    protected String totpAt(String base32Secret, Instant at) {
        try {
            TimeBasedOneTimePasswordGenerator totp = new TimeBasedOneTimePasswordGenerator(Duration.ofSeconds(30), 6);
            Key key = new SecretKeySpec(base32Decode(base32Secret), totp.getAlgorithm());
            return String.format("%06d", totp.generateOneTimePassword(key, at));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 現在 ±3 ウィンドウのどれとも一致しない 6 桁コード（確実に不正な TOTP）。 */
    protected String wrongTotp(String base32Secret) {
        Set<String> valid = new HashSet<>();
        Instant now = Instant.now();
        for (int w = -3; w <= 3; w++) {
            valid.add(totpAt(base32Secret, now.plusSeconds(30L * w)));
        }
        for (int i = 0; ; i++) {
            String candidate = String.format("%06d", (123_457 + i * 7_919) % 1_000_000);
            if (!valid.contains(candidate)) {
                return candidate;
            }
        }
    }

    /** 当該ユーザーのどのバックアップコードとも一致しない 8 桁コード。 */
    protected String wrongBackupCode(MfaUser user) {
        for (int i = 0; ; i++) {
            String candidate = String.format("%08d", (13_572_468 + i * 104_729) % 100_000_000);
            if (!user.backupCodes().contains(candidate)) {
                return candidate;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // DB 観測（JPA 一次キャッシュを介さず JDBC で確定状態を読む）
    // ─────────────────────────────────────────────────────────────

    protected int backupCodeCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT JSON_LENGTH(backup_codes) FROM two_factor_auth WHERE user_id = ?", Integer.class, userId);
        return n == null ? -1 : n;
    }

    protected String backupCodesJson(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT CAST(backup_codes AS CHAR) FROM two_factor_auth WHERE user_id = ?", String.class, userId);
    }

    protected int refreshTokenCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    // ─────────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────────

    private static List<String> distinctBackupCodes(int count) {
        Set<String> codes = new java.util.LinkedHashSet<>();
        while (codes.size() < count) {
            codes.add(String.format("%08d", RANDOM.nextInt(100_000_000)));
        }
        return new ArrayList<>(codes);
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomBase32Secret() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(BASE32.charAt(RANDOM.nextInt(32)));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String encoded) {
        String s = encoded.toUpperCase().replaceAll("[=\\s]", "");
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[s.length() * 5 / 8];
        int idx = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | BASE32.indexOf(c);
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[idx++] = (byte) (buffer >> (bitsLeft - 8));
                bitsLeft -= 8;
            }
        }
        return out;
    }
}

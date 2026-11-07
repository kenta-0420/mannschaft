package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練（足軽2・メールOTP）共通の土台。{@link MfaItSupport} を継承し、メールOTP 系 EP の呼び出しと
 * email_outbox の観測を足す（{@link MfaItSupport}・{@link MfaValkeyFake} は並行部隊と共有のため改変しない）。
 *
 * <h2>コードの取り出し方</h2>
 * <p>テストは平文コードを Valkey から読まない（Valkey にはハッシュしか置かない契約。AC-19）。
 * 送信 API の副作用として作られる {@code email_outbox} 行（{@code template_kind = 'MFA_EMAIL_OTP'}）の
 * 暗号化 payload を実 {@code EncryptionService} で復号して取り出す。enqueue が同期（送信 TX 内）でも
 * {@code @Async} でも観測できるよう最大 5 秒待つ。payload のキー名は出陣側の裁量とし、{@code code} キーが
 * あればそれを、無ければ payload 値の中の「前後が数字でない 6 桁」を最初の 1 つ採る。</p>
 *
 * <h2>IP</h2>
 * <p>メールOTP 送信には IP 単位の上限がある（AC-18/AC-20）。上限の実装がプロセス内（Valkey 代役の外）に
 * 状態を持っても他テストと干渉しないよう、テストごとに文書用ではない一意の IP（198.18.0.0/15）を割り当てる。</p>
 */
public abstract class MfaEmailOtpItSupport extends MfaItSupport {

    protected static final String EMAIL_SEND_PATH = "/api/v1/auth/2fa/email/send";
    protected static final String EMAIL_SETUP_PATH = "/api/v1/auth/2fa/email/setup";
    protected static final String EMAIL_ENABLE_PATH = "/api/v1/auth/2fa/email/enable";
    protected static final String REAUTH_SEND_PATH = "/api/v1/auth/2fa/reauth/email/send";
    protected static final String DISABLE_PATH = "/api/v1/auth/2fa/disable";
    protected static final String REGENERATE_PATH = "/api/v1/auth/2fa/backup-codes/regenerate";
    protected static final String OTP_TEMPLATE_KIND = "MFA_EMAIL_OTP";
    /** 後見切替（acting-as）ヘッダ。 */
    protected static final String HEADER_PROXY_FOR = "X-Proxy-For-User-Id";

    /** 設計諸元（軍議 設計判断 3）: OTP 有効期限 10 分・再送待ち 60 秒・セッション送信 5 回・ユーザー 10 回/時。 */
    protected static final Duration OTP_TTL = Duration.ofMinutes(10);
    protected static final Duration RESEND_WAIT = Duration.ofSeconds(60);
    protected static final int SESSION_SEND_LIMIT = 5;
    protected static final int USER_SEND_LIMIT_PER_HOUR = 10;

    /** ログイン用 OTP の Valkey キー名に含まれる語（軍議 設計判断 3: {@code mfa_email_otp:{sessionHash}}）。 */
    protected static final String LOGIN_OTP_KEY_MARK = "mfa_email_otp:";
    /** 設定用 OTP の Valkey キー名に含まれる語（軍議 設計判断 3: {@code mfa_email_setup:{userId}}）。 */
    protected static final String SETUP_OTP_KEY_MARK = "mfa_email_setup:";

    /** 内部プレースホルダ（＝メールなし）とみなされる接尾辞（GuardianshipHandoverService 等の既存規約）。 */
    protected static final String INTERNAL_EMAIL_SUFFIX = ".mannschaft.internal";

    private static final Pattern SIX_DIGITS = Pattern.compile("(?<![0-9])([0-9]{6})(?![0-9])");

    @Autowired
    protected AuthTokenService authTokenService;
    @Autowired
    protected ParentalConsentLinkRepository parentalConsentLinkRepository;

    /** このテストで使う送信元 IP。 */
    protected String ip;

    private final List<Long> trackedUserIds = new CopyOnWriteArrayList<>();

    /** 送信者 IP をテストごとに一意にする。 */
    @BeforeEach
    void assignFreshIp() {
        ip = freshIp();
    }

    /** 本クラスが作った email_outbox 行・保護者リンクを片付ける（users 行は親クラスの方針どおり残す）。 */
    @AfterEach
    void cleanUpEmailOtpRows() {
        for (Long userId : trackedUserIds) {
            jdbcTemplate.update("DELETE FROM email_outbox WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM parental_consent_links WHERE child_user_id = ? OR parent_user_id = ?",
                    userId, userId);
        }
        trackedUserIds.clear();
    }

    protected static String freshIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "198." + (18 + r.nextInt(2)) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    // ─────────────────────────────────────────────────────────────
    // フィクスチャ（作ったユーザーを追跡して outbox 行を後始末する）
    // ─────────────────────────────────────────────────────────────

    protected MfaUser totpUser() {
        return track(createTotpUser());
    }

    protected MfaUser emailUser() {
        return track(createEmailUser());
    }

    /** バックアップコード 0 件の TOTP ユーザー（Argon2id 計算を省いて大量に作るため）。 */
    protected MfaUser lightTotpUser() {
        return track(createMfaUser(TwoFactorMethod.TOTP, 0, passwordEncoder::encode));
    }

    protected long plainUser() {
        long id = createPlainUser();
        trackedUserIds.add(id);
        return id;
    }

    protected MfaUser track(MfaUser u) {
        trackedUserIds.add(u.userId());
        return u;
    }

    /** ユーザーのメールアドレスを書き換える（JPA 一次キャッシュを介さず確定させる）。 */
    protected void updateEmail(long userId, String email) {
        jdbcTemplate.update("UPDATE users SET email = ? WHERE id = ?", email, userId);
    }

    /** 「メールなし」ユーザーのアドレス（既存規約の内部プレースホルダ。非ルーティング）。 */
    protected static String placeholderEmail() {
        return "mfa-it-" + UUID.randomUUID() + "@noemail" + INTERNAL_EMAIL_SUFFIX;
    }

    /**
     * 後見切替（acting-as）の成立する保護者・子の組を作る（保護者リンク APPROVED・子は 8 歳・JP）。
     *
     * @return {保護者 userId, 子 userId}
     */
    protected long[] createGuardianAndChild() {
        long guardianId = plainUser();
        UserEntity child = UserEntity.builder()
                .email("mfa-it-child-" + UUID.randomUUID() + "@example.com")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .lastName("多要素").firstName("子").displayName("多要素 子")
                .status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone("Asia/Tokyo").isSearchable(false)
                .countryCode("JP")
                .birthDate(LocalDate.now().minusYears(8).toString())
                .build();
        long childId = userRepository.save(child).getId();
        trackedUserIds.add(childId);
        byte[] tokenHash = new byte[32];
        ThreadLocalRandom.current().nextBytes(tokenHash);
        parentalConsentLinkRepository.save(ParentalConsentLinkEntity.builder()
                .childUserId(childId)
                .parentUserId(guardianId)
                .parentEmail("mfa-it-guardian-" + UUID.randomUUID() + "@example.com")
                .tokenHash(HexFormat.of().formatHex(tokenHash))
                .status(ParentalConsentLinkStatus.APPROVED)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .approvedAt(LocalDateTime.now())
                .build());
        return new long[]{guardianId, childId};
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────

    /** {@code POST /2fa/email/send}（公開・本文で MFA セッショントークン）をこのテストの IP から叩く。 */
    protected MvcResult sendLoginOtp(String mfaSessionToken) throws Exception {
        return sendLoginOtp(mfaSessionToken, ip);
    }

    protected MvcResult sendLoginOtp(String mfaSessionToken, String fromIp) throws Exception {
        return mockMvc.perform(withIp(post(EMAIL_SEND_PATH).servletPath(EMAIL_SEND_PATH), fromIp)
                        .header("User-Agent", USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaSessionToken", mfaSessionToken))))
                .andReturn();
    }

    /** {@code POST /2fa/email/setup}（認証済み）。 */
    protected MvcResult startEmailSetup(long userId) throws Exception {
        return mockMvc.perform(withIp(post(EMAIL_SETUP_PATH).servletPath(EMAIL_SETUP_PATH), ip)
                        .with(user(String.valueOf(userId))))
                .andReturn();
    }

    /** {@code POST /2fa/email/enable {code}}（認証済み）。 */
    protected MvcResult enableEmail(long userId, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        return mockMvc.perform(withIp(post(EMAIL_ENABLE_PATH).servletPath(EMAIL_ENABLE_PATH), ip)
                        .with(user(String.valueOf(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** {@code POST /2fa/reauth/email/send}（認証済み）。 */
    protected MvcResult sendReauthOtp(long userId) throws Exception {
        return mockMvc.perform(withIp(post(REAUTH_SEND_PATH).servletPath(REAUTH_SEND_PATH), ip)
                        .with(user(String.valueOf(userId))))
                .andReturn();
    }

    /** {@code POST /2fa/disable}（認証済み）。 */
    protected MvcResult disable(long userId, String currentPassword, String method, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentPassword", currentPassword);
        body.put("method", method);
        body.put("code", code);
        return mockMvc.perform(withIp(post(DISABLE_PATH).servletPath(DISABLE_PATH), ip)
                        .with(user(String.valueOf(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** {@code POST /2fa/backup-codes/regenerate}（認証済み・要素で本人確認）。 */
    protected MvcResult regenerate(long userId, String method, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", method);
        body.put("code", code);
        return mockMvc.perform(withIp(post(REGENERATE_PATH).servletPath(REGENERATE_PATH), ip)
                        .with(user(String.valueOf(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** 実ログイン API でこのテストの IP から MFA セッションを得る。 */
    protected String mfaSession(MfaUser u) throws Exception {
        return loginForMfaSession(u);
    }

    protected static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder b, String fromIp) {
        return b.with(req -> {
            req.setRemoteAddr(fromIp);
            return req;
        });
    }

    protected String retryAfter(MvcResult r) {
        return r.getResponse().getHeader("Retry-After");
    }

    // ─────────────────────────────────────────────────────────────
    // email_outbox 観測
    // ─────────────────────────────────────────────────────────────

    /** email_outbox の MFA_EMAIL_OTP 行（復号済み）。 */
    public record OtpOutboxRow(UUID id, String toAddress, Map<String, String> payload, String status,
                               String sourceEventId, String idempotencyKey, String locale) {
    }

    /** 当該ユーザーの MFA_EMAIL_OTP 行を作成順（UUIDv7 順）で返す。 */
    protected List<OtpOutboxRow> otpOutboxRows(long userId) {
        return jdbcTemplate.query(
                "SELECT id, to_address, payload_json, status, source_event_id, idempotency_key, locale "
                        + "FROM email_outbox WHERE user_id = ? AND template_kind = ? ORDER BY id",
                (rs, i) -> new OtpOutboxRow(
                        toUuid(rs.getBytes("id")),
                        new String(encryptionService.decryptBytes(rs.getBytes("to_address")), StandardCharsets.UTF_8),
                        decodePayload(rs.getBytes("payload_json")),
                        rs.getString("status"),
                        rs.getString("source_event_id"),
                        rs.getString("idempotency_key"),
                        rs.getString("locale")),
                userId, OTP_TEMPLATE_KIND);
    }

    protected int otpOutboxCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE user_id = ? AND template_kind = ?",
                Integer.class, userId, OTP_TEMPLATE_KIND);
        return n == null ? 0 : n;
    }

    /** MFA_EMAIL_OTP 行が {@code atLeast} 件になるまで最大 5 秒待ち、全行を返す。 */
    protected List<OtpOutboxRow> awaitOtpOutboxRows(long userId, int atLeast) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(otpOutboxCount(userId))
                        .as("email_outbox に MFA_EMAIL_OTP 行が %d 件以上（user_id=%d）", atLeast, userId)
                        .isGreaterThanOrEqualTo(atLeast));
        return otpOutboxRows(userId);
    }

    /** outbox 行の payload から 6 桁コードを取り出す。 */
    protected static String codeOf(OtpOutboxRow row) {
        String explicit = row.payload().get("code");
        if (explicit != null && explicit.matches("\\d{6}")) {
            return explicit;
        }
        for (String v : row.payload().values()) {
            if (v == null) {
                continue;
            }
            Matcher m = SIX_DIGITS.matcher(v);
            if (m.find()) {
                return m.group(1);
            }
        }
        throw new AssertionError("MFA_EMAIL_OTP の payload に 6 桁コードが無い: keys=" + row.payload().keySet());
    }

    /**
     * 送信 API を呼んだ直後に使う: 送信前 {@code before} 件から 1 件増えた最新行のコードを返す。
     */
    protected String awaitNewCode(long userId, int before) {
        List<OtpOutboxRow> rows = awaitOtpOutboxRows(userId, before + 1);
        return codeOf(rows.get(rows.size() - 1));
    }

    /** ログイン用 OTP を送って 200 を確認し、届いたコードを返す。 */
    protected String sendLoginOtpAndGetCode(MfaUser u, String session) throws Exception {
        int before = otpOutboxCount(u.userId());
        MvcResult r = sendLoginOtp(session);
        assertThat(r.getResponse().getStatus()).as("前提: ログイン用メールOTP 送信 200: %s", body(r)).isEqualTo(200);
        return awaitNewCode(u.userId(), before);
    }

    /** 設定用 OTP を送って 200 を確認し、届いたコードを返す。 */
    protected String startSetupAndGetCode(long userId) throws Exception {
        int before = otpOutboxCount(userId);
        MvcResult r = startEmailSetup(userId);
        assertThat(r.getResponse().getStatus()).as("前提: 設定用メールOTP 送信 200: %s", body(r)).isEqualTo(200);
        return awaitNewCode(userId, before);
    }

    /** 本人確認用 OTP を送って 200 を確認し、届いたコードを返す。 */
    protected String sendReauthAndGetCode(long userId) throws Exception {
        int before = otpOutboxCount(userId);
        MvcResult r = sendReauthOtp(userId);
        assertThat(r.getResponse().getStatus()).as("前提: 本人確認用メールOTP 送信 200: %s", body(r)).isEqualTo(200);
        return awaitNewCode(userId, before);
    }

    /** {@code code} と異なる 6 桁コード。 */
    protected static String otherCode(String code) {
        int n = Integer.parseInt(code);
        return String.format("%06d", (n + 314_159) % 1_000_000);
    }

    // ─────────────────────────────────────────────────────────────
    // Valkey / DB 観測
    // ─────────────────────────────────────────────────────────────

    protected String sessionKey(String mfaSessionToken) {
        return MFA_SESSION_KEY_PREFIX + authTokenService.hashToken(mfaSessionToken);
    }

    protected Map<String, Object> twoFactorRow(long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT method, is_enabled, totp_secret, JSON_LENGTH(backup_codes) AS backup_count "
                        + "FROM two_factor_auth WHERE user_id = ?", userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** two_factor_auth 行の is_enabled（TINYINT(1) は JDBC で Boolean/Number のどちらでも来うる）。 */
    protected static boolean enabled(Map<String, Object> row) {
        Object v = row.get("is_enabled");
        if (v instanceof Boolean b) {
            return b;
        }
        return v instanceof Number n && n.intValue() != 0;
    }

    /** 2FA が有効化されていない（行なし、または未有効行）。 */
    protected boolean notEnabled(long userId) {
        Map<String, Object> row = twoFactorRow(userId);
        return row == null || !enabled(row);
    }

    protected List<String> texts(com.fasterxml.jackson.databind.JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private Map<String, String> decodePayload(byte[] encrypted) {
        if (encrypted == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(encryptionService.decryptBytes(encrypted),
                    new TypeReference<Map<String, String>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("email_outbox.payload_json の復号・解析に失敗", e);
        }
    }

    private static UUID toUuid(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        return new UUID(bb.getLong(), bb.getLong());
    }
}

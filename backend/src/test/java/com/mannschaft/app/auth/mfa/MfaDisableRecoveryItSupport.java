package com.mannschaft.app.auth.mfa;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261012-0910 試練（足軽3: 無効化・通知・回復）共通の追加土台。
 *
 * <p>{@link MfaItSupport}（足軽1 の土台・改変禁止）を継承し、無効化・再生成・回復・通知の観測ヘルパーを足す。
 * 方針は土台と同じ（TEST_CONVENTION.md §3.1.2）: 実 MySQL＋実 Security フィルタ越し。外部境界の Valkey のみ
 * {@link MfaValkeyFake}。メールは実送信の手前、{@code email_outbox} 行（暗号化 payload を実
 * {@code EncryptionService} で復号）で観測する。監査は {@code audit_logs} を JDBC で読む（@Async のため待つ）。</p>
 *
 * <h2>実装側への観測契約（出陣の足軽へ）</h2>
 * <ul>
 *   <li>通知メール: {@code email_outbox.template_kind}＝{@code MFA_SECURITY_NOTICE} / {@code MFA_RECOVERY} /
 *       {@code MFA_EMAIL_OTP}、{@code user_id}＝宛先ユーザー。</li>
 *   <li>回復メールの payload のどれかの値に、平文回復トークン（{@code token=...} クエリ、または
 *       UUID 形式の素のトークン）が含まれること。</li>
 *   <li>本人確認メールOTP の payload のどれかの値に 6 桁コードが含まれること。</li>
 *   <li>監査 metadata（JSON）: MFA_ENABLED / MFA_DISABLED は {@code $.method}、MFA_DISABLED は {@code $.reason}
 *       （{@code USER} / {@code RECOVERY}）。</li>
 *   <li>メールOTP のコード保持キーは名前に {@code mfa_email} を含み、本人確認・設定用はユーザー ID で終わる
 *       （設計: {@code mfa_email_setup:{userId}}。本人確認用も同形）。送信回数等のレート制限キーは名前に
 *       {@code rate / count / limit / resend / cooldown / sent} のいずれかを含めて区別する。</li>
 * </ul>
 */
public abstract class MfaDisableRecoveryItSupport extends MfaItSupport {

    protected static final String DISABLE_PATH = "/api/v1/auth/2fa/disable";
    protected static final String REGENERATE_PATH = "/api/v1/auth/2fa/backup-codes/regenerate";
    protected static final String REAUTH_SEND_PATH = "/api/v1/auth/2fa/reauth/email/send";
    protected static final String STATUS_PATH = "/api/v1/auth/2fa/status";
    protected static final String SETUP_PATH = "/api/v1/auth/2fa/setup";
    protected static final String VERIFY_PATH = "/api/v1/auth/2fa/verify";
    protected static final String RECOVERY_REQUEST_PATH = "/api/v1/auth/2fa/recovery/request";
    protected static final String RECOVERY_CONFIRM_PATH = "/api/v1/auth/2fa/recovery/confirm";
    protected static final String REFRESH_PATH = "/api/v1/auth/refresh";
    protected static final String USER_INVALIDATED_KEY_PREFIX = "mannschaft:auth:user_invalidated_at:";
    protected static final String PROXY_FOR_HEADER = "X-Proxy-For-User-Id";

    private static final Pattern TOKEN_QUERY = Pattern.compile("token=([A-Za-z0-9_\\-]+)");
    private static final Pattern UUID_LIKE =
            Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern SIX_DIGITS = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");

    @Autowired
    protected ParentalConsentLinkRepository parentalConsentLinkRepository;

    /** 本クラス経由で作ったユーザー（追加テーブルの後始末用）。 */
    private final List<Long> myUserIds = new CopyOnWriteArrayList<>();

    @Override
    protected long createPlainUser() {
        long id = super.createPlainUser();
        myUserIds.add(id);
        return id;
    }

    @Override
    protected MfaUser createMfaUser(TwoFactorMethod method, int backupCount, Function<String, String> hasher) {
        MfaUser u = super.createMfaUser(method, backupCount, hasher);
        myUserIds.add(u.userId());
        return u;
    }

    /** 土台が消さない所有行（回復トークン・outbox・監査・保護者リンク）の後始末。 */
    @AfterEach
    void cleanUpMyRows() {
        for (Long userId : myUserIds) {
            jdbcTemplate.update("DELETE FROM mfa_recovery_tokens WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM email_outbox WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM audit_logs WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM parental_consent_links WHERE child_user_id = ? OR parent_user_id = ?",
                    userId, userId);
            jdbcTemplate.update("DELETE FROM webauthn_credentials WHERE user_id = ?", userId);
        }
        myUserIds.clear();
    }

    // ─────────────────────────────────────────────────────────────
    // フィクスチャ
    // ─────────────────────────────────────────────────────────────

    /** パスワード未設定（OAuth 専用等。hasPassword=false）にする。 */
    protected void removePassword(long userId) {
        jdbcTemplate.update("UPDATE users SET password_hash = NULL WHERE id = ?", userId);
    }

    /**
     * {@code childUserId} を後見切替（acting-as）できる保護者ユーザーを作る。
     *
     * <p>F08.9 の実判定（{@code GuardianshipSwitchService#evaluateSwitch}: APPROVED の保護者リンク＋
     * JP 年齢ゲートで小学生以下）を実データで満たす。子は満 8 歳・country_code=JP にする。</p>
     *
     * @return 保護者ユーザー ID
     */
    protected long createGuardianActingAs(long childUserId) {
        UserEntity child = userRepository.findById(childUserId).orElseThrow();
        userRepository.save(child.toBuilder()
                .birthDate(LocalDate.now().minusYears(8).toString())
                .countryCode("JP")
                .build());
        long guardianId = createPlainUser();
        String guardianEmail = userRepository.findById(guardianId).orElseThrow().getEmail();
        parentalConsentLinkRepository.save(ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentUserId(guardianId)
                .parentEmail(guardianEmail)
                .tokenHash(UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .status(ParentalConsentLinkStatus.APPROVED)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .approvedAt(LocalDateTime.now())
                .build());
        return guardianId;
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP（認証済み）
    // ─────────────────────────────────────────────────────────────

    protected MvcResult disable(long userId, String currentPassword, String method, String code) throws Exception {
        return mockMvc.perform(post(DISABLE_PATH).servletPath(DISABLE_PATH)
                        .with(user(String.valueOf(userId)))
                        .header("User-Agent", USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(factorBody(currentPassword, method, code))))
                .andReturn();
    }

    /** 後見切替ヘッダ付き（保護者 {@code guardianId} が子 {@code childId} として操作する）で無効化を叩く。 */
    protected MvcResult disableActingAs(long guardianId, long childId, String currentPassword, String method,
                                        String code) throws Exception {
        return mockMvc.perform(post(DISABLE_PATH).servletPath(DISABLE_PATH)
                        .with(user(String.valueOf(guardianId)))
                        .header(PROXY_FOR_HEADER, String.valueOf(childId))
                        .header("User-Agent", USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(factorBody(currentPassword, method, code))))
                .andReturn();
    }

    /** 本文 {@code body}（null なら本文なし）で再生成を叩く。 */
    protected MvcResult regenerate(long userId, Map<String, Object> body) throws Exception {
        var req = post(REGENERATE_PATH).servletPath(REGENERATE_PATH)
                .with(user(String.valueOf(userId)))
                .header("User-Agent", USER_AGENT);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(req).andReturn();
    }

    protected MvcResult regenerateActingAs(long guardianId, long childId, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(REGENERATE_PATH).servletPath(REGENERATE_PATH)
                        .with(user(String.valueOf(guardianId)))
                        .header(PROXY_FOR_HEADER, String.valueOf(childId))
                        .header("User-Agent", USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    protected MvcResult status(long userId) throws Exception {
        return mockMvc.perform(get(STATUS_PATH).servletPath(STATUS_PATH).with(user(String.valueOf(userId))))
                .andReturn();
    }

    /** TOTP 設定開始（実 API）→ 返った秘密鍵。 */
    protected String setupTotpViaApi(long userId) throws Exception {
        MvcResult r = mockMvc.perform(post(SETUP_PATH).servletPath(SETUP_PATH)
                        .with(user(String.valueOf(userId)))
                        .header("User-Agent", USER_AGENT))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("前提: TOTP 設定開始: %s", body(r)).isEqualTo(201);
        String secret = json(r).path("data").path("secret").asText(null);
        assertThat(secret).as("前提: 秘密鍵").isNotBlank();
        return secret;
    }

    /** TOTP 設定確定（実 API）。 */
    protected MvcResult verifyTotpViaApi(long userId, String code) throws Exception {
        return mockMvc.perform(post(VERIFY_PATH).servletPath(VERIFY_PATH)
                        .with(user(String.valueOf(userId)))
                        .header("User-Agent", USER_AGENT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("totpCode", code))))
                .andReturn();
    }

    /** 本人確認用メールOTP を送らせ、outbox から 6 桁コードを取り出す（AC-51 の API を利用）。 */
    protected String sendReauthOtpAndReadCode(long userId) throws Exception {
        int before = outboxCount(userId, "MFA_EMAIL_OTP");
        MvcResult r = mockMvc.perform(post(REAUTH_SEND_PATH).servletPath(REAUTH_SEND_PATH)
                        .with(user(String.valueOf(userId)))
                        .header("User-Agent", USER_AGENT))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("前提: 本人確認OTP 送信: %s", body(r)).isEqualTo(200);
        List<Map<String, String>> payloads = awaitOutboxPayloads(userId, "MFA_EMAIL_OTP", before + 1);
        Map<String, String> latest = payloads.get(payloads.size() - 1);
        for (String v : latest.values()) {
            Matcher m = SIX_DIGITS.matcher(v == null ? "" : v);
            if (m.find()) {
                return m.group(1);
            }
        }
        throw new AssertionError("MFA_EMAIL_OTP の payload に 6 桁コードが無い: keys=" + latest.keySet());
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP（回復・公開）
    // ─────────────────────────────────────────────────────────────

    /**
     * 回復メール要求。現行契約（クエリ）と仕様書 F01.1（本文）の両方で mfaSessionToken を渡す
     * （どちらで受ける実装でも同じ試験が成立するように）。
     */
    protected MvcResult requestRecovery(String mfaSessionToken) throws Exception {
        return mockMvc.perform(post(RECOVERY_REQUEST_PATH).servletPath(RECOVERY_REQUEST_PATH)
                        .header("User-Agent", USER_AGENT)
                        .param("mfaSessionToken", mfaSessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("mfaSessionToken", mfaSessionToken))))
                .andReturn();
    }

    /** 回復確認（メールリンクから。認証・MFA セッションなし）。クエリと本文の両方でトークンを渡す。 */
    protected MvcResult confirmRecovery(String token) throws Exception {
        return mockMvc.perform(post(RECOVERY_CONFIRM_PATH).servletPath(RECOVERY_CONFIRM_PATH)
                        .header("User-Agent", USER_AGENT)
                        .param("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", token))))
                .andReturn();
    }

    /** 回復メールを要求し、outbox の回復メールから平文トークンを取り出す。 */
    protected String requestRecoveryAndReadToken(MfaUser u, String mfaSessionToken) throws Exception {
        int before = outboxCount(u.userId(), "MFA_RECOVERY");
        MvcResult r = requestRecovery(mfaSessionToken);
        assertThat(r.getResponse().getStatus()).as("前提: 回復メール要求: %s", body(r)).isBetween(200, 202);
        List<Map<String, String>> payloads = awaitOutboxPayloads(u.userId(), "MFA_RECOVERY", before + 1);
        return extractRecoveryToken(payloads.get(payloads.size() - 1));
    }

    protected String extractRecoveryToken(Map<String, String> payload) {
        for (String v : payload.values()) {
            Matcher m = TOKEN_QUERY.matcher(v == null ? "" : v);
            if (m.find()) {
                return m.group(1);
            }
        }
        for (String v : payload.values()) {
            Matcher m = UUID_LIKE.matcher(v == null ? "" : v);
            if (m.find()) {
                return m.group();
            }
        }
        throw new AssertionError("MFA_RECOVERY の payload に回復トークンが無い: keys=" + payload.keySet());
    }

    /** POST /auth/refresh を refresh_token Cookie で叩く。 */
    protected MvcResult refreshWith(String rawRefreshToken) throws Exception {
        return mockMvc.perform(post(REFRESH_PATH).servletPath(REFRESH_PATH)
                        .header("User-Agent", USER_AGENT)
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", rawRefreshToken)))
                .andReturn();
    }

    protected List<String> setCookies(MvcResult r) {
        return new ArrayList<>(r.getResponse().getHeaders("Set-Cookie"));
    }

    /** Set-Cookie から指定 Cookie の値（無ければ null）。 */
    protected String cookieValue(MvcResult r, String name) {
        for (String h : setCookies(r)) {
            if (h.startsWith(name + "=")) {
                String v = h.substring(name.length() + 1);
                int semi = v.indexOf(';');
                return semi < 0 ? v : v.substring(0, semi);
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // 観測（DB）
    // ─────────────────────────────────────────────────────────────

    protected int twoFactorRowCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM two_factor_auth WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    protected boolean twoFactorEnabled(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM two_factor_auth WHERE user_id = ? AND is_enabled = 1", Integer.class, userId);
        return n != null && n > 0;
    }

    /** 秘密鍵・有効フラグ・バックアップコードのどれかが残っている行の数（「完全に消えた」判定用）。 */
    protected int residualSecretRowCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM two_factor_auth WHERE user_id = ? AND (is_enabled = 1 OR totp_secret IS NOT NULL"
                        + " OR (backup_codes IS NOT NULL AND JSON_LENGTH(backup_codes) > 0))",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    protected int activeRefreshTokenCount(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, userId);
        return n == null ? 0 : n;
    }

    protected int outboxCount(long userId, String templateKind) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE user_id = ? AND template_kind = ?",
                Integer.class, userId, templateKind);
        return n == null ? 0 : n;
    }

    /** outbox に {@code templateKind} の行が {@code atLeast} 件以上たまるまで待ち、各行の復号済み payload を古い順に返す。 */
    protected List<Map<String, String>> awaitOutboxPayloads(long userId, String templateKind, int atLeast) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outboxCount(userId, templateKind))
                        .as("email_outbox に %s（user_id=%d）が %d 件以上", templateKind, userId, atLeast)
                        .isGreaterThanOrEqualTo(atLeast));
        List<byte[]> rows = jdbcTemplate.query(
                "SELECT payload_json FROM email_outbox WHERE user_id = ? AND template_kind = ? ORDER BY created_at, id",
                (rs, i) -> rs.getBytes(1), userId, templateKind);
        List<Map<String, String>> out = new ArrayList<>();
        for (byte[] enc : rows) {
            out.add(decryptPayload(enc));
        }
        return out;
    }

    /** outbox の宛先（復号）を古い順に返す。 */
    protected List<String> outboxRecipients(long userId, String templateKind) {
        List<byte[]> rows = jdbcTemplate.query(
                "SELECT to_address FROM email_outbox WHERE user_id = ? AND template_kind = ? ORDER BY created_at, id",
                (rs, i) -> rs.getBytes(1), userId, templateKind);
        List<String> out = new ArrayList<>();
        for (byte[] enc : rows) {
            out.add(enc == null ? null : new String(encryptionService.decryptBytes(enc), StandardCharsets.UTF_8));
        }
        return out;
    }

    private Map<String, String> decryptPayload(byte[] encrypted) {
        if (encrypted == null) {
            return Map.of();
        }
        try {
            byte[] plain = encryptionService.decryptBytes(encrypted);
            return objectMapper.readValue(plain, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("email_outbox.payload_json の復号に失敗", e);
        }
    }

    protected int auditCount(long userId, String eventType) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND event_type = ?",
                Integer.class, userId, eventType);
        return n == null ? 0 : n;
    }

    /** audit_logs に {@code eventType} が {@code atLeast} 件以上たまるまで待つ（@Async 記録）。 */
    protected void awaitAudit(long userId, String eventType, int atLeast) {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(auditCount(userId, eventType))
                        .as("audit_logs に %s（user_id=%d）が %d 件以上", eventType, userId, atLeast)
                        .isGreaterThanOrEqualTo(atLeast));
    }

    /** 最新の {@code eventType} 監査行の metadata から JSON パスの値を読む（無ければ null）。 */
    protected String latestAuditMetadata(long userId, String eventType, String jsonPath) {
        List<String> v = jdbcTemplate.query(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata, ?)) FROM audit_logs WHERE user_id = ? AND event_type = ?"
                        + " ORDER BY id DESC LIMIT 1",
                (rs, i) -> rs.getString(1), jsonPath, userId, eventType);
        return v.isEmpty() ? null : v.get(0);
    }

    // ─────────────────────────────────────────────────────────────
    // 観測（Valkey）
    // ─────────────────────────────────────────────────────────────

    /** 当該ユーザーの本人確認・設定用メールOTP のコード保持キー（レート制限キーは除く）。 */
    protected java.util.Set<String> emailOtpCodeKeys(long userId) {
        java.util.Set<String> keys = new java.util.HashSet<>(valkey.liveKeysMatching("mfa_email.*:" + userId + "$"));
        keys.removeIf(k -> k.matches("(?i).*(rate|count|limit|resend|cooldown|sent).*"));
        return keys;
    }

    // ─────────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────────

    protected static Map<String, Object> factorBody(String currentPassword, String method, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (currentPassword != null) {
            body.put("currentPassword", currentPassword);
        }
        if (method != null) {
            body.put("method", method);
        }
        if (code != null) {
            body.put("code", code);
        }
        return body;
    }

    /** 現在の次ウィンドウ（+30 秒）の TOTP。±1 ウィンドウ許容のため有効で、現在コードの使用済み印と衝突しない。 */
    protected String nextWindowTotp(String base32Secret) {
        return totpAt(base32Secret, java.time.Instant.now().plusSeconds(30));
    }
}

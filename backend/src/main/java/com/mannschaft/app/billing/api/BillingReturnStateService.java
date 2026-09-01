package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import lombok.RequiredArgsConstructor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * PR4 署名付き return state（BC-16 / BC-28）。
 *
 * <p>token 形式は {@code {kid}.{payload}.{signature}}。payload は purpose / scope / actor / tab /
 * quoteId / sessionId / billingCustomerId / iat / exp と nonce の <b>ハッシュ</b> のみを含み、
 * nonce 平文は token に載せない（token が漏えいしても CAS 消費を突破できないようにするため）。
 * 検証失敗はすべて {@link BillingReturnStateException} に畳み、token や scope を露出しない。
 */
@RequiredArgsConstructor
public class BillingReturnStateService {
    public enum Purpose { CHECKOUT_SUCCESS, CHECKOUT_CANCEL, PORTAL_RETURN, PAYMENT_ACTION_RETURN }

    public record ReturnState(Purpose purpose, EntitlementScopeKind scopeKind, long scopeId,
                              long actorId, String tab, UUID quoteId, String sessionId,
                              UUID billingCustomerId, Instant issuedAt, Instant expiresAt,
                              String nonce) { }

    /** 発行から exp までの上限（BC-16: Checkout Session expiry + 15分でも 24 時間を超えさせない）。 */
    private static final long MAX_LIFETIME_SECONDS = 86_400L;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String FIELD_SEPARATOR = "|";
    private static final int NONCE_HASH_LENGTH = 64;
    private static final int PAYLOAD_FIELD_COUNT = 11;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final Clock clock;
    private final BillingReturnSigningKeyProvider signingKeyProvider;
    private final BillingReturnStateNonceRepository nonceRepository;

    /**
     * state を現行鍵で署名し、nonce をハッシュだけ台帳へ登録する。
     *
     * @param state 発行対象の state（nonce は平文）
     * @return {@code {kid}.{payload}.{signature}} 形式の token
     * @throws BillingReturnStateException 有効期限が発行から 24 時間の上限を超える場合
     */
    public String issue(ReturnState state) {
        BillingReturnSigningKeyProvider.SigningKey key = signingKeyProvider.activeKey();
        Instant issuedAt = clock.instant();
        if (state.expiresAt() == null
                || state.expiresAt().isAfter(issuedAt.plusSeconds(MAX_LIFETIME_SECONDS))) {
            throw new BillingReturnStateException();
        }
        String nonceHash = hashNonce(state.nonce());
        String payload = encodePayload(state, nonceHash);
        String token = key.kid() + "." + payload + "." + sign(key, key.kid(), payload);
        nonceRepository.register(nonceHash, state.purpose(), state.actorId(),
                state.scopeKind(), state.scopeId(), state.expiresAt());
        return token;
    }

    /**
     * token を検証して state を復元する。kid で旧鍵も検索し rotation 中の token を受理する。
     *
     * @param signedState 受領した token
     * @param expectedPurpose 期待する purpose
     * @return 復元した state（nonce フィールドにはハッシュが入る）
     * @throws BillingReturnStateException 署名不正・未知 kid・改竄・期限切れ・purpose 不一致
     */
    public ReturnState verify(String signedState, Purpose expectedPurpose) {
        if (signedState == null) {
            throw new BillingReturnStateException();
        }
        String[] parts = signedState.split("\\.", -1);
        if (parts.length != 3) {
            throw new BillingReturnStateException();
        }
        BillingReturnSigningKeyProvider.SigningKey key = resolveKey(parts[0]);
        String expected = sign(key, parts[0], parts[1]);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new BillingReturnStateException();
        }
        ReturnState state = decodePayload(parts[1]);
        if (state.purpose() != expectedPurpose || !state.expiresAt().isAfter(clock.instant())) {
            throw new BillingReturnStateException();
        }
        return state;
    }

    /**
     * nonce を purpose / actor / scope / hash すべてを条件に一度だけ CAS 消費する。
     *
     * @param state 検証済み state
     * @param actorId 実際に callback を踏んだ actor
     * @throws BillingReturnStateException 既消費・期限切れ・束縛不一致で 1 行更新できなかった場合
     */
    public void consumeNonce(ReturnState state, long actorId) {
        int consumed = nonceRepository.consumeIfValid(hashNonce(state.nonce()), state.purpose(), actorId,
                state.scopeKind(), state.scopeId(), clock.instant());
        if (consumed != 1) {
            throw new BillingReturnStateException();
        }
    }

    private BillingReturnSigningKeyProvider.SigningKey resolveKey(String kid) {
        Optional<BillingReturnSigningKeyProvider.SigningKey> found = signingKeyProvider.findByKid(kid);
        if (found != null && found.isPresent()) {
            return found.get();
        }
        BillingReturnSigningKeyProvider.SigningKey active = signingKeyProvider.activeKey();
        if (active != null && active.kid().equals(kid)) {
            return active;
        }
        throw new BillingReturnStateException();
    }

    private String encodePayload(ReturnState state, String nonceHash) {
        String raw = String.join(FIELD_SEPARATOR,
                state.purpose().name(),
                state.scopeKind().name(),
                Long.toString(state.scopeId()),
                Long.toString(state.actorId()),
                nullSafe(state.tab()),
                nullSafe(state.quoteId()),
                nullSafe(state.sessionId()),
                nullSafe(state.billingCustomerId()),
                Long.toString(state.issuedAt().getEpochSecond()),
                Long.toString(state.expiresAt().getEpochSecond()),
                nonceHash);
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private ReturnState decodePayload(String payload) {
        String raw;
        try {
            raw = new String(DECODER.decode(payload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BillingReturnStateException();
        }
        String[] fields = raw.split("\\" + FIELD_SEPARATOR, -1);
        if (fields.length != PAYLOAD_FIELD_COUNT) {
            throw new BillingReturnStateException();
        }
        try {
            return new ReturnState(Purpose.valueOf(fields[0]), EntitlementScopeKind.valueOf(fields[1]),
                    Long.parseLong(fields[2]), Long.parseLong(fields[3]), emptyToNull(fields[4]),
                    fields[5].isEmpty() ? null : UUID.fromString(fields[5]), emptyToNull(fields[6]),
                    fields[7].isEmpty() ? null : UUID.fromString(fields[7]),
                    Instant.ofEpochSecond(Long.parseLong(fields[8])),
                    Instant.ofEpochSecond(Long.parseLong(fields[9])), fields[10]);
        } catch (IllegalArgumentException e) {
            throw new BillingReturnStateException();
        }
    }

    /**
     * nonce のハッシュ値（HMAC-SHA256 hex 64 文字）を得る。台帳に平文を残さないだけでなく、
     * 現行署名鍵で keyed にすることで DB 側の値から nonce を逆算・総当りされないようにする。
     * verify 由来の state は既にハッシュを保持しているためそのまま用いる
     * （token に平文が載らないため再ハッシュはできない）。
     */
    private String hashNonce(String nonce) {
        if (nonce == null) {
            throw new BillingReturnStateException();
        }
        if (isNonceHash(nonce)) {
            return nonce;
        }
        BillingReturnSigningKeyProvider.SigningKey key = signingKeyProvider.activeKey();
        if (key == null) {
            throw new BillingReturnStateException();
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.secret(), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(nonce.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private boolean isNonceHash(String value) {
        if (value.length() != NONCE_HASH_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private String sign(BillingReturnSigningKeyProvider.SigningKey key, String kid, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.secret(), HMAC_ALGORITHM));
            byte[] signature = mac.doFinal((kid + "." + payload).getBytes(StandardCharsets.UTF_8));
            return ENCODER.encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}

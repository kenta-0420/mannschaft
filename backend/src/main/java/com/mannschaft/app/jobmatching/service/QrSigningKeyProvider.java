package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.jobmatching.config.QrSigningProperties;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * F13.1 Phase 13.1.2 — QR トークン署名鍵プロバイダ。
 *
 * <p>{@link QrSigningProperties#getSigningKeys()} に並ぶ全鍵を HMAC-SHA256 用
 * {@link SecretKey} に変換してキャッシュし、以下の用途で提供する:</p>
 * <ul>
 *   <li>{@link #current()} — 新規発行用。{@code active=true} かつリスト上で最新の鍵を返す</li>
 *   <li>{@link #find(String)} — 検証用。全鍵（旧鍵含む）を {@code kid} で解決する</li>
 * </ul>
 *
 * <p>鍵は UTF-8 バイト列で 32 bytes 以上必須（{@link Keys#hmacShaKeyFor(byte[])} 要件）。
 * 未満の場合は Spring Boot 起動時に {@link IllegalStateException} でアプリケーションを失敗させる。</p>
 *
 * <p>設計書 §10.10（鍵ローテーション）に従い、新鍵投入時は旧 {@code active=false}
 * の鍵も保持することで、発行済みトークンの検証が TTL 経過まで継続可能となる。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QrSigningKeyProvider {

    /** HMAC-SHA256 最小鍵長（バイト）。{@link Keys#hmacShaKeyFor(byte[])} の要件と一致。 */
    private static final int MIN_KEY_BYTES = 32;

    private final QrSigningProperties properties;

    /** {@code kid} → {@link SecretKey} キャッシュ（挿入順保持）。 */
    private final Map<String, SecretKey> keysByKid = new LinkedHashMap<>();

    /** 新規発行に使う現在アクティブな鍵エントリ。 */
    private CurrentKey currentKey;

    /**
     * 起動時に全鍵をバイト列化し、{@link Keys#hmacShaKeyFor(byte[])} で {@link SecretKey} を構築する。
     * 鍵長不足・重複 kid・active 鍵未設定は起動失敗で検出する。
     */
    @PostConstruct
    void initialize() {
        Objects.requireNonNull(properties.getSigningKeys(),
                "mannschaft.jobs.qr.signing-keys が未設定です");

        String activeKid = null;
        SecretKey activeSecret = null;

        for (QrSigningProperties.SigningKey entry : properties.getSigningKeys()) {
            String kid = entry.getKid();
            String secret = entry.getSecret();

            if (keysByKid.containsKey(kid)) {
                throw new IllegalStateException(
                        "QR 署名鍵の kid が重複しています: kid=" + kid);
            }

            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_KEY_BYTES) {
                throw new IllegalStateException(String.format(
                        "QR 署名鍵 secret が短すぎます: kid=%s, length=%d bytes (%d bytes 以上必須)",
                        kid, bytes.length, MIN_KEY_BYTES));
            }

            SecretKey key = Keys.hmacShaKeyFor(bytes);
            keysByKid.put(kid, key);

            if (Boolean.TRUE.equals(entry.getActive())) {
                activeKid = kid;
                activeSecret = key;
            }
        }

        if (activeKid == null) {
            throw new IllegalStateException(
                    "QR 署名鍵に active=true のエントリが 1 件もありません");
        }

        this.currentKey = new CurrentKey(activeKid, activeSecret);
        log.info("QR 署名鍵ロード完了: totalKeys={}, activeKid={}", keysByKid.size(), activeKid);
    }

    /**
     * 新規発行に使用する現在の鍵を返す。
     *
     * <p>{@code signingKeys} リストで {@code active=true} が最後に現れた鍵を返す。
     * ローテーション時は新鍵を末尾に追加して {@code active=true} に、旧鍵は {@code active=false} に設定する。</p>
     */
    public CurrentKey current() {
        return currentKey;
    }

    /**
     * 指定 {@code kid} の鍵を検証用に解決する。旧鍵（{@code active=false}）も含めて解決可能。
     *
     * @param kid 鍵 ID（JWT ヘッダから抽出）
     * @return 鍵（存在しなければ {@link Optional#empty()}）
     */
    public Optional<SecretKey> find(String kid) {
        return Optional.ofNullable(keysByKid.get(kid));
    }

    /**
     * 新規発行に使う現在アクティブな鍵。{@code kid} と {@link SecretKey} をセットで返す。
     */
    public record CurrentKey(String kid, SecretKey key) {
    }
}

package com.mannschaft.app.billing.api;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@link BillingReturnSigningKeyProvider} の設定駆動実装（BC-16 / BC-28）。
 *
 * <p>金型は {@code QrSigningKeyProvider}。{@code mannschaft.billing.return-state.signing-keys} の
 * 全鍵を起動時にバイト列化してキャッシュし、{@code active=true} の最後の鍵で新規発行、検証は
 * 旧鍵を含む全鍵から {@code kid} で解決する（rotation 中の token を受理するため）。</p>
 *
 * <p><b>鍵未設定時の挙動（QR 前例からの意図的な差分）:</b> QR は起動失敗（{@code @NotEmpty}）だが、
 * 本 provider は<b>起動は通し、利用時に fail-closed で落とす</b>。理由は 2 つある。
 * (1) 課金 return state は billing ドメイン限定の機能であり、鍵未設定を起動失敗にすると
 * 無関係な全機能・全 {@code @SpringBootTest} を巻き込んで止める。
 * (2) 鍵が無い状態で発行を試みると {@link IllegalStateException} を投げるため、
 * 「弱い鍵で署名してしまう」危険は構造的に生じない（署名は一切行われない）。
 * 起動時には ERROR ログで欠落を明示するため、設定漏れは黙って隠れない。</p>
 *
 * <p>secret が設定されているのに 32 bytes 未満の場合は明確な設定ミスであり、起動失敗させる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BillingReturnSigningKeyProviderImpl implements BillingReturnSigningKeyProvider {

    /** HMAC-SHA256 の最小鍵長（バイト）。 */
    private static final int MIN_KEY_BYTES = 32;

    private final BillingReturnSigningProperties properties;

    /** kid → 鍵（挿入順保持・旧鍵も検証用に保持する）。 */
    private final Map<String, SigningKey> keysByKid = new LinkedHashMap<>();

    /** 新規発行に使う鍵（未設定なら null）。 */
    private SigningKey activeKey;

    /** 起動時に全鍵をバイト列化する。重複 kid・短すぎる secret は設定ミスとして起動失敗させる。 */
    @PostConstruct
    void initialize() {
        for (BillingReturnSigningProperties.SigningKey entry : properties.getSigningKeys()) {
            String kid = entry.getKid();
            String secret = entry.getSecret();
            if (secret == null || secret.isBlank()) {
                // 環境変数未設定（既定の空文字）。鍵として登録しない。
                continue;
            }
            if (keysByKid.containsKey(kid)) {
                throw new IllegalStateException("billing return 署名鍵の kid が重複しています: kid=" + kid);
            }
            byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < MIN_KEY_BYTES) {
                throw new IllegalStateException(String.format(
                        "billing return 署名鍵 secret が短すぎます: kid=%s, length=%d bytes (%d bytes 以上必須)",
                        kid, bytes.length, MIN_KEY_BYTES));
            }
            SigningKey key = new SigningKey(kid, bytes);
            keysByKid.put(kid, key);
            if (Boolean.TRUE.equals(entry.getActive())) {
                activeKey = key;
            }
        }
        if (activeKey == null) {
            log.error("billing return state 署名鍵が未設定です"
                    + "（mannschaft.billing.return-state.signing-keys / 環境変数 "
                    + "MANNSCHAFT_BILLING_RETURN_SIGNING_SECRET）。"
                    + "Checkout / Portal の復帰 URL 発行は fail-closed で拒否されます");
        } else {
            log.info("billing return state 署名鍵ロード完了: totalKeys={}, activeKid={}",
                    keysByKid.size(), activeKey.kid());
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException 鍵が 1 件も設定されていない場合（fail-closed）
     */
    @Override
    public SigningKey activeKey() {
        if (activeKey == null) {
            throw new IllegalStateException("billing return state 署名鍵が未設定のため state を発行できません");
        }
        return activeKey;
    }

    @Override
    public Optional<SigningKey> findByKid(String kid) {
        return Optional.ofNullable(keysByKid.get(kid));
    }
}

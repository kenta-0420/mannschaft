package com.mannschaft.app.mail.outbox;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * F09.18 メール配信 outbox の冪等キー生成器 (設計書 §7.1)。
 *
 * <p>生成規則:</p>
 * <pre>
 *   idempotency_key = sha256(user_id + ':' + template_kind + ':' + nonce)[:32]
 * </pre>
 *
 * <p>注意:</p>
 * <ul>
 *   <li>{@code user_id} が NULL の場合は {@code "0"} を使う (認証前のアドレス確認メール用)</li>
 *   <li>{@code nonce} が NULL の場合は {@code UUID.randomUUID()} をフォールバックとして使う
 *       (呼び出し側が必ず指定すべきだが安全網)</li>
 *   <li>出力は常に 32 文字の 16 進文字列 (sha256 → 64 文字の先頭 32 文字)</li>
 * </ul>
 *
 * <p>DigestUtils (commons-codec) は本プロジェクト未導入のため、{@link MessageDigest} を直接使う。</p>
 */
@Component
public class IdempotencyKeyGenerator {

    private static final int KEY_LENGTH = 32;
    private static final String ALGORITHM = "SHA-256";

    /**
     * 冪等キーを生成する。
     *
     * @param userId       受信者 user_id (null 可、null の場合は {@code "0"} を使用)
     * @param templateKind テンプレ種別
     * @param nonce        業務側の冪等識別子 (null 可、null の場合は UUID を生成)
     * @return 32 文字の 16 進文字列
     */
    public String generate(Long userId, String templateKind, String nonce) {
        String userIdPart = userId == null ? "0" : String.valueOf(userId);
        String noncePart = nonce == null ? UUID.randomUUID().toString() : nonce;
        String source = userIdPart + ":" + templateKind + ":" + noncePart;
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            byte[] digest = md.digest(source.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            return hex.substring(0, KEY_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 は標準で必ず存在する。ここに到達することはない
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }
}

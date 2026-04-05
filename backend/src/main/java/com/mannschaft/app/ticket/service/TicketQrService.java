package com.mannschaft.app.ticket.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * チケット QR コード消化ユーティリティ。
 *
 * <p>ワンタイムトークンの生成・検証・削除を担当する。
 * 本番環境では Valkey（Redis）に置き換える。現在はインメモリ ConcurrentHashMap で仮実装。</p>
 */
@Slf4j
@Service
public class TicketQrService {

    private static final int TOKEN_LENGTH = 12;
    private static final int TTL_MINUTES = 5;
    private static final String TOKEN_CHARS = "abcdef0123456789";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * インメモリトークンストア。本番では Valkey に置き換え。
     * key = token, value = QrTokenEntry(bookId, expiresAt)
     */
    private final Map<String, QrTokenEntry> tokenStore = new ConcurrentHashMap<>();

    /**
     * ワンタイムトークンを生成し、bookId と紐付けて保存する。
     *
     * @param bookId 回数券ID
     * @return QR ペイロード（tkt_{bookId}_otp_{token} 形式）と有効期限
     */
    public QrGenerateResult generateToken(Long bookId) {
        String token = generateRandomToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(TTL_MINUTES);
        tokenStore.put(token, new QrTokenEntry(bookId, expiresAt));
        String qrPayload = "tkt_" + bookId + "_otp_" + token;
        log.debug("QR トークン生成: bookId={}, token={}", bookId, token);
        return new QrGenerateResult(qrPayload, expiresAt);
    }

    /**
     * QR ペイロードからトークンを検証し、bookId を返す。検証後にトークンを即座に削除する。
     *
     * @param qrPayload QR ペイロード文字列
     * @return bookId（トークンが有効な場合）
     * @throws IllegalArgumentException ペイロード形式不正
     * @throws IllegalStateException    トークンが無効または期限切れ
     */
    public Long validateAndConsumeToken(String qrPayload) {
        // ペイロード形式: tkt_{bookId}_otp_{token}
        if (qrPayload == null || !qrPayload.startsWith("tkt_") || !qrPayload.contains("_otp_")) {
            throw new IllegalArgumentException("QR ペイロード形式不正: " + qrPayload);
        }

        String token = qrPayload.substring(qrPayload.indexOf("_otp_") + 5);
        QrTokenEntry entry = tokenStore.remove(token);

        if (entry == null) {
            throw new IllegalStateException("QR トークンが無効または既に使用済みです");
        }

        if (entry.expiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("QR トークンの有効期限が切れています");
        }

        log.debug("QR トークン検証成功: bookId={}", entry.bookId());
        return entry.bookId();
    }

    private String generateRandomToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_CHARS.charAt(SECURE_RANDOM.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * トークン保存エントリ。
     */
    record QrTokenEntry(Long bookId, LocalDateTime expiresAt) {}

    /**
     * QR 生成結果。
     */
    public record QrGenerateResult(String qrPayload, LocalDateTime expiresAt) {}
}

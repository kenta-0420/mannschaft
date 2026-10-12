package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.auth.dto.MfaEmailSendResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * {@link MfaEmailOtpService} の Valkey 実装（CMP-261012-0910）。
 *
 * <p><b>現段階（甲）</b>: キー設計・保存形式・照合・破棄を確定させた。送信（{@link #send}）と
 * SETUP / REAUTH の照合失敗上限（AC-45）は乙が実装する。送信が未実装の間はコードが一切保存されないため、
 * EMAIL での照合は常に「不正コード」になる（未送信は不正扱い＝AC-22 の仕様どおり）。</p>
 *
 * <p><b>保存形式</b>: コード保持キーの値は {@link #hash(MfaEmailOtpPurpose, String, String)}
 * （用途・主体・コードを連結した SHA-256 の 16 進）。平文コードは置かない。用途・主体をハッシュに含めるため、
 * 仮にキーを取り違えても別用途・別主体のコードとは一致しない（AC-23 の二重防御）。</p>
 */
@Service
@RequiredArgsConstructor
public class ValkeyMfaEmailOtpService implements MfaEmailOtpService {

    private final StringRedisTemplate redisTemplate;

    @Override
    public MfaEmailSendResponse send(MfaEmailOtpPurpose purpose, String subject, Long userId, String ipAddress) {
        throw new UnsupportedOperationException("CMP-261012-0910 メールOTP 送信は乙隊で実装する");
    }

    @Override
    public boolean verifyAndConsume(MfaEmailOtpPurpose purpose, String subject, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return false;
        }
        String key = MfaEmailOtpKeys.codeKey(purpose, subject);
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            return false;
        }
        byte[] expected = stored.getBytes(StandardCharsets.UTF_8);
        byte[] actual = hash(purpose, subject, code).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            return false;
        }
        // DEL の戻り値で消費者を 1 人に決める（同一コードの並列提示で成功は 1 件）
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    @Override
    public void invalidate(MfaEmailOtpPurpose purpose, String subject) {
        redisTemplate.delete(List.of(
                MfaEmailOtpKeys.codeKey(purpose, subject),
                MfaEmailOtpKeys.attemptCountKey(purpose, subject)));
    }

    /**
     * コード保持キーに保存する値（SHA-256 16 進）。送信側はこの関数で保存し、照合側と形式を一致させる。
     *
     * @param purpose 用途
     * @param subject 主体
     * @param code    平文 6 桁コード
     * @return ハッシュ値
     */
    static String hash(MfaEmailOtpPurpose purpose, String subject, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] h = digest.digest((purpose.name() + ":" + subject + ":" + code).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

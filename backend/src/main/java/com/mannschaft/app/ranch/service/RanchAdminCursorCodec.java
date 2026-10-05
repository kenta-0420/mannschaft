package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.ranch.RanchErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/** 管理主体・一覧種別・version境界を既存HMAC鍵に結び付ける不透明cursor。 */
@Component
@RequiredArgsConstructor
public class RanchAdminCursorCodec {
    public enum Kind { CARE, POLICY }
    private static final String DOMAIN = "ranch-admin-version-cursor-v1";
    private final EncryptionService encryption;

    public String encode(Long actorId, Kind kind, long beforeVersion) {
        if (actorId == null || kind == null || beforeVersion < 1) throw invalid();
        String text = kind.name() + ":" + actorId + ":" + beforeVersion;
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return payload + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
    }

    public Long decode(Long actorId, Kind kind, String token) {
        if (token == null) return null;
        if (actorId == null || kind == null || token.length() > 512) throw invalid();
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) throw invalid();
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (signature.length != 32 || !MessageDigest.isEqual(signature, sign(parts[0]))) throw invalid();
            String[] values = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split(":", -1);
            if (values.length != 3 || !kind.name().equals(values[0]) || !actorId.toString().equals(values[1])
                    || !values[2].matches("[1-9][0-9]*")) throw invalid();
            return Long.parseLong(values[2]);
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private byte[] sign(String payload) { return HexFormat.of().parseHex(encryption.hmac(DOMAIN + "." + payload)); }
    private BusinessException invalid() { return new BusinessException(RanchErrorCode.RANCH_006); }
}
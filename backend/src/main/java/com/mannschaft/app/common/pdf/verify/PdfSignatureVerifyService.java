package com.mannschaft.app.common.pdf.verify;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfErrorCode;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/**
 * 内部署名トークン検証サービス（F12.1 §5.14 / F09.15 §9.4）。
 *
 * <p>送信された PDF の SHA-256 を再計算して期待値と比較し、
 * 内部署名トークン（HMAC-SHA256 + epochMs）を再計算して照合する。
 *
 * <p>判定:
 * <ul>
 *   <li>{@code hashMatch}  — 再計算 SHA-256 == expectedHash</li>
 *   <li>{@code tokenValid} — recompute(expectedHash, subjectId, epochMs) == HMAC 部</li>
 *   <li>{@code valid}      — hashMatch && tokenValid</li>
 * </ul>
 *
 * <p>定数時間比較を用いて簡易なタイミング攻撃対策を行う。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfSignatureVerifyService {

    private final PdfGeneratorService pdfGeneratorService;

    public PdfSignatureVerifyResponse verify(PdfSignatureVerifyRequest request) {
        Objects.requireNonNull(request, "request は必須");

        byte[] pdfBytes;
        try {
            pdfBytes = Base64.getDecoder().decode(request.pdfBase64());
        } catch (IllegalArgumentException e) {
            log.warn("PDF Base64 デコード失敗: subjectId={}", request.subjectId());
            throw new BusinessException(PdfErrorCode.PDF_006, e);
        }

        String computedHash = pdfGeneratorService.sha256Hex(pdfBytes);
        boolean hashMatch = constantTimeEquals(computedHash, request.expectedHash());

        boolean tokenValid = false;
        try {
            // expectedToken 形式: HMAC_B64URL + "." + epochMs
            int dotIdx = request.expectedToken().lastIndexOf('.');
            if (dotIdx <= 0 || dotIdx == request.expectedToken().length() - 1) {
                log.warn("内部署名トークン形式不正: subjectId={}", request.subjectId());
            } else {
                long epochMs = Long.parseLong(request.expectedToken().substring(dotIdx + 1));
                Instant signedAt = Instant.ofEpochMilli(epochMs);
                // 再計算は computedHash ではなく expectedHash を使う:
                //   - 受信 PDF の hash が改ざんされていた場合、tokenValid を独立に評価できる
                //   - hash_match=false かつ token_valid=true なら呼び出し側で原因切り分け可能
                String recomputed = pdfGeneratorService.recomputeInternalToken(
                        request.expectedHash(), request.subjectId(), signedAt);
                tokenValid = constantTimeEquals(recomputed, request.expectedToken());
            }
        } catch (NumberFormatException e) {
            log.warn("内部署名トークンの epochMs 解析失敗: subjectId={}", request.subjectId());
        } catch (BusinessException e) {
            // PDF_007（鍵未設定）等は呼び出し元に伝播
            throw e;
        }

        boolean valid = hashMatch && tokenValid;
        log.info("PDF 内部署名検証: subjectId={} valid={} hashMatch={} tokenValid={}",
                request.subjectId(), valid, hashMatch, tokenValid);

        return new PdfSignatureVerifyResponse(valid, hashMatch, tokenValid, computedHash, Instant.now());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] aBytes = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}

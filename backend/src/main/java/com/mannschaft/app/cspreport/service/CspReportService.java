package com.mannschaft.app.cspreport.service;

import com.mannschaft.app.cspreport.dto.CspReportRequest;
import com.mannschaft.app.cspreport.entity.CspReportEntity;
import com.mannschaft.app.cspreport.repository.CspReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * CSP 違反レポートの受信・重複集約を担当するコアサービス。
 *
 * <p>同一違反パターン（違反ディレクティブ + ドキュメントURI + ブロックURI）は
 * SHA-256 ハッシュによって 1 レコードに集約し、occurrence_count をインクリメントする。
 * 新規パターンの場合は新規レコードを作成する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CspReportService {

    private final CspReportRepository cspReportRepository;

    /**
     * CSP 違反レポートを受信し、重複集約または新規作成する。
     *
     * @param report     CSP 違反レポートリクエスト
     * @param ipAddress  送信元 IP アドレス
     * @param userAgent  ブラウザの User-Agent
     */
    @Transactional
    public void receive(CspReportRequest report, String ipAddress, String userAgent) {
        String hash = computeHash(report);

        Optional<CspReportEntity> existing = cspReportRepository.findByReportHash(hash);
        if (existing.isPresent()) {
            // 既存レコードに集約: occurrence_count++ と lastSeenAt を更新
            CspReportEntity entity = existing.get();
            entity.incrementOccurrence();
            cspReportRepository.save(entity);
            log.debug("CSP違反レポート重複集約: hash={}, count={}", hash, entity.getOccurrenceCount());
        } else {
            // 新規レコードを作成
            CspReportEntity entity = buildEntity(report, hash, ipAddress, userAgent);
            cspReportRepository.save(entity);
            log.info("CSP違反レポート新規記録: violatedDirective={}, documentUri={}, blockedUri={}",
                    report.getViolatedDirective(), report.getDocumentUri(), report.getBlockedUri());
        }
    }

    /**
     * SHA-256(violatedDirective + "|" + documentUri + "|" + blockedUri) でハッシュを計算する。
     * null フィールドは空文字列として扱う。
     */
    private String computeHash(CspReportRequest report) {
        String directive = nullToEmpty(report.getViolatedDirective());
        String documentUri = nullToEmpty(report.getDocumentUri());
        String blockedUri = nullToEmpty(report.getBlockedUri());
        return sha256(directive + "|" + documentUri + "|" + blockedUri);
    }

    /**
     * リクエスト情報から Entity を構築する。
     */
    private CspReportEntity buildEntity(CspReportRequest report,
                                        String hash,
                                        String ipAddress,
                                        String userAgent) {
        return CspReportEntity.builder()
                .documentUri(truncate(report.getDocumentUri(), 1000))
                .blockedUri(truncate(report.getBlockedUri(), 1000))
                .violatedDirective(truncate(report.getViolatedDirective(), 200))
                .effectiveDirective(truncate(report.getEffectiveDirective(), 200))
                .originalPolicy(report.getOriginalPolicy())
                .disposition(truncate(report.getDisposition(), 20))
                .scriptSample(truncate(report.getScriptSample(), 500))
                .statusCode(report.getStatusCode())
                .reportHash(hash)
                .occurrenceCount(1)
                .ipAddress(truncate(ipAddress, 45))
                .userAgent(truncate(userAgent, 500))
                .build();
    }

    /**
     * SHA-256 ハッシュを計算する。
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength ? str : str.substring(0, maxLength);
    }
}

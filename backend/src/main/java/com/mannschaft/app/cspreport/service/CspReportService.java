package com.mannschaft.app.cspreport.service;

import com.mannschaft.app.cspreport.dto.CspReportRequest;
import com.mannschaft.app.cspreport.entity.CspReportEntity;
import com.mannschaft.app.cspreport.repository.CspReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
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

    private static final int MAX_URI_LENGTH = 1000;
    private static final String INVALID_URI = "/invalid-uri";

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
        String documentUri = normalizeUri(report.getDocumentUri());
        String blockedUri = normalizeUri(report.getBlockedUri());
        String hash = computeHash(report.getViolatedDirective(), documentUri, blockedUri);

        Optional<CspReportEntity> existing = cspReportRepository.findByReportHash(hash);
        if (existing.isPresent()) {
            // 既存レコードに集約: occurrence_count++ と lastSeenAt を更新
            CspReportEntity entity = existing.get();
            entity.incrementOccurrence();
            cspReportRepository.save(entity);
            log.debug("CSP違反レポート重複集約: hash={}, count={}", hash, entity.getOccurrenceCount());
        } else {
            // 新規レコードを作成
            CspReportEntity entity = buildEntity(report, documentUri, blockedUri, hash, ipAddress, userAgent);
            cspReportRepository.save(entity);
            log.info("CSP違反レポート新規記録: violatedDirective={}, documentUri={}, blockedUri={}",
                    report.getViolatedDirective(), documentUri, blockedUri);
        }
    }

    /**
     * SHA-256(violatedDirective + "|" + documentUri + "|" + blockedUri) でハッシュを計算する。
     * null フィールドは空文字列として扱う。
     */
    private String computeHash(String directive, String documentUri, String blockedUri) {
        return sha256(nullToEmpty(directive) + "|" + nullToEmpty(documentUri) + "|" + nullToEmpty(blockedUri));
    }

    /**
     * 保存・集約・ログに渡す前にURLの秘密部分を除く。pathはdecodeしない。
     * CSPのinline/eval等の分類は維持し、data/blobは埋込み内容を保持しない。
     */
    private String normalizeUri(String value) {
        if (value == null) {
            return null;
        }
        if (value.regionMatches(true, 0, "data:", 0, "data:".length())) {
            return "data:";
        }
        if (value.regionMatches(true, 0, "blob:", 0, "blob:".length())) {
            return "blob:";
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            String authority = uri.getRawAuthority();
            String canonical;
            if (authority != null) {
                String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
                canonical = (scheme == null ? "" : scheme + ":") + "//" + hostAndPort
                        + nullToEmpty(uri.getRawPath());
            } else if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return INVALID_URI;
            } else {
                // about:blankや分類値も保持しつつ、相対path等のquery/fragmentを除く。
                int end = value.length();
                int query = value.indexOf('?');
                int fragment = value.indexOf('#');
                if (query >= 0) {
                    end = Math.min(end, query);
                }
                if (fragment >= 0) {
                    end = Math.min(end, fragment);
                }
                canonical = value.substring(0, end);
            }
            // 切り詰めはpercent escapeやsurrogateを分断するため、超過は固定値にする。
            return canonical.length() <= MAX_URI_LENGTH ? canonical : INVALID_URI;
        } catch (IllegalArgumentException exception) {
            return INVALID_URI;
        }
    }

    /**
     * リクエスト情報から Entity を構築する。
     */
    private CspReportEntity buildEntity(CspReportRequest report,
                                        String documentUri,
                                        String blockedUri,
                                        String hash,
                                        String ipAddress,
                                        String userAgent) {
        return CspReportEntity.builder()
                .documentUri(documentUri)
                .blockedUri(blockedUri)
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

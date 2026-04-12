package com.mannschaft.app.common.storage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Cloudflare R2 オブジェクトへのアクセス URL 生成サービス。
 * Cloudflare Workers ドメインが設定されている場合は Workers 経由 URL を返す。
 * 未設定の場合は R2 Pre-signed URL（S3 互換 API）にフォールバックする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class R2UrlService {

    private final CdnProperties cdnProperties;
    private final R2StorageService r2StorageService;
    private final StorageProperties storageProperties;

    @PostConstruct
    void init() {
        if (cdnProperties.isEnabled() && !cdnProperties.getWorkersDomain().isBlank()) {
            log.info("Cloudflare Workers CDN有効: domain={}", cdnProperties.getWorkersDomain());
        } else {
            log.info("Cloudflare Workers CDN無効 — R2 Pre-signed URLにフォールバック");
        }
    }

    /**
     * 署名付きダウンロード URL を生成する。
     * CDN 有効かつ Workers ドメインが設定されている場合は Workers 経由 URL を返す。
     * それ以外は R2 Pre-signed URL を返す。
     *
     * @param r2Key R2 オブジェクトキー
     * @param ttl   有効期限（Pre-signed URL 使用時のみ有効）
     * @return アクセス URL
     */
    public String generateSignedUrl(String r2Key, Duration ttl) {
        if (cdnProperties.isEnabled() && !cdnProperties.getWorkersDomain().isBlank()) {
            // Cloudflare Workers 経由の URL（認証は Workers 側で処理）
            String url = "https://" + cdnProperties.getWorkersDomain() + "/" + r2Key;
            log.debug("Cloudflare Workers URL生成: key={}", r2Key);
            return url;
        }
        // R2 Pre-signed URL（S3 互換 API）
        return r2StorageService.generateDownloadUrl(r2Key, ttl);
    }

    /**
     * デフォルト TTL（設定値）で署名付き URL を生成する。
     *
     * @param r2Key R2 オブジェクトキー
     * @return アクセス URL
     */
    public String generateSignedUrl(String r2Key) {
        return generateSignedUrl(r2Key, Duration.ofSeconds(storageProperties.getPresignedDownloadTtl()));
    }

    /**
     * CDN が有効かどうかを返す。
     *
     * @return CDN 有効フラグ
     */
    public boolean isCdnEnabled() {
        return cdnProperties.isEnabled() && !cdnProperties.getWorkersDomain().isBlank();
    }
}

package com.mannschaft.app.common.storage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * CloudFront署名付きURL生成サービス。
 * CDNが有効な場合は署名付きCloudFront URLを返し、無効な場合はS3 Pre-signed URLにフォールバックする。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CloudFrontUrlService {

    private final CdnProperties cdnProperties;
    private final S3StorageService s3StorageService;
    private CloudFrontUtilities cloudFrontUtilities;

    @PostConstruct
    void init() {
        if (cdnProperties.isEnabled()) {
            cloudFrontUtilities = CloudFrontUtilities.create();
            log.info("CloudFront CDN有効: domain={}", cdnProperties.getDomain());
        } else {
            log.info("CloudFront CDN無効 — S3 Pre-signed URLにフォールバック");
        }
    }

    /**
     * 署名付きダウンロードURLを生成する。
     * CDN有効時はCloudFront署名付きURL、無効時はS3 Pre-signed URLを返す。
     *
     * @param s3Key S3オブジェクトキー
     * @param ttl   有効期限
     * @return 署名付きURL
     */
    public String generateSignedUrl(String s3Key, Duration ttl) {
        if (!cdnProperties.isEnabled()) {
            return s3StorageService.generateDownloadUrl(s3Key, ttl);
        }
        return generateCloudFrontSignedUrl(s3Key, ttl);
    }

    /**
     * デフォルトTTL（設定値）で署名付きURLを生成する。
     *
     * @param s3Key S3オブジェクトキー
     * @return 署名付きURL
     */
    public String generateSignedUrl(String s3Key) {
        return generateSignedUrl(s3Key, Duration.ofSeconds(cdnProperties.getSignedUrlTtl()));
    }

    /**
     * CDNが有効かどうかを返す。
     *
     * @return CDN有効フラグ
     */
    public boolean isCdnEnabled() {
        return cdnProperties.isEnabled();
    }

    private String generateCloudFrontSignedUrl(String s3Key, Duration ttl) {
        try {
            String resourceUrl = "https://" + cdnProperties.getDomain() + "/" + s3Key;
            Instant expirationDate = Instant.now().plus(ttl);

            CannedSignerRequest signerRequest = CannedSignerRequest.builder()
                    .resourceUrl(resourceUrl)
                    .privateKey(Path.of(cdnProperties.getPrivateKeyPath()))
                    .keyPairId(cdnProperties.getKeyPairId())
                    .expirationDate(expirationDate)
                    .build();

            String signedUrl = cloudFrontUtilities.getSignedUrlWithCannedPolicy(signerRequest).url();
            log.debug("CloudFront署名付きURL生成: key={}", s3Key);
            return signedUrl;
        } catch (Exception e) {
            log.warn("CloudFront署名付きURL生成失敗、S3フォールバック: key={}", s3Key, e);
            return s3StorageService.generateDownloadUrl(s3Key, ttl);
        }
    }
}

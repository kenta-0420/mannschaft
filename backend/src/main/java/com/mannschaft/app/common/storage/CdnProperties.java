package com.mannschaft.app.common.storage;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * CloudFront CDN の設定値を保持するコンポーネント。
 */
@Getter
@Component
public class CdnProperties {

    private final boolean enabled;
    private final String domain;
    private final String keyPairId;
    private final String privateKeyPath;
    private final int signedUrlTtl;

    public CdnProperties(
            @Value("${mannschaft.cdn.enabled:false}") boolean enabled,
            @Value("${mannschaft.cdn.domain:}") String domain,
            @Value("${mannschaft.cdn.key-pair-id:}") String keyPairId,
            @Value("${mannschaft.cdn.private-key-path:}") String privateKeyPath,
            @Value("${mannschaft.cdn.signed-url-ttl:900}") int signedUrlTtl) {
        this.enabled = enabled;
        this.domain = domain;
        this.keyPairId = keyPairId;
        this.privateKeyPath = privateKeyPath;
        this.signedUrlTtl = signedUrlTtl;
    }
}

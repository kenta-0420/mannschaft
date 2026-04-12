package com.mannschaft.app.common.storage;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cloudflare Workers CDN の設定値を保持するコンポーネント。
 * workers-domain が設定されている場合は Workers 経由 URL を使用する。
 * 未設定の場合は R2 Pre-signed URL にフォールバックする。
 */
@Getter
@Component
public class CdnProperties {

    private final boolean enabled;
    private final String workersDomain;

    public CdnProperties(
            @Value("${mannschaft.cdn.enabled:false}") boolean enabled,
            @Value("${mannschaft.cdn.workers-domain:}") String workersDomain) {
        this.enabled = enabled;
        this.workersDomain = workersDomain;
    }
}

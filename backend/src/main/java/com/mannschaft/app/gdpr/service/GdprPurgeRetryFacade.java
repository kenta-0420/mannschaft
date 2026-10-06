package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 旧7ドメインのTX契約を保ち、本人設定の独立TXへ手動retryを振り分ける非TX入口。 */
@Service
@RequiredArgsConstructor
public class GdprPurgeRetryFacade {

    private final GdprPurgeRetryService legacyRetryService;
    private final GdprSettingsPurgeRetryService settingsRetryService;

    /** 不明なドメインは旧サービスの既存入力検証へ渡す。 */
    public RetryResultResponse retryDomainPurge(Long userId, String domainName) {
        if (settingsRetryService.supports(domainName)) {
            return settingsRetryService.retryDomainPurge(userId, domainName);
        }
        return legacyRetryService.retryDomainPurge(userId, domainName);
    }
}

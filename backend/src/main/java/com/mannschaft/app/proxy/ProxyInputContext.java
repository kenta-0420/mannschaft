package com.mannschaft.app.proxy;

import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * リクエストスコープで代理入力状態を保持するBean（F14.1）。
 * ProxyInputContextFilterがヘッダーを検証してactivate()し、
 * Service層はisProxy()で代理入力モードかどうかを判定する。
 */
@Component
@RequestScope
@Getter
public class ProxyInputContext {

    private boolean proxyMode = false;
    private Long subjectUserId;
    private Long consentId;
    private String inputSource;
    private String originalStorageLocation;

    public boolean isProxy() {
        return proxyMode;
    }

    public void activate(Long subjectUserId, Long consentId,
                         String inputSource, String originalStorageLocation) {
        this.proxyMode = true;
        this.subjectUserId = subjectUserId;
        this.consentId = consentId;
        this.inputSource = inputSource;
        this.originalStorageLocation = originalStorageLocation;
    }

    public void clear() {
        this.proxyMode = false;
        this.subjectUserId = null;
        this.consentId = null;
        this.inputSource = null;
        this.originalStorageLocation = null;
    }
}

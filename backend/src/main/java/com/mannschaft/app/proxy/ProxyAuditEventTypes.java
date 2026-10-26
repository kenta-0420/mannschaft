package com.mannschaft.app.proxy;

/**
 * 代理入力機能（F14.1）の監査ログイベント種別定数。
 * AuditLogService.record()のeventType引数に使用する。
 */
public final class ProxyAuditEventTypes {

    public static final String PROXY_CONSENT_CREATED = "PROXY_CONSENT_CREATED";
    public static final String PROXY_CONSENT_APPROVED = "PROXY_CONSENT_APPROVED";
    public static final String PROXY_CONSENT_REVOKED = "PROXY_CONSENT_REVOKED";
    public static final String PROXY_INPUT_EXECUTED = "PROXY_INPUT_EXECUTED";

    private ProxyAuditEventTypes() {}
}

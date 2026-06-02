package com.mannschaft.app.securityincident;

/**
 * セキュリティインシデントの種別。
 *
 * <p>GDPR Article 33 に基づく DPA への通知対象となるインシデント分類。</p>
 */
public enum SecurityIncidentType {
    /** 個人データ漏洩 */
    DATA_BREACH,
    /** 認証突破・アカウント乗っ取り */
    AUTH_COMPROMISE,
    /** DDoS攻撃 */
    DDOS,
    /** サプライチェーン攻撃 */
    SUPPLY_CHAIN,
    /** 不正アクセス */
    UNAUTHORIZED_ACCESS,
    /** その他 */
    OTHER
}

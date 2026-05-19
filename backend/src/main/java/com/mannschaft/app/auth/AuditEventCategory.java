package com.mannschaft.app.auth;

public enum AuditEventCategory {
    AUTH,
    ACCOUNT,
    OAUTH,
    MFA,
    ADMIN_ACTION,
    LIFECYCLE,
    TEAM,
    ORGANIZATION,
    PAYMENT,
    SCHEDULE,
    TODO,
    REPAIR_PLAN,
    RESIDENT,
    /** F09.15 居住者継承支援（入居時誓約 / 事前登録 / 封緘解除）系。 */
    SUCCESSION,
    /** F18 個人ポイントカードウォレット（生成 / 削除 / 閲覧 / グループ / 設定）。 */
    POINT_CARD,
    /** F17 村機能（村作成・参加・通報・ピン・ロビー等）。 */
    VILLAGE,
    /** F15.4 セキュリティ系（レート制限到達 / 429 応答等）。 */
    SECURITY_RATE_LIMIT,
    /** F05.2 回覧板（押印訂正・委任・強制スキップ・添付削除等）。 */
    CIRCULATION
}

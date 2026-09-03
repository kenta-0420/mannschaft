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
    CIRCULATION,
    /** F05.7 書類テンプレート・フォームビルダー系（PDF / CSV / 複製 / リマインド等）。 */
    FORM,
    /** F03.5 シフト管理（手動リマインド送信等）。 */
    SHIFT,
    /** F05.1 掲示板（他者コンテンツ削除等のモデレーション操作）。 */
    BULLETIN,
    /** F08.7 / F08.7.1 大会（連絡スペース公開設定変更等）。 */
    TOURNAMENT,
    /** F08.10 試合記録・分析（スコア確定 / status 遷移 / 記録モード切替 / 記録係変更）。 */
    MATCH,
    /** 柱②-2 販促プロビジョニング（PROVISIONED作成・招待発行/再送/取消/承諾）。 */
    PROVISIONING,
    /** F08.4 領収書（発行者設定の変更等）。 */
    RECEIPT
}

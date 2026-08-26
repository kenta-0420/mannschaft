package com.mannschaft.app.mail.outbox;

/**
 * F09.18 メール配信基盤のテンプレート種別 enum。
 *
 * <p>設計書 {@code docs/features/F09.18_email_delivery_infrastructure.md} §11
 * 「既存 14 箇所の移行マトリクス」と完全一致する 14 種を定義する。
 *
 * <p>Phase 18-a では VERIFICATION / PASSWORD_RESET のみ既存テンプレ
 * （{@code templates/email/verification.html} / {@code password-reset.html}）を再利用する想定。
 * 残 12 種は Phase 18-b / 18-c で個別にレンダラと HTML テンプレを追加していく。
 *
 * <p>enum 名は {@code email_outbox.template_kind} カラムにそのまま保存され、
 * outbox ワーカーがレンダラディスパッチに使う。命名規約はドメイン接頭辞 +
 * SCREAMING_SNAKE_CASE。新規追加時は本 enum + マトリクス (§11) + 移行 Phase
 * の三者を必ず同時更新すること。
 */
public enum EmailTemplateKind {

    // 認証系 (Phase 18-b 最優先 — raw token 喪失問題の起点)
    /** #1, #2 認証メール (新規登録 / 再送、raw token 含む) */
    VERIFICATION,
    /** #3 パスワードリセット (raw token 含む) */
    PASSWORD_RESET,

    // 分析・レポート系 (Phase 18-c)
    /** #4 月次 KPI レポート (admin 複数宛) */
    ANALYTICS_KPI_MONTHLY,
    /** #5 集計サマリーメール (admin 複数宛) */
    ANALYTICS_SUMMARY,

    // 広告主・請求系 (Phase 18-c)
    /** #6 広告主向け請求書期限切れ通知 (複数受信者ループ) */
    ADVERTISING_INVOICE_OVERDUE,
    /** 広告主向け週次/月次パフォーマンスレポート */
    ADVERTISING_REPORT,

    // エラー・運用系 (Phase 18-c)
    /** #7 SYSTEM_ADMIN 向けエラー週次サマリー */
    ERROR_REPORT_WEEKLY,

    // 通知系 (Phase 18-c)
    /** #8 確認型通知メール (confirmToken 含む、REQUIRES_NEW TX、複数受信者) */
    NOTIFICATION_CONFIRM,

    // GDPR 系 (Phase 18-c)
    /** #9 データエクスポート完了通知 (ZIP パスワード含む、24h 有効期限) */
    GDPR_EXPORT_READY,
    /** #10 データエクスポート失敗通知 (リトライ可能、24h 期限内に再送試行) */
    GDPR_EXPORT_FAILED,
    /** #11 退会リマインダー (7日目 / 25日目) */
    GDPR_WITHDRAWAL_REMINDER,

    // 予約系 (Phase 18-b 最優先 = #12 / Phase 18-c = #13, #14)
    /** #12 臨時休業通知 (患者ループ送信、予約キャンセル併発、業務影響重大) */
    RESERVATION_EMERGENCY_CLOSURE,
    /** #13 臨時休業患者リマインド (3時間前) */
    RESERVATION_EMERGENCY_REMINDER,
    /** #14 臨時休業未確認リマインド (2時間前、操作者向け) */
    RESERVATION_EMERGENCY_UNCONFIRMED,
    /** #15 予約通知メール (F03.4 機能D・予約成立時に登録宛先へ「日時＋メニュー＋予約者名」を送信、スルー方式) */
    RESERVATION_RECEIVED_NOTIFY,

    // 広告メール配信系 (Phase 18-f — TC-4 package-private 化で前倒し移行)
    /** F09.17 ダイレクトメール配信 (DirectMailService 経由、スルー方式) */
    DIRECT_MAIL_AD
}

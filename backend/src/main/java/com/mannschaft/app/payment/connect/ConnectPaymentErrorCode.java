package com.mannschaft.app.payment.connect;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F22.1 謝礼決済（Connect / エスクロー）専用エラーコード。
 *
 * <p>既存 {@link com.mannschaft.app.payment.PaymentErrorCode}（{@code PAYMENT_001}〜{@code PAYMENT_027}）は
 * F08.2 会員費決済で使用済みであり、設計書 02 §7 のコード番号（{@code PAYMENT_011}=PAYEE_REQUIRED 等）と
 * 文字列が衝突する。コード文字列の重複は {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} の
 * 解決を曖昧にするため、本 Phase（F22.1 謝礼決済）では衝突しない {@code PAYMENT_C0xx} 系を採用する。
 * 意味（PAYEE_REQUIRED / WEBHOOK 署名失敗 等）は設計書 02 §7 に 1:1 対応する。</p>
 *
 * <p>HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に明示登録する
 * （登録漏れは {@code Severity} 既定 400/500 にフォールバックするため要注意・#1279 前科）。</p>
 *
 * <p>設計書: docs/features/F22.1_market/payment/02_api_design.md §7</p>
 */
@Getter
@RequiredArgsConstructor
public enum ConnectPaymentErrorCode implements ErrorCode {

    /** 認可エラー（札主/受領者本人でない・IDOR）。403。 */
    PAYMENT_FORBIDDEN("PAYMENT_C001", "この操作を行う権限がありません", Severity.WARN),

    /** escrow / connect_account が存在しない（または scope 不一致で秘匿）。404。 */
    PAYMENT_RESOURCE_NOT_FOUND("PAYMENT_C002", "対象が見つかりません", Severity.WARN),

    /** payment_enabled なのに price 未設定。422。 */
    PRICE_REQUIRED("PAYMENT_C010", "謝礼金額が設定されていません", Severity.WARN),

    /** payeeKind 未指定。422。 */
    PAYEE_REQUIRED("PAYMENT_C011", "受領主体（payeeKind）が指定されていません", Severity.WARN),

    /** payeeKind=USER で payeeUserId 未指定。422。 */
    PAYEE_USER_REQUIRED("PAYMENT_C012", "個人受領の場合は受領者ユーザーが必要です", Severity.WARN),

    /** 受領者が札主 scope に紐づかない（IDOR 防止）。422。 */
    PAYEE_NOT_IN_SCOPE("PAYMENT_C013", "受領者が札主スコープに属していません", Severity.WARN),

    /** 既に返金済み。409。 */
    ALREADY_REFUNDED("PAYMENT_C020", "既に返金済みです", Severity.WARN),

    /** 返金額が残額を超過。422。 */
    REFUND_AMOUNT_EXCEEDS("PAYMENT_C021", "返金額が残額を超えています", Severity.WARN),

    /** 払出時に payouts 不可（手動操作時の保険）。409。 */
    ONBOARDING_NOT_READY("PAYMENT_C030", "受領口座の登録が完了していません", Severity.WARN),

    /** Webhook 署名検証失敗。400。 */
    WEBHOOK_SIGNATURE_INVALID("PAYMENT_C040", "Webhook 署名の検証に失敗しました", Severity.WARN),

    /** 与信失敗（Stripe 側エラー・カード拒否）。409。 */
    AUTHORIZATION_FAILED("PAYMENT_C041", "与信に失敗しました", Severity.WARN),

    /** capture（払出）不可な状態からの payout 要求（CANCELLED/REFUNDED 等の後段状態）。409。 */
    INVALID_ESCROW_STATE("PAYMENT_C042", "この取引は払出できない状態です", Severity.WARN),

    /** capture 失敗（Stripe 側エラー）。409。 */
    CAPTURE_FAILED("PAYMENT_C043", "払出に失敗しました", Severity.WARN),

    /** Stripe API 通信失敗。500（Severity.ERROR 既定）。 */
    STRIPE_API_ERROR("PAYMENT_C050", "決済サービスとの通信に失敗しました", Severity.ERROR),

    /**
     * 安全ガード（R1・手数料ランク化・02 §3.5.2）: 総手数料が額面を超える（固定額が大きすぎ・少額決済の破綻）。422。
     *
     * <p>設計書 02 §7 はこの概念に番号 {@code PAYMENT_C050} を割り当てるが、当該文字列は既に
     * {@link #STRIPE_API_ERROR}（500）が使用済みである。さらに設計書 §7 は {@code PAYMENT_C051}〜{@code PAYMENT_C053}
     * を R2（シスアド CRUD・FEE_POLICY_NOT_FOUND 等）に予約済みのため、これらと衝突しない {@code PAYMENT_C060} を採用する
     * （番号は概念対応であり実コード文字列とは一致しない・設計書 02 §7 実装注記と同方針）。
     * {@code total_fee > face_amount} を {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に 422 で登録し
     * （#1279 の 500 フォールバック前科回避）、握りつぶさず「このパターンはこの額面に適用できない」と返す。</p>
     */
    FEE_EXCEEDS_FACE_AMOUNT("PAYMENT_C060", "手数料が額面を上回るため、この手数料パターンは適用できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

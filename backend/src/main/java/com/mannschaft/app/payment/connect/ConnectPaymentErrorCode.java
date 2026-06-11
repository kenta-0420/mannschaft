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

    /**
     * 札主の confirm 前（{@code PENDING_CONFIRMATION}）からの capture 要求を拒否する。409。
     *
     * <p>第一陣 status 意味論の根治（2026-06-10）。manual-capture PI は札主が Stripe.js で confirm するまで
     * 真の与信（amount_capturable）が立たないため、{@code PENDING_CONFIRMATION} からの capture は必ず Stripe で
     * 失敗する。これを Stripe へ到達させず（症状を隠さず）アプリ境界で 409 拒否する。confirm 後の
     * {@code payment_intent.amount_capturable_updated} で {@code AUTHORIZED} へ昇格してから capture すること。
     * {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に 409 で登録する（登録漏れは既定 400/500 へ
     * フォールバックするため・#1279 前科）。</p>
     */
    AUTHORIZATION_NOT_CONFIRMED("PAYMENT_C044", "札主のカード確認（与信確定）がまだ完了していません", Severity.WARN),

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
    FEE_EXCEEDS_FACE_AMOUNT("PAYMENT_C060", "手数料が額面を上回るため、この手数料パターンは適用できません", Severity.WARN),

    /**
     * シスアド CRUD: 存在しない（または不在で秘匿しない）手数料パターン（policy_key）を参照した。404。
     *
     * <p>設計書 02 §7 / §11 の {@code FEE_POLICY_NOT_FOUND}。{@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP}
     * に 404 で登録する（登録漏れは 400 既定にフォールバックするため・#1279 前科）。</p>
     */
    FEE_POLICY_NOT_FOUND("PAYMENT_C051", "対象の手数料パターンが見つかりません", Severity.WARN),

    /**
     * シスアド CRUD: {@code DEFAULT} パターンの削除/無効化を拒否する（解決フォールバックの終端・最後の砦）。409。
     *
     * <p>設計書 02 §7 / §11 の {@code FEE_POLICY_DEFAULT_IMMUTABLE}。{@code DEFAULT} が消えると全課金の解決終端が
     * 失われ破綻するため、削除/無効化のみ禁止する（率・固定額の改定は許容）。{@code ERROR_CODE_STATUS_MAP} に 409 で登録。</p>
     */
    FEE_POLICY_DEFAULT_IMMUTABLE("PAYMENT_C052", "DEFAULT パターンは削除・無効化できません", Severity.WARN),

    /**
     * シスアド CRUD: {@code percent_rate} が {@code [0,1)} 外、率・固定額がともに 0（手数料ゼロ）、または policy_key 形式違反。422。
     *
     * <p>設計書 02 §7 / §11 の {@code FEE_POLICY_INVALID_RATE}。{@code ERROR_CODE_STATUS_MAP} に 422 で登録。</p>
     */
    FEE_POLICY_INVALID_RATE("PAYMENT_C053", "手数料パターンの率・固定額が不正です（率は 0 以上 1 未満、率と固定額がともに 0 は不可）", Severity.WARN),

    /**
     * シスアド CRUD: 既存 policy_key で新規作成（POST）した（重複）。409。
     *
     * <p>更新は {@code PUT /{policyKey}} に誘導する。{@code ERROR_CODE_STATUS_MAP} に 409 で登録。</p>
     */
    FEE_POLICY_ALREADY_EXISTS("PAYMENT_C054", "同じキーの手数料パターンが既に存在します（更新は PUT を使用してください）", Severity.WARN),

    /**
     * シスアド CRUD（割当）: {@code (source_kind, sub_key, organization_id)} の組が既存（UNIQUE 違反）。409。
     *
     * <p>{@code ERROR_CODE_STATUS_MAP} に 409 で登録。</p>
     */
    FEE_POLICY_ASSIGNMENT_DUPLICATE("PAYMENT_C055", "同じ条件の割当が既に存在します", Severity.WARN),

    /**
     * シスアド CRUD（割当）: 割当が参照する policy_key が無効（{@code enabled=FALSE}）である。422。
     *
     * <p>存在しない policy_key は {@link #FEE_POLICY_NOT_FOUND}（404）で区別する。無効パターンへの割当は解決時に
     * フォールバックされ意図せぬ DEFAULT 適用を招くため、設定時点で握りつぶさず拒否する（症状を隠さない）。
     * {@code ERROR_CODE_STATUS_MAP} に 422 で登録。</p>
     */
    FEE_POLICY_ASSIGNMENT_POLICY_DISABLED("PAYMENT_C056", "割当先の手数料パターンが無効です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

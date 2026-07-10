package com.mannschaft.app.billing;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F20.1 課金・エンタイトルメント基盤のエラーコード（設計書 02 §9）。
 *
 * <p>HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に明示登録する
 * （登録漏れは Severity 既定 400/500 にフォールバックする前科があるため）。402（支払えば解決する）は
 * {@code FEATURE_NOT_ENTITLED} のみ・403 は {@code FEATURE_FORBIDDEN_FOR_SCOPE}/{@code SCOPE_FORBIDDEN}。</p>
 *
 * <p><b>採番注記</b>: {@code ENTITLEMENT_} プレフィックスは新設。マージ時に
 * {@code git grep "ENTITLEMENT_0"} で並行 PR との衝突を再確認すること（設計書 02 §9）。</p>
 */
@Getter
@RequiredArgsConstructor
public enum EntitlementErrorCode implements ErrorCode {

    /** 指定 planKey が存在しない/enabled=false（404）。 */
    PLAN_NOT_FOUND("ENTITLEMENT_001", "指定されたプランが見つかりません", Severity.WARN),

    /** 指定 featureKey がカタログに存在しない（404）。 */
    FEATURE_NOT_FOUND("ENTITLEMENT_002", "指定された機能が見つかりません", Severity.WARN),

    /** 権利なし・購入手段あり（アドオン/上位プラン）→ 402。details に購入導線。 */
    FEATURE_NOT_ENTITLED("ENTITLEMENT_003", "この機能を利用するには契約が必要です", Severity.WARN),

    /** 権利なし・購入手段なし（スコープ不適合・カタログ無効含む）→ 403。 */
    FEATURE_FORBIDDEN_FOR_SCOPE("ENTITLEMENT_004", "この機能は利用できません", Severity.WARN),

    /** scopeId の所有権なし（IDOR・03 §2）→ 403。 */
    SCOPE_FORBIDDEN("ENTITLEMENT_005", "指定されたスコープへの権限がありません", Severity.WARN),

    /** ACTIVE な PLAN 契約が既に存在（/同一 plan への変更/同一 ADDON 重複）→ 409。 */
    CONTRACT_ALREADY_ACTIVE("ENTITLEMENT_006", "既に有効な契約が存在します", Severity.WARN),

    /** 契約が存在しない/スコープ不一致（IDOR 秘匿）→ 404。 */
    CONTRACT_NOT_FOUND("ENTITLEMENT_007", "指定された契約が見つかりません", Severity.WARN),

    /** featureKey が addon_available=false → 422。 */
    ADDON_NOT_AVAILABLE("ENTITLEMENT_008", "この機能はアドオン契約できません", Severity.WARN),

    /** scopeKind が USER/TEAM/ORG 以外 → 400。 */
    INVALID_SCOPE_KIND("ENTITLEMENT_009", "スコープ種別の指定が不正です", Severity.WARN),

    /** シスアド CRUD のマスタ整合違反 → 400。 */
    PLAN_MASTER_VALIDATION_FAILED("ENTITLEMENT_010", "プランマスタの整合性違反です", Severity.WARN),

    /** 既に CANCELLED/EXPIRED の契約への解約・変更 → 409。 */
    CONTRACT_NOT_CANCELLABLE("ENTITLEMENT_011", "この契約は既に解約または失効しています", Severity.WARN),

    /** 参照中マスタの DELETE（enabled=false を案内）→ 409。 */
    PLAN_MASTER_IN_USE("ENTITLEMENT_012", "参照中のマスタは削除できません", Severity.WARN),

    /** uk_ent_grant 違反（同一発行元×同時刻の二重発行・AC-21）→ 409。 */
    DUPLICATE_ENTITLEMENT("ENTITLEMENT_013", "権利の二重発行が検出されました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

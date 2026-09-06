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
    DUPLICATE_ENTITLEMENT("ENTITLEMENT_013", "権利の二重発行が検出されました", Severity.WARN),

    /**
     * contractKind が PLAN/ADDON 以外 → 400。
     *
     * <p>設計 02 §9 の一覧には contractKind 専用コードが無かったため本弾で追補採番した
     * （{@code INVALID_SCOPE_KIND}=009 は「scopeKind 不正」の意味であり contractKind 不正には意味が
     * 合わないため流用しない）。CreateContractRequest の {@code contractKind} 検証（02 §3.1）に対応する。</p>
     */
    INVALID_CONTRACT_KIND("ENTITLEMENT_014", "契約種別の指定が不正です", Severity.WARN),

    /**
     * Stripe Checkout Session 生成失敗（PSP 呼び出しエラー）→ 502。
     *
     * <p>実決済（D-1〜D-4・2026-07-10 御裁可）で追補採番。PENDING 契約を起票後に Stripe API が失敗した場合、
     * 当該 PENDING 契約は補償（CANCELLED＋pointer 物理 DELETE）して本コードを投げる（孤児 PENDING を残さない）。
     * {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に 502 を明示登録する。</p>
     */
    CHECKOUT_SESSION_FAILED("ENTITLEMENT_015", "決済手続きの開始に失敗しました", Severity.ERROR),

    /**
     * 決済フローの契約が PENDING（入金前）のスロットを占有中の再契約 → 409。
     *
     * <p>{@code active_contract_pointers.uk_acp_slot} が PENDING 契約でスロットを確保している状態での
     * 二重契約試行。既存が ACTIVE なら {@link #CONTRACT_ALREADY_ACTIVE}（006）、PENDING なら本コード（016）で
     * 「入金前の契約が進行中」を明示する（再挑戦は Checkout 完了/失効後）。</p>
     */
    CONTRACT_PENDING_PAYMENT("ENTITLEMENT_016", "入金前の契約が進行中です。完了または失効後に再度お試しください", Severity.WARN),

    /**
     * 決済を要するプラン変更 → 409（実決済 検分差し戻し・AC-44）。
     *
     * <p>changePlan は決済レール（Checkout / Stripe サブスク差し替え）を持たないため、
     * (a) 既存契約が有償（{@code psp_subscription_ref} 非 NULL）＝旧サブスクが解約されず課金継続する孤児化、
     * (b) 変更先プランが有償（価格設定済み）＝Checkout を経ない無償すり抜け（D-4 の抜け穴）、
     * のいずれも 409 で拒否し「一度解約してから新プランを契約」する導線へ誘導する。</p>
     */
    CONTRACT_CHANGE_REQUIRES_PAYMENT("ENTITLEMENT_017",
            "有償プランが関わる変更はできません。一度解約してから新プランを契約してください", Severity.WARN),

    /** 請求書が存在しない又は他 scope のため秘匿する（404）。 */
    INVOICE_NOT_FOUND("ENTITLEMENT_018", "指定された請求書が見つかりません", Severity.WARN),

    /** 対象価格が販売可能でない（409）。 */
    PRICE_NOT_SELLABLE("ENTITLEMENT_019", "指定された価格は現在販売できません", Severity.WARN),

    /** 変更 preview が失効した（409）。 */
    PREVIEW_EXPIRED("ENTITLEMENT_020", "変更内容の確認期限が切れています", Severity.WARN),

    /** 契約変更が進行中操作と競合した（409）。 */
    CHANGE_CONFLICT("ENTITLEMENT_021", "別の契約操作が進行中です", Severity.WARN),

    /** JST 月境界の安全窓に入った（409）。 */
    MONTH_BOUNDARY("ENTITLEMENT_022", "月初に最新金額を再見積りしてください", Severity.WARN),

    /** quote が失効又は snapshot と不一致になった（409）。 */
    QUOTE_EXPIRED("ENTITLEMENT_023", "見積りの有効期限が切れています", Severity.WARN),

    /** legacy Customer の移行が必要（409）。 */
    MIGRATION_REQUIRED("ENTITLEMENT_024", "支払方法の移行が必要です", Severity.WARN),

    /** Stripe が利用不能（502）。 */
    STRIPE_UNAVAILABLE("ENTITLEMENT_025", "決済サービスを利用できません", Severity.ERROR),

    /** 旧有償 POST は Billing Center flow が必要（409）。 */
    BILLING_FLOW_REQUIRED("ENTITLEMENT_026", "料金・契約画面から手続きを開始してください", Severity.WARN),

    // ========================================
    // 柱③-B PR-2 請求支払者の引継（設計書 billing_payer_handover_design.md §3.6・§5.5）
    // ========================================

    /** USER スコープ契約は引継の概念自体が無い（400・設計書 §4.2）。 */
    HANDOVER_SCOPE_NOT_SUPPORTED("ENTITLEMENT_027",
            "この契約は請求担当の引継に対応していません", Severity.WARN),

    /**
     * 旧契約が {@code PAST_DUE}、期末が既に過去、または Stripe サブスク未作成のため引継できない
     * （409・設計書 §3.6 R2-P1-4・AC-29）。{@code trial_end} には未来時刻しか指定できないため、
     * この条件では引継要求の作成自体を拒否し、先に支払回収または解約を促す。
     */
    HANDOVER_CONTRACT_NOT_ELIGIBLE("ENTITLEMENT_028",
            "先に旧契約の支払回収または解約を完了してから、請求担当の引継を申請してください", Severity.WARN),

    /** 同一契約への進行中（非終端）の引継要求は同時に1件のみ（409・設計書 §4.2 生成列+UNIQUE）。 */
    HANDOVER_ALREADY_IN_PROGRESS("ENTITLEMENT_029",
            "この契約には進行中の引継要求があります", Severity.WARN),

    /** 引継要求が存在しない、またはスコープ越境（404・存在オラクルを残さないため404で畳む）。 */
    HANDOVER_NOT_FOUND("ENTITLEMENT_030",
            "指定された引継要求が見つかりません", Severity.WARN),

    /** 承諾できる状態（{@code REQUESTED}/{@code REQUIRES_PAYMENT_METHOD}）ではない（409）。 */
    HANDOVER_NOT_ACCEPTABLE("ENTITLEMENT_031",
            "この引継要求は承諾できる状態ではありません", Severity.WARN),

    /** 引継先候補の ADMIN が居ない、または全員が退会予定（409・設計書 §5.5 ①②・AC-10/17/18）。 */
    HANDOVER_NO_CANDIDATE("ENTITLEMENT_032",
            "引継先となる管理者が見つかりません", Severity.WARN),

    /** 猶予期限（既定14日）を過ぎている（409・設計書 §5.3・AC-21）。 */
    HANDOVER_EXPIRED("ENTITLEMENT_033",
            "この引継要求は期限切れです", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

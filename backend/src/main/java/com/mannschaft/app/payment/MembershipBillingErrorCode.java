package com.mannschaft.app.payment;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F08.9 会費課金・ペイウォール（membership billing / paywall）専用エラーコード。
 *
 * <p><b>コード採番（衝突回避の根拠）:</b><br>
 * 決済ドメインには既に2系統のコードが存在する。
 * <ul>
 *   <li>{@link PaymentErrorCode}（F08.2 会員費決済）: {@code PAYMENT_001}〜{@code PAYMENT_027}</li>
 *   <li>{@link com.mannschaft.app.payment.connect.ConnectPaymentErrorCode}（F22.1 謝礼決済 Connect）:
 *       {@code PAYMENT_C001}〜{@code PAYMENT_C050}</li>
 * </ul>
 * 本 F08.9 では上記いずれとも文字列が重複しない独立プレフィックス {@code MEMBERSHIP_BILLING_xxx} を採用する。
 * コード文字列の重複は {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} の解決を曖昧にするため避ける。
 * （{@code PAYMENT_Cxxx} を継続採番すると将来 F22.1 の追番と衝突しうるため、ドメイン由来の独立系列を切る。）</p>
 *
 * <p><b>HTTP ステータス:</b> {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に明示登録する。
 * {@code Severity.WARN} の既定は 400 のため、403/409 は登録漏れすると 400 にフォールバックする（#1279 前科）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/03_security.md §2「代理払いの認可」</p>
 */
@Getter
@RequiredArgsConstructor
public enum MembershipBillingErrorCode implements ErrorCode {

    /**
     * 払い手が受益者の会費を払う権原を持たない（払い手 ≠ 受益者の核心 IDOR 対策）。403。
     *
     * <p>SELF（本人）/ GUARDIAN（保護者リンク）/ PROXY_GRANT（payment_proxy_grants）/
     * ADMIN_MANUAL（scope ADMIN の手動記録）のいずれの権原も成立しない場合に投げる。</p>
     */
    MEMBERSHIP_PAYER_NOT_AUTHORIZED(
            "MEMBERSHIP_BILLING_001",
            "この受益者の会費を支払う権限がありません",
            Severity.WARN),

    /**
     * 既に有効な支払い記録が存在する（二重課金防止）。409。
     *
     * <p>同一受益者・同一 payment_item に対して既に PAID な記録がある場合に投げる。</p>
     */
    MEMBERSHIP_ALREADY_PAID(
            "MEMBERSHIP_BILLING_002",
            "この支払い項目には既に有効な支払い記録が存在します",
            Severity.WARN),

    /**
     * 後見切替セッション中（acting-as / {@code X-Proxy-For-User-Id} 付き）に、
     * 子の認証クリティカル操作（パスワード変更・2FA設定・メール変更・退会・退会取消）を
     * 代理しようとした（なりすまし防止の安全境界）。403。
     *
     * <p>設計書: docs/features/F08.9_membership_billing_paywall/03_security.md §3.2
     * 「切替セッションの安全境界（なりすまし防止）」。これらは認証クリティカルゆえ代理不可。</p>
     */
    MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION(
            "MEMBERSHIP_BILLING_003",
            "後見切替セッション中はこの認証に関わる操作を代理で行えません",
            Severity.WARN),

    /**
     * 後見切替を開始しようとした子が年齢ポリシーで封印段階（中学生以降等）に達しているため切替できない。403。
     *
     * <p>{@code GuardianshipAgePolicyRegistry.forCountry(child.country_code).resolve(...).switchAllowed == false}
     * の場合に投げる。生年月日が解決できず安全側に封印された場合も本コード。</p>
     *
     * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §2.2
     * （{@code GUARDIANSHIP_SWITCH_AGE_LOCKED}）／03_security.md §3「後見切替の年齢ゲート」。</p>
     */
    GUARDIANSHIP_SWITCH_AGE_LOCKED(
            "MEMBERSHIP_BILLING_004",
            "このお子さまは年齢到達のため後見切替できません",
            Severity.WARN),

    /**
     * 後見切替を開始しようとした相手に有効な保護者リンクが存在しない。403。
     *
     * <p>parental_consent_links（APPROVED）も care_links（ACTIVE PARENT）も成立しない場合に投げる。
     * 他人の子になりすます試行（IDOR）もここで弾く。</p>
     *
     * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §2.2
     * （{@code GUARDIANSHIP_LINK_NOT_FOUND}）。</p>
     */
    GUARDIANSHIP_LINK_NOT_FOUND(
            "MEMBERSHIP_BILLING_005",
            "有効な保護者リンクが見つからないため後見切替できません",
            Severity.WARN),

    /**
     * 自立移行の引き継ぎ（パスワード設定リンク送付）に必要な子のメールアドレスが解決できない。400。
     *
     * <p>子が未登録メール かつ {@code childEmail} も未指定の場合に投げる。逆に子に既存メールが
     * あるのに {@code childEmail} を指定した場合（上書き要求＝メール変更フローの迂回）も本コードで弾く。</p>
     *
     * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §2.3
     * （{@code handover/initiate}・子メールが無ければ保護者が登録）。</p>
     */
    GUARDIANSHIP_HANDOVER_EMAIL_REQUIRED(
            "MEMBERSHIP_BILLING_006",
            "引き継ぎにはお子さまのメールアドレスが必要です",
            Severity.WARN),

    /**
     * 協会請求（payment_requests）が見つからない。404。
     *
     * <p>存在しない／論理削除済み／IDOR（他テナント・他チーム宛て）を秘匿して 404 で返す。</p>
     *
     * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §7。</p>
     */
    PAYMENT_REQUEST_NOT_FOUND(
            "MEMBERSHIP_BILLING_007",
            "請求が見つかりません",
            Severity.WARN),

    /**
     * 協会請求が現在の状態では実行できない操作（取消/支払いの状態制約）。409。
     *
     * <p>取消は DRAFT/SENT のみ、支払いは SENT/VIEWED/OVERDUE のみ。それ以外の状態からの操作で投げる。</p>
     *
     * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §7。</p>
     */
    PAYMENT_REQUEST_INVALID_STATUS(
            "MEMBERSHIP_BILLING_008",
            "この請求は現在の状態ではその操作を実行できません",
            Severity.WARN),

    /**
     * 協会請求が支払い済み（二重支払い防止）。409。
     *
     * <p>{@code status=PAID} の請求に対して再度支払いを試みた場合に投げる（02_api §7
     * {@code PAYMENT_REQUEST_ALREADY_PAID}）。</p>
     */
    PAYMENT_REQUEST_ALREADY_PAID(
            "MEMBERSHIP_BILLING_009",
            "この請求は既に支払い済みです",
            Severity.WARN),

    /**
     * 協会請求の支払いで、着金先（協会の Connect 口座）が READY（payouts_enabled）でない。409。
     *
     * <p>READY 検証は<b>支払い時</b>に行う（発行時ではない・即時モードゆえ HELD にしない）。</p>
     *
     * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §7 / 11。</p>
     */
    PAYMENT_REQUEST_CONNECT_NOT_READY(
            "MEMBERSHIP_BILLING_010",
            "請求先の着金口座の登録が完了していません",
            Severity.WARN),

    /**
     * 協会請求の支払い操作者が、請求先チームの ADMIN でない（または請求先チーム不一致）。403。
     *
     * <p>{@code payment_requests.payer_scope_id == teamId} かつ team ADMIN を要求する（03_security §1・
     * 02_api §7 {@code PAYMENT_REQUEST_NOT_FOR_THIS_TEAM}）。IDOR を秘匿して 403 で返す。</p>
     */
    PAYMENT_REQUEST_NOT_FOR_THIS_TEAM(
            "MEMBERSHIP_BILLING_011",
            "この請求を支払う権限がありません",
            Severity.WARN),

    /**
     * 立替/精算記録（team_payment_advances）が見つからない。404。
     *
     * <p>存在しない／論理削除済み／IDOR（他チーム宛て）を秘匿して 404 で返す。</p>
     */
    PAYMENT_ADVANCE_NOT_FOUND(
            "MEMBERSHIP_BILLING_012",
            "立替記録が見つかりません",
            Severity.WARN),

    /**
     * 立替が既に精算済み（重複確認防止）。409。
     *
     * <p>{@code settlement_status=SETTLED} の立替に対して再度精算確認を試みた場合に投げる
     * （02_api §10 {@code ADVANCE_ALREADY_SETTLED}）。</p>
     */
    PAYMENT_ADVANCE_ALREADY_SETTLED(
            "MEMBERSHIP_BILLING_013",
            "この立替は既に精算済みです",
            Severity.WARN),

    /**
     * 協会請求の配信（send）で、請求先チームに受信できる ADMIN/DEPUTY_ADMIN が一人も居ない。409。
     *
     * <p>確認必須通知の受信者ゼロは配信不能（チーム体制の不備）。状態制約の
     * {@link #PAYMENT_REQUEST_INVALID_STATUS}（取消/支払いの状態違反）と混同しないよう専用コードに分離する
     * （02_api §7「受信者ゼロ・DRAFT 以外は 409」のうち受信者ゼロ側）。</p>
     */
    PAYMENT_REQUEST_NO_RECIPIENTS(
            "MEMBERSHIP_BILLING_014",
            "この請求を配信できる管理者が請求先チームにいません",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

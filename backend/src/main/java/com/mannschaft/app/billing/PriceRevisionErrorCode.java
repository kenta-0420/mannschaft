package com.mannschaft.app.billing;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 価格改定戦役（price-revisions）: 税コードマスタ・価格 revision 作成のエラーコード。
 *
 * <p>本 enum は試練隊（第1陣）が AC-1〜AC-53・AC-176・AC-177 の red テストを書くために
 * 契約として新設した。HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} への
 * 登録を出陣隊（実装）が行う（{@code EntitlementErrorCode} と同じ採番規約に倣う）。</p>
 *
 * <p>採番注記: {@code PRICE_REVISION_} プレフィックスは新設。マージ時に既存 PR との重複が無いか
 * 再確認すること。</p>
 */
@Getter
@RequiredArgsConstructor
public enum PriceRevisionErrorCode implements ErrorCode {

    /** 同一 code・同一 validFrom の税コード重複登録（uk_btc_code_from）→ 409。AC-9。 */
    TAX_CODE_DUPLICATE("PRICE_REVISION_001", "同一の税コード・適用開始日時が既に登録されています", Severity.WARN),

    /** 同一 code の有効期間が重なる登録・更新 → 409。AC-10。 */
    TAX_CODE_OVERLAP("PRICE_REVISION_002", "税コードの有効期間が既存の行と重複しています", Severity.WARN),

    /** 指定 taxCode が存在しない・無効・有効期間外 → 400。AC-14/AC-15/AC-16。 */
    TAX_CODE_NOT_FOUND("PRICE_REVISION_003", "指定された税コードは利用できません", Severity.WARN),

    /** 税コードの不変項目（rateBasisPoints/code/validFrom）を更新しようとした → 400。AC-6。 */
    TAX_CODE_IMMUTABLE_FIELD("PRICE_REVISION_004", "税率・コード・適用開始日時は更新できません", Severity.WARN),

    /** band 構成の検証違反全般（連番・人数レンジ・件数上限など）→ 400。AC-20〜AC-26/AC-37。 */
    BAND_VALIDATION_FAILED("PRICE_REVISION_005", "価格帯の指定が不正です", Severity.WARN),

    /** inputAmount が0以下・上限超過 → 400。AC-27/AC-28。 */
    INVALID_AMOUNT("PRICE_REVISION_006", "金額の指定が不正です", Severity.WARN),

    /** effectiveFrom が過去日時・effectiveUntil<=effectiveFrom → 400。AC-29/AC-30。 */
    INVALID_EFFECTIVE_PERIOD("PRICE_REVISION_007", "有効期間の指定が不正です", Severity.WARN),

    /** PLAN/ADDON の productKey がマスタに実在しない → 400。AC-31/AC-32。 */
    PRODUCT_NOT_FOUND("PRICE_REVISION_008", "指定された商品が見つかりません", Severity.WARN),

    /** ADDON 対象 feature の addonAvailable=false → 400。AC-33。 */
    ADDON_NOT_AVAILABLE_FOR_REVISION("PRICE_REVISION_009", "この機能はアドオン価格を設定できません", Severity.WARN),

    /** PLAN/ADDON の productKey 型の取り違え → 400。AC-34。 */
    PRODUCT_KIND_MISMATCH("PRICE_REVISION_010", "商品種別と商品キーが一致しません", Severity.WARN),

    /** scopeKind が USER/TEAM/ORG 以外 → 400。 */
    INVALID_SCOPE_KIND("PRICE_REVISION_011", "スコープ種別の指定が不正です", Severity.WARN),

    /** productKey/taxCode が空文字・空白のみ・64文字超 → 400。AC-36。 */
    INVALID_FIELD_LENGTH("PRICE_REVISION_012", "文字列項目の長さが不正です", Severity.WARN),

    /** 同一 (productKind, productKey, scopeKind) に既に future が1件存在する状態での2本目の create → 409（第6版・単一future制限）。AC-176。 */
    FUTURE_REVISION_ALREADY_EXISTS("PRICE_REVISION_013", "既に未来日程の価格改定が存在します", Severity.WARN),

    /** ACTIVE 側の区間重なり判定違反 → 409。AC-46/AC-49。 */
    REVISION_OVERLAP("PRICE_REVISION_014", "有効期間が既存の価格改定と重複しています", Severity.WARN),

    /** taxBehavior が INCLUSIVE/EXCLUSIVE 以外 → 400。AC-39。 */
    INVALID_TAX_BEHAVIOR("PRICE_REVISION_015", "税表示方式の指定が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

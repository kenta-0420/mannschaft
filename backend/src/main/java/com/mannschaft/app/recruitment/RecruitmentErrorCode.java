package com.mannschaft.app.recruitment;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F03.11 募集型予約のエラーコード定義。
 * 設計書 §15.1〜§15.4 を参照。Phase 1+5a スコープのコードのみ実装。
 */
@Getter
@RequiredArgsConstructor
public enum RecruitmentErrorCode implements ErrorCode {

    // ========================================
    // 15.1 リソース・権限系 (001〜099)
    // ========================================

    /** 募集が見つからない */
    LISTING_NOT_FOUND("RECRUITMENT_001", "募集が見つかりません", Severity.WARN),

    /** 募集の作成権限なし */
    NO_PERMISSION_TO_CREATE("RECRUITMENT_002", "募集の作成権限がありません", Severity.WARN),

    /** 公開範囲による閲覧不可 */
    VISIBILITY_DENIED("RECRUITMENT_003", "公開範囲によりこの募集を閲覧できません", Severity.WARN),

    /** カテゴリ未指定 */
    CATEGORY_NOT_SPECIFIED("RECRUITMENT_012", "カテゴリが指定されていません", Severity.ERROR),

    /** 決済有効化時に料金未指定 */
    PRICE_REQUIRED("RECRUITMENT_015", "決済を有効化する場合は料金の指定が必要です", Severity.ERROR),

    /** DRAFT 募集の閲覧権限なし */
    DRAFT_VIEW_DENIED("RECRUITMENT_020", "下書き募集の閲覧権限がありません", Severity.WARN),

    // ========================================
    // 15.2 ステータス遷移エラー (100〜199)
    // ========================================

    /** 不正な状態遷移 */
    INVALID_STATE_TRANSITION("RECRUITMENT_100", "不正な状態遷移です", Severity.WARN),

    /** 既に締切を過ぎている */
    DEADLINE_EXCEEDED("RECRUITMENT_101", "応募締切を過ぎています", Severity.WARN),

    /** 既にキャンセル済み */
    ALREADY_CANCELLED("RECRUITMENT_102", "既にキャンセル済みです", Severity.WARN),

    /** DRAFT のままでは申込不可 */
    DRAFT_NOT_APPLICABLE("RECRUITMENT_103", "下書きのままでは申込できません", Severity.WARN),

    /** COMPLETED 済みの編集は不可 */
    COMPLETED_NOT_EDITABLE("RECRUITMENT_104", "開催完了済みの編集はできません", Severity.WARN),

    /** 既に申込済み */
    ALREADY_APPLIED("RECRUITMENT_105", "既に申込済みです", Severity.WARN),

    /** キャンセル待ち上限超過 */
    WAITLIST_LIMIT_EXCEEDED("RECRUITMENT_106", "キャンセル待ち上限を超過しました", Severity.WARN),

    // ========================================
    // 15.3 バリデーション・整合性エラー (200〜299)
    // ========================================

    /** 定員に達している */
    LISTING_FULL("RECRUITMENT_005", "定員に達しています", Severity.WARN),

    /** 参加形式不一致 */
    PARTICIPATION_TYPE_MISMATCH("RECRUITMENT_007", "参加形式が一致しません", Severity.WARN),

    /** min_capacity > capacity */
    INVALID_CAPACITY("RECRUITMENT_008", "最小定員が定員を超えています", Severity.ERROR),

    /** 予約ライン/募集との時間衝突 */
    LINE_TIME_CONFLICT("RECRUITMENT_009", "予約ラインまたは募集と時間が衝突します", Severity.WARN),

    /** 配信対象 0件で publish 不可 */
    EMPTY_DISTRIBUTION_TARGETS("RECRUITMENT_204", "配信対象が0件のため公開できません", Severity.ERROR),

    /** 画像URLがホワイトリスト外 */
    IMAGE_URL_NOT_WHITELISTED("RECRUITMENT_205", "画像URLが許可リストに含まれていません", Severity.ERROR),

    /** capacity < confirmed_count への変更不可 */
    CAPACITY_BELOW_CONFIRMED("RECRUITMENT_206", "定員を確定参加者数より少なく変更できません", Severity.ERROR),

    /** visibility と distribution_targets の整合性違反 */
    VISIBILITY_TARGETS_INCONSISTENT("RECRUITMENT_207", "公開範囲と配信対象の組合せが不正です", Severity.ERROR),

    /** 短時間の申込多すぎ（レート制限） */
    APPLY_RATE_LIMIT_EXCEEDED("RECRUITMENT_208", "短時間に多くの申込を行いました。しばらく経ってから再試行してください", Severity.WARN),

    // ========================================
    // 15.4 ペナルティ・キャンセル料エラー (300〜399)
    // ========================================

    /** キャンセル料の決済失敗 */
    CANCELLATION_PAYMENT_FAILED("RECRUITMENT_301", "キャンセル料の決済に失敗しました", Severity.ERROR),

    /** キャンセルポリシー設定が不正 */
    INVALID_CANCELLATION_POLICY("RECRUITMENT_302", "キャンセルポリシーの設定が不正です", Severity.ERROR),

    /** キャンセルポリシーの段階が4を超える */
    TIER_LIMIT_EXCEEDED("RECRUITMENT_303", "キャンセルポリシーの段階が4を超えています", Severity.ERROR),

    /** キャンセル料の確認モーダル未経由 */
    FEE_NOT_ACKNOWLEDGED("RECRUITMENT_304", "キャンセル料の確認が必要です", Severity.WARN),

    /** キャンセルポリシー段階の時間範囲が重複 */
    TIER_RANGE_OVERLAP("RECRUITMENT_307", "キャンセルポリシー段階の時間範囲が重複しています", Severity.ERROR),

    /** 表示されたキャンセル料と実際の料金が乖離 (§9.10 409 用、新設) */
    CANCELLATION_FEE_MISMATCH("RECRUITMENT_308", "表示されたキャンセル料と実際の料金が異なります。再試算してください", Severity.WARN),

    // ========================================
    // Phase 5b: NO_SHOW・ペナルティ系 (305, 309〜312)
    // ========================================

    /** NO_SHOW 異議申立の期限超過 */
    NO_SHOW_DISPUTE_DEADLINE_EXCEEDED("RECRUITMENT_305", "異議申立の期限を過ぎています", Severity.WARN),

    /** NO_SHOW 記録が見つからない */
    NO_SHOW_RECORD_NOT_FOUND("RECRUITMENT_309", "NO_SHOW記録が見つかりません", Severity.WARN),

    /** ペナルティが見つからない */
    PENALTY_NOT_FOUND("RECRUITMENT_310", "ペナルティが見つかりません", Severity.WARN),

    /** 既に異議申立済み */
    ALREADY_DISPUTED("RECRUITMENT_311", "既に異議申立済みです", Severity.WARN),

    /** ペナルティ設定が見つからない */
    PENALTY_SETTING_NOT_FOUND("RECRUITMENT_312", "ペナルティ設定が見つかりません", Severity.WARN),

    // ========================================
    // Phase 3: テンプレート系 (313〜)
    // ========================================

    /** テンプレートが見つからない */
    TEMPLATE_NOT_FOUND("RECRUITMENT_313", "テンプレートが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

package com.mannschaft.app.advertising;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 広告機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum AdvertisingErrorCode implements ErrorCode {

    /** アフィリエイト設定が見つからない */
    AD_001("AD_001", "指定されたアフィリエイト設定が見つかりません", Severity.WARN),

    /** 無効なプロバイダー */
    AD_002("AD_002", "無効なプロバイダーが指定されました", Severity.WARN),

    /** 無効な配置場所 */
    AD_003("AD_003", "無効な配置場所が指定されました", Severity.WARN),

    /** 有効期間の不整合 */
    AD_004("AD_004", "有効開始日時は有効終了日時より前に設定してください", Severity.WARN),

    /** 広告主アカウントが見つからない */
    AD_005("AD_005", "広告主アカウントが見つかりません", Severity.WARN),

    /** 組織が既に広告主として登録済み */
    AD_006("AD_006", "この組織は既に広告主として登録されています", Severity.WARN),

    /** ステータス不適合 */
    AD_007("AD_007", "アカウントのステータスが操作に適合しません", Severity.WARN),

    /** 料金テーブルが見つからない */
    AD_008("AD_008", "料金テーブルが見つかりません", Severity.WARN),

    /** 過去の料金テーブルは削除不可 */
    AD_009("AD_009", "過去の料金テーブルは削除できません", Severity.WARN),

    /** 停止アカウントの更新不可 */
    AD_010("AD_010", "アカウントが停止されているため更新できません", Severity.WARN),

    /** 条件に一致する料金が見つからない */
    AD_011("AD_011", "指定された条件に一致する料金が見つかりません", Severity.WARN),

    /** フィールド未指定 */
    AD_012("AD_012", "少なくとも1つのフィールドを指定してください", Severity.WARN),

    /** 請求書が見つからない */
    AD_013("AD_013", "請求書が見つかりません", Severity.WARN),

    /** 請求書のステータスが操作に適合しない */
    AD_014("AD_014", "請求書のステータスが操作に適合しません", Severity.WARN),

    /** レポートスケジュール上限超過 */
    AD_015("AD_015", "レポートスケジュールは最大3件までです", Severity.WARN),

    /** レポートスケジュールが見つからない */
    AD_016("AD_016", "レポートスケジュールが見つかりません", Severity.WARN),

    /** 増額申請が見つからない */
    AD_017("AD_017", "増額申請が見つかりません", Severity.WARN),

    /** 処理中の増額申請が既に存在 */
    AD_018("AD_018", "処理中の増額申請が既にあります", Severity.WARN),

    /** 増額申請のステータスが操作に適合しない */
    AD_019("AD_019", "増額申請のステータスが操作に適合しません", Severity.WARN),

    /** 希望額が現在の限度額以下 */
    AD_020("AD_020", "希望額は現在の限度額より大きい値を指定してください", Severity.WARN),

    /** キャンペーンが見つからない */
    AD_021("AD_021", "指定されたキャンペーンが見つかりません", Severity.WARN),

    /** コンバージョンが見つからない */
    AD_022("AD_022", "指定されたコンバージョンが見つかりません", Severity.WARN),

    /** コンバージョン期間が不正 */
    AD_023("AD_023", "コンバージョン取得期間の指定が不正です", Severity.WARN),

    /** クリエイティブが見つからない */
    AD_024("AD_024", "指定された広告クリエイティブが見つかりません", Severity.WARN),

    /** 削除済みクリエイティブは更新不可 */
    AD_025("AD_025", "削除済みの広告クリエイティブは更新できません", Severity.WARN),

    /** キャンペーンとクリエイティブの不一致 */
    AD_026("AD_026", "指定されたキャンペーンに属するクリエイティブが見つかりません", Severity.WARN),

    // ─── F09.19 運用型キャンペーン CRUD（§15。仮採番 — マージ時に origin/main の最大値を再確認して確定） ───

    /** 状態遷移違反・編集不可状態・編集不可フィールドの変更（HTTP 409） */
    AD_027("AD_027", "キャンペーンの状態がこの操作に適合しません", Severity.WARN),

    /** 日予算が料金カードの最低日予算未満（HTTP 400） */
    AD_028("AD_028", "日予算が料金カードの最低日予算を下回っています", Severity.WARN),

    /** visit / click の IP レート制限（HTTP 429） */
    AD_029("AD_029", "リクエストが集中しています。しばらくしてからお試しください", Severity.WARN),

    /** startDate / endDate 検証違反（HTTP 400） */
    AD_030("AD_030", "掲載期間の指定が不正です", Severity.WARN),

    /** rateCardId 不一致・期間外（HTTP 400） */
    AD_031("AD_031", "適用可能な料金カードが見つかりません", Severity.WARN),

    /** 通報対象の XOR 違反（F09.19.9。HTTP 400） */
    AD_032("AD_032", "通報対象の指定が不正です", Severity.WARN),

    /** 通報自動停止中の resume 拒否（F09.19.9。HTTP 403） */
    AD_033("AD_033", "通報により停止中のため、この操作は実行できません", Severity.WARN),

    /** 運用型キャンペーンから参照中の料金カード削除拒否（HTTP 409。FK violation 500 回帰防御） */
    AD_034("AD_034", "この料金カードは運用型キャンペーンから参照されているため削除できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

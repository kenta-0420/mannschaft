package com.mannschaft.app.membership;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F02.1 QR会員証機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum MembershipErrorCode implements ErrorCode {

    /** 会員証が見つからない */
    MEMBERSHIP_001("MEMBERSHIP_001", "会員証が見つかりません", Severity.WARN),

    /** 他人の会員証へのアクセス */
    MEMBERSHIP_002("MEMBERSHIP_002", "この会員証へのアクセス権限がありません", Severity.WARN),

    /** 会員証がACTIVEでない（QR取得・再生成時） */
    MEMBERSHIP_003("MEMBERSHIP_003", "この会員証は現在利用できません", Severity.WARN),

    /** SUPPORTERによるQR再生成 */
    MEMBERSHIP_004("MEMBERSHIP_004", "QRコードの再生成権限がありません", Severity.WARN),

    /** QRトークンの有効期限切れ */
    MEMBERSHIP_005("MEMBERSHIP_005", "QRコードの有効期限が切れています。会員証画面を再表示してください", Severity.WARN),

    /** QR署名不正 */
    MEMBERSHIP_006("MEMBERSHIP_006", "QRコードの署名が不正です", Severity.WARN),

    /** card_codeに該当する会員証がない */
    MEMBERSHIP_007("MEMBERSHIP_007", "QRコードに対応する会員証が見つかりません", Severity.WARN),

    /** 会員証が一時停止中 */
    MEMBERSHIP_008("MEMBERSHIP_008", "この会員証は一時停止中です", Severity.WARN),

    /** 会員証が無効化済み */
    MEMBERSHIP_009("MEMBERSHIP_009", "この会員証は無効化されています", Severity.WARN),

    /** 二重スキャン防止（5分以内の再スキャン） */
    MEMBERSHIP_010("MEMBERSHIP_010", "チェックイン済みです。しばらくお待ちください", Severity.WARN),

    /** スキャン権限なし（ADMIN+でない） */
    MEMBERSHIP_011("MEMBERSHIP_011", "スキャン認証の権限がありません", Severity.WARN),

    /** スコープ不一致（他チームの会員証をスキャン） */
    MEMBERSHIP_012("MEMBERSHIP_012", "この会員証のスコープに対する権限がありません", Severity.WARN),

    /** 拠点QRの署名不正またはコード不存在 */
    MEMBERSHIP_013("MEMBERSHIP_013", "拠点QRコードが無効です", Severity.WARN),

    /** 拠点が無効化されている */
    MEMBERSHIP_014("MEMBERSHIP_014", "この拠点は現在利用できません", Severity.WARN),

    /** セルフチェックイン時にスコープ未所属 */
    MEMBERSHIP_015("MEMBERSHIP_015", "この施設のメンバーではありません", Severity.WARN),

    /** 会員証が既にSUSPENDED */
    MEMBERSHIP_016("MEMBERSHIP_016", "この会員証は既に一時停止されています", Severity.WARN),

    /** REVOKED の会員証を再有効化しようとした */
    MEMBERSHIP_017("MEMBERSHIP_017", "無効化された会員証は再有効化できません。再加入が必要です", Severity.WARN),

    /** SUSPENDEDでない会員証を再有効化しようとした */
    MEMBERSHIP_018("MEMBERSHIP_018", "一時停止中の会員証のみ再有効化できます", Severity.WARN),

    /** 拠点が見つからない */
    MEMBERSHIP_019("MEMBERSHIP_019", "拠点が見つかりません", Severity.WARN),

    /** 拠点数が上限に到達 */
    MEMBERSHIP_020("MEMBERSHIP_020", "拠点数が上限（20件）に達しています", Severity.WARN),

    /** QRトークンのフォーマット不正 */
    MEMBERSHIP_021("MEMBERSHIP_021", "QRトークンのフォーマットが不正です", Severity.WARN),

    /** 統計の期間が90日超過 */
    MEMBERSHIP_022("MEMBERSHIP_022", "集計期間は最大90日間です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

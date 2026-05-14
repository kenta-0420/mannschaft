package com.mannschaft.app.village;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F17.1 村機能のエラーコード定義（設計書 §10 準拠）。
 *
 * <p>B2（村 CRUD）/ B3（メンバーシップ）/ B4（ニックネーム）/ B5（村作成申請）の
 * 各足軽が必要とするコードを 1 つの enum に集約する。番号は設計書
 * {@code docs/features/F17.1_village_community.md} §10 に従い VILLAGE_001〜030 を割り当て、
 * 設計書未定義の追加コードは VILLAGE_031〜050 の空き番号に割り振る。</p>
 *
 * <p>HttpStatus マッピングは {@link com.mannschaft.app.common.GlobalExceptionHandler}
 * の {@code ERROR_CODE_STATUS_MAP} で個別指定する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum VillageErrorCode implements ErrorCode {

    // ==================================================================
    // 設計書 §10 採番（VILLAGE_001〜030）
    // ==================================================================

    /** VILLAGE_001: 村が存在しない / 削除 / 凍結済み（404、IDOR 対策で統一） */
    VILLAGE_NOT_FOUND("VILLAGE_001", "村が見つかりません", Severity.WARN),

    /** VILLAGE_002: UNLISTED 村に非村人がアクセス（403） */
    VILLAGE_UNLISTED("VILLAGE_002", "この村は非公開です", Severity.WARN),

    /** VILLAGE_003: 村名重複（400） */
    VILLAGE_NAME_TAKEN("VILLAGE_003", "その村名はすでに使われています", Severity.WARN),

    /** VILLAGE_004: スラッグ形式不正（400） */
    VILLAGE_SLUG_INVALID("VILLAGE_004", "スラッグの形式が不正です（3〜40文字の英小文字・数字・ハイフン）", Severity.WARN),

    /** VILLAGE_005: スラッグ重複（400） */
    VILLAGE_SLUG_TAKEN("VILLAGE_005", "そのスラッグはすでに使われています", Severity.WARN),

    /** VILLAGE_006: すでに村人（409） */
    ALREADY_MEMBER("VILLAGE_006", "すでに村人です", Severity.WARN),

    /** VILLAGE_007: 村人ではない（409） */
    NOT_MEMBER("VILLAGE_007", "この村のメンバーではありません", Severity.WARN),

    /** VILLAGE_008: ニックネーム重複（プラットフォーム全体で先着優先・409） */
    NICKNAME_TAKEN("VILLAGE_008", "そのニックネームはすでに使われています", Severity.WARN),

    /** VILLAGE_010: 村作成申請レート超過（429） */
    CREATION_REQUEST_THROTTLED("VILLAGE_010", "村作成申請のレート上限に達しました（1日3件・保有10件まで）", Severity.WARN),

    /** VILLAGE_011: ニックネーム変更レート超過（429） */
    NICKNAME_CHANGE_THROTTLED("VILLAGE_011", "ニックネーム変更は月3回までです", Severity.WARN),

    /** VILLAGE_012: 参加村数ハード上限（429） */
    PARTICIPATION_LIMIT_EXCEEDED("VILLAGE_012", "参加可能な村数の上限（100）を超えました", Severity.WARN),

    /** VILLAGE_014: ガイドライン未同意 / 同意期限切れ（400） */
    GUIDELINE_NOT_AGREED("VILLAGE_014", "村ガイドラインへの同意が必要です（直近1時間以内）", Severity.WARN),

    /** VILLAGE_015: チーム/組織代表権限なし（403） */
    REPRESENT_FORBIDDEN("VILLAGE_015", "この主体として参加する権限がありません", Severity.WARN),

    /** VILLAGE_016: 指定主体が村人でない（403） */
    SUBJECT_NOT_MEMBER("VILLAGE_016", "指定された主体は村人ではありません", Severity.WARN),

    /** VILLAGE_017: 村長は後継未指名で退村不可（409） */
    HEADMAN_CANNOT_LEAVE("VILLAGE_017", "村長は後継を指名するまで退村できません", Severity.WARN),

    /** VILLAGE_018: 楽観ロック競合（409） */
    VERSION_CONFLICT("VILLAGE_018", "他のユーザーが情報を更新しました。最新の内容を確認して再度お試しください", Severity.WARN),

    /** VILLAGE_019: APPROVAL 村に直接参加しようとした（409） */
    VILLAGE_JOIN_REQUIRES_APPROVAL("VILLAGE_019", "この村は承認が必要です。参加申請をご利用ください", Severity.WARN),

    /** VILLAGE_022: 新規アカウント（7日以内）が制限操作を行おうとした（403） */
    NEW_ACCOUNT_RESTRICTED("VILLAGE_022", "新規アカウントはこの操作を行えません", Severity.WARN),

    /** VILLAGE_024: モデレーション権限なし（403） — 村長/長老でないユーザーが BAN や役職変更を試みた */
    MODERATION_FORBIDDEN("VILLAGE_024", "この操作を行う権限がありません", Severity.WARN),

    /** VILLAGE_025: 参加/退出のフラッピング検出（409） */
    JOIN_RATE_EXCEEDED("VILLAGE_025", "短時間に参加と退出を繰り返しています。しばらく時間をおいてからお試しください", Severity.WARN),

    /** VILLAGE_027: 凍結済み村への変更操作（409） */
    VILLAGE_ALREADY_ARCHIVED("VILLAGE_027", "この村は凍結されています", Severity.WARN),

    /** VILLAGE_028: ニックネーム長違反 / NG ワード / 使用文字違反（422） */
    NICKNAME_INVALID("VILLAGE_028", "ニックネームが無効です（長さ・禁止語・使用文字を確認してください）", Severity.WARN),

    /** VILLAGE_029: 村説明文・名称・カテゴリ等の入力値不正（400） */
    VILLAGE_FIELD_INVALID("VILLAGE_029", "入力値が不正です", Severity.WARN),

    // ==================================================================
    // 設計書 §10 未定義の追加コード（VILLAGE_031〜050 の空きに割当）
    // ==================================================================

    /** VILLAGE_031: BAN されているメンバーの操作（403） */
    MEMBER_BANNED("VILLAGE_031", "この村から BAN されています", Severity.WARN),

    /** VILLAGE_032: 村作成申請が存在しない（404） */
    CREATION_REQUEST_NOT_FOUND("VILLAGE_032", "村作成申請が見つかりません", Severity.WARN),

    /** VILLAGE_033: 既に審査済みの申請への再操作（409） */
    CREATION_REQUEST_ALREADY_REVIEWED("VILLAGE_033", "この村作成申請は既に審査済みです", Severity.WARN),

    /** VILLAGE_034: 拒否済み申請への操作（403） */
    CREATION_REQUEST_REJECTED("VILLAGE_034", "この村作成申請は拒否済みです", Severity.WARN),

    /** VILLAGE_035: 申請時の slug が既存村と衝突（409） — VILLAGE_005 と区別し申請ライフサイクル専用 */
    CREATION_REQUEST_SLUG_TAKEN("VILLAGE_035", "指定された slug は既に使用されています", Severity.WARN),

    /** VILLAGE_036: 一般ユーザーが OFFICIAL 村を申請しようとした（403） */
    OFFICIAL_VILLAGE_FORBIDDEN("VILLAGE_036", "一般ユーザーは公式村を申請できません", Severity.WARN),

    /** VILLAGE_037: 村作成権限なし（運営権限が必要） — B2 が独自に VILLAGE_028 で定義していたものを移設 */
    VILLAGE_CREATE_FORBIDDEN("VILLAGE_037", "公式村の作成には運営権限が必要です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

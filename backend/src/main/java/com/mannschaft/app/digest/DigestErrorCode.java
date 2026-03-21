package com.mannschaft.app.digest;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F06.3 タイムラインダイジェストのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum DigestErrorCode implements ErrorCode {

    /** 期間不正（start > end / 31日超過） */
    DIGEST_001("DIGEST_001", "指定された期間が不正です", Severity.WARN),

    /** custom_prompt_suffix が500文字超過 */
    DIGEST_002("DIGEST_002", "カスタムプロンプトは500文字以内で指定してください", Severity.WARN),

    /** 権限不足 */
    DIGEST_003("DIGEST_003", "この操作を行う権限がありません", Severity.WARN),

    /** スコープへのアクセス権なし */
    DIGEST_004("DIGEST_004", "指定されたスコープへのアクセス権がありません", Severity.WARN),

    /** F06.3 モジュールが無効 */
    DIGEST_005("DIGEST_005", "タイムラインダイジェスト機能が有効化されていません", Severity.WARN),

    /** AI スタイル指定時に FEATURE_DIGEST_AI が無効 */
    DIGEST_006("DIGEST_006", "AI ダイジェスト機能は現在利用できません。TEMPLATE スタイルをご利用ください", Severity.WARN),

    /** 同一期間のダイジェストが既に存在 */
    DIGEST_007("DIGEST_007", "同一期間のダイジェストが既に存在します", Severity.WARN),

    /** AI スタイルの月次生成上限に到達 */
    DIGEST_008("DIGEST_008", "今月の AI 生成枠を使い切りました。TEMPLATE スタイルは引き続き利用可能です", Severity.WARN),

    /** 同一スコープで GENERATING 中のダイジェストが存在 */
    DIGEST_009("DIGEST_009", "前回の生成が処理中です。完了後に再度お試しください", Severity.WARN),

    /** 対象期間の投稿が min_posts_threshold 未満 */
    DIGEST_010("DIGEST_010", "対象期間の投稿数が最低件数に達していません", Severity.WARN),

    /** ダイジェストが存在しない */
    DIGEST_011("DIGEST_011", "指定されたダイジェストが見つかりません", Severity.WARN),

    /** ステータスが GENERATED でない（publish / discard 時） */
    DIGEST_012("DIGEST_012", "この操作は GENERATED 状態のダイジェストのみ可能です", Severity.WARN),

    /** ステータスが GENERATED / FAILED でない（regenerate 時） */
    DIGEST_013("DIGEST_013", "再生成は GENERATED または FAILED 状態のダイジェストのみ可能です", Severity.WARN),

    /** 設定が存在しない */
    DIGEST_014("DIGEST_014", "ダイジェスト設定が見つかりません", Severity.WARN),

    /** 同一スコープに有効な設定が既に存在（新規作成時） */
    DIGEST_015("DIGEST_015", "同一スコープに有効な設定が既に存在します", Severity.WARN),

    /** schedule_day_of_week が範囲外 */
    DIGEST_016("DIGEST_016", "曜日は0（日曜）〜6（土曜）の範囲で指定してください", Severity.WARN),

    /** バリデーションエラー（汎用） */
    DIGEST_017("DIGEST_017", "入力値が不正です", Severity.WARN),

    /** generated_title が200文字超過 */
    DIGEST_018("DIGEST_018", "タイトルは200文字以内で指定してください", Severity.WARN),

    /** generated_excerpt が500文字超過 */
    DIGEST_019("DIGEST_019", "抜粋は500文字以内で指定してください", Severity.WARN),

    /** AI 生成失敗 */
    DIGEST_020("DIGEST_020", "AI によるダイジェスト生成に失敗しました", Severity.ERROR),

    /** ADMIN でない */
    DIGEST_021("DIGEST_021", "この操作は ADMIN のみ実行可能です", Severity.WARN),

    /** timezone が無効な IANA 形式 */
    DIGEST_022("DIGEST_022", "タイムゾーンが無効です。IANA 形式で指定してください", Severity.WARN),

    /** MANUAL 以外で schedule_time が NULL */
    DIGEST_023("DIGEST_023", "スケジュール実行時刻は必須です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

package com.mannschaft.app.knowledgebase;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ナレッジベース機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum KnowledgeBaseErrorCode implements ErrorCode {

    /** ページが見つからない */
    KB_001("KB_001", "ページが見つかりません", Severity.WARN),

    /** アクセス権限がない（明確な認可拒否 → 403 を {@link com.mannschaft.app.common.GlobalExceptionHandler} で明示登録） */
    KB_002("KB_002", "アクセス権限がありません", Severity.WARN),

    /** スラッグ重複（状態競合 → 409 を {@link com.mannschaft.app.common.GlobalExceptionHandler} で明示登録） */
    KB_003("KB_003", "スラッグが既に使用されています", Severity.WARN),

    /** 階層深さ上限超過 */
    KB_004("KB_004", "階層の深さが上限（10）を超えます", Severity.WARN),

    /** 循環参照検出 */
    KB_005("KB_005", "循環参照が検出されました（ページ移動時）", Severity.WARN),

    /** バージョン不一致（楽観排他制御の競合 → 409 を {@link com.mannschaft.app.common.GlobalExceptionHandler} で明示登録） */
    KB_006("KB_006", "バージョンが一致しません", Severity.WARN),

    /** リビジョンが見つからない */
    KB_007("KB_007", "リビジョンが見つかりません", Severity.WARN),

    /** お気に入り上限超過 */
    KB_008("KB_008", "お気に入りの上限（50）に達しました", Severity.WARN),

    /** ピン留め上限超過 */
    KB_009("KB_009", "ピン留めの上限（10）に達しました", Severity.WARN),

    /** テンプレートが見つからない */
    KB_010("KB_010", "テンプレートが見つかりません", Severity.WARN),

    /** システムテンプレート変更不可（書込禁止リソースへの操作拒否 → 403 を {@link com.mannschaft.app.common.GlobalExceptionHandler} で明示登録） */
    KB_011("KB_011", "システムテンプレートは変更できません", Severity.WARN),

    /** テンプレート上限超過 */
    KB_012("KB_012", "テンプレートの上限（20）に達しました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

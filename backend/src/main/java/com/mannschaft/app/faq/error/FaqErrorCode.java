package com.mannschaft.app.faq.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F21.1 §5.5: FAQ駆動GEO 機能のエラーコード定義。
 *
 * <p>設計書: docs/features/F21.1_geo_optimization.md §5.5</p>
 *
 * <p>HTTP ステータスへのマッピングは
 * {@link com.mannschaft.app.common.GlobalExceptionHandler#ERROR_CODE_STATUS_MAP} で行う。
 * バリデーション系（FAQ_001〜FAQ_005）は {@link Severity#WARN} 既定の 400 にマップされ、
 * 対象不在（FAQ_010）は IDOR 対策で 404 にマップする。</p>
 */
@Getter
@RequiredArgsConstructor
public enum FaqErrorCode implements ErrorCode {

    /** 自由質問の登録件数が上限（7件）を超えています (400)。 */
    FAQ_001("FAQ_001",
            "自由質問は最大7件までです",
            Severity.WARN),

    /** 不正な固定質問キーが指定されました (400)。 */
    FAQ_002("FAQ_002",
            "不正な固定質問キーが指定されました",
            Severity.WARN),

    /** 固定質問キーが重複しています (400)。 */
    FAQ_003("FAQ_003",
            "固定質問キーが重複しています",
            Severity.WARN),

    /** 自由質問の質問文が空です (400)。 */
    FAQ_004("FAQ_004",
            "自由質問の質問文は必須です",
            Severity.WARN),

    /** 入力値が長さ上限を超えています (400)。 */
    FAQ_005("FAQ_005",
            "入力値が長さ上限を超えています",
            Severity.WARN),

    /** 指定された FAQ 対象（チーム / 組織）が存在しません (404 へ正規化、IDOR 対策)。 */
    FAQ_010("FAQ_010",
            "指定されたチーム / 組織が見つかりません",
            Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

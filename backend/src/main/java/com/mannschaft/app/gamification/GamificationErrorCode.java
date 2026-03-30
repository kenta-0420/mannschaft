package com.mannschaft.app.gamification;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ゲーミフィケーション機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum GamificationErrorCode implements ErrorCode {

    /** 設定が見つからない */
    GAMIFICATION_001("GAMIFICATION_001", "ゲーミフィケーション設定が見つかりません", Severity.WARN),

    /** ポイントルールが見つからない */
    GAMIFICATION_002("GAMIFICATION_002", "ポイントルールが見つかりません", Severity.WARN),

    /** バッジが見つからない */
    GAMIFICATION_003("GAMIFICATION_003", "バッジが見つかりません", Severity.WARN),

    /** システムルールは変更できない */
    GAMIFICATION_004("GAMIFICATION_004", "システムルールは変更できません", Severity.WARN),

    /** ゲーミフィケーションが無効 */
    GAMIFICATION_005("GAMIFICATION_005", "ゲーミフィケーションが無効です", Severity.WARN),

    /** バージョン不一致 */
    GAMIFICATION_006("GAMIFICATION_006", "バージョンが一致しません", Severity.WARN),

    /** カスタムルールの上限到達 */
    GAMIFICATION_007("GAMIFICATION_007", "カスタムルールの上限に達しました", Severity.WARN),

    /** アクセス権限なし */
    GAMIFICATION_008("GAMIFICATION_008", "アクセス権限がありません", Severity.WARN),

    /** daily_limit到達 */
    GAMIFICATION_009("GAMIFICATION_009", "本日のポイント付与上限に達しました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

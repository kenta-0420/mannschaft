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

    /** 設定が見つからない（404） */
    GAMIFICATION_001("GAMIFICATION_001", "ゲーミフィケーション設定が見つかりません", Severity.WARN),

    /** ポイントルールが見つからない（404） */
    GAMIFICATION_002("GAMIFICATION_002", "ポイントルールが見つかりません", Severity.WARN),

    /** バッジが見つからない（404） */
    GAMIFICATION_003("GAMIFICATION_003", "バッジが見つかりません", Severity.WARN),

    /** システムルールは変更できない（存在は隠さず権限拒否のため403） */
    GAMIFICATION_004("GAMIFICATION_004", "システムルールは変更できません", Severity.WARN),

    /** ゲーミフィケーションが無効（throw元なし・未使用） */
    GAMIFICATION_005("GAMIFICATION_005", "ゲーミフィケーションが無効です", Severity.WARN),

    /** バージョン不一致（楽観的ロック・状態競合のため409） */
    GAMIFICATION_006("GAMIFICATION_006", "バージョンが一致しません", Severity.WARN),

    /** カスタムルールの上限到達（実装は重複ルールの存在チェックであり文言と実挙動が食い違うため見送り） */
    GAMIFICATION_007("GAMIFICATION_007", "カスタムルールの上限に達しました", Severity.WARN),

    /** アクセス権限なし（実体はスコープ不一致・越境の存在秘匿のため404） */
    GAMIFICATION_008("GAMIFICATION_008", "アクセス権限がありません", Severity.WARN),

    /** daily_limit到達（throw元なし・未使用） */
    GAMIFICATION_009("GAMIFICATION_009", "本日のポイント付与上限に達しました", Severity.WARN),

    /** 管理者調整の1日上限超過 */
    GAMIFICATION_010("GAMIFICATION_010", "1日の管理者ポイント調整上限に達しました", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

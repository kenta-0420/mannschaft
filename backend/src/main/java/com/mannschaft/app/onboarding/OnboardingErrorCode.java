package com.mannschaft.app.onboarding;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * オンボーディング機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum OnboardingErrorCode implements ErrorCode {

    /** テンプレートが見つからない */
    ONBOARDING_001("ONBOARDING_001", "指定されたオンボーディングテンプレートが見つかりません", Severity.WARN),

    /** ステップが見つからない */
    ONBOARDING_002("ONBOARDING_002", "指定されたオンボーディングステップが見つかりません", Severity.WARN),

    /** 進捗が見つからない */
    ONBOARDING_003("ONBOARDING_003", "指定されたオンボーディング進捗が見つかりません", Severity.WARN),

    /** テンプレートはDRAFT状態でない */
    ONBOARDING_004("ONBOARDING_004", "テンプレートはDRAFT状態でないため操作できません", Severity.WARN),

    /** テンプレートはACTIVE状態でない */
    ONBOARDING_005("ONBOARDING_005", "テンプレートはACTIVE状態でないため操作できません", Severity.WARN),

    /** ステップが0件 */
    ONBOARDING_006("ONBOARDING_006", "テンプレートにステップが1つも登録されていません", Severity.WARN),

    /** テンプレート数上限超過 */
    ONBOARDING_007("ONBOARDING_007", "オンボーディングテンプレート数が上限を超えています", Severity.WARN),

    /** 進捗はIN_PROGRESS状態でない */
    ONBOARDING_008("ONBOARDING_008", "進捗はIN_PROGRESS状態でないため操作できません", Severity.WARN),

    /** ステップは既に完了済み */
    ONBOARDING_009("ONBOARDING_009", "このステップは既に完了済みです", Severity.WARN),

    /** 前のステップが未完了 */
    ONBOARDING_010("ONBOARDING_010", "順序が強制されているため、前のステップを先に完了してください", Severity.WARN),

    /** 手動完了不可のステップ種別 */
    ONBOARDING_011("ONBOARDING_011", "このステップ種別は手動で完了できません", Severity.WARN),

    /** プリセットが見つからない */
    ONBOARDING_012("ONBOARDING_012", "指定されたプリセットが見つかりません", Severity.WARN),

    /** リマインダー送信上限超過 */
    ONBOARDING_013("ONBOARDING_013", "リマインダーの送信上限を超えています", Severity.WARN),

    /** ACTIVEテンプレートが存在しない */
    ONBOARDING_014("ONBOARDING_014", "有効なオンボーディングテンプレートが存在しません", Severity.WARN),

    /** 進行中の進捗がありテンプレート削除不可 */
    ONBOARDING_015("ONBOARDING_015", "進行中の進捗があるためテンプレートを削除できません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

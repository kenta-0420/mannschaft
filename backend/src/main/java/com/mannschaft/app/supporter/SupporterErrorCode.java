package com.mannschaft.app.supporter;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.5 サポーター申請・管理機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum SupporterErrorCode implements ErrorCode {

    /** 既にサポーター申請済みです */
    SUPPORTER_001("SUPPORTER_001", "既にサポーター申請済みです", Severity.WARN),

    /** 既にサポーターとして登録されています */
    SUPPORTER_002("SUPPORTER_002", "既にサポーターとして登録されています", Severity.WARN),

    /** 申請が見つかりません */
    SUPPORTER_003("SUPPORTER_003", "申請が見つかりません", Severity.WARN),

    /** この申請はすでに処理済みです */
    SUPPORTER_004("SUPPORTER_004", "この申請はすでに処理済みです", Severity.WARN),

    /** ブロックされているため申請できません */
    SUPPORTER_005("SUPPORTER_005", "ブロックされているため申請できません", Severity.WARN),

    /** サポーター機能が無効なチーム・組織です */
    SUPPORTER_006("SUPPORTER_006", "サポーター機能が有効ではありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

package com.mannschaft.app.organization;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.2 組織管理機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum OrgErrorCode implements ErrorCode {

    /** 組織が見つかりません */
    ORG_001("ORG_001", "組織が見つかりません", Severity.WARN),

    /** 組織名は既に使用されています */
    ORG_002("ORG_002", "組織名は既に使用されています", Severity.WARN),

    /** 組織はアーカイブ済みです */
    ORG_003("ORG_003", "組織はアーカイブ済みです", Severity.WARN),

    /** 組織の最大階層深度を超えています */
    ORG_004("ORG_004", "組織の最大階層深度を超えています", Severity.WARN),

    /** この操作を行う権限がありません */
    ORG_005("ORG_005", "この操作を行う権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

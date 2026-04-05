package com.mannschaft.app.signage;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * デジタルサイネージ機能のエラーコード。
 */
@Getter
@RequiredArgsConstructor
public enum SignageErrorCode implements ErrorCode {

    /** 画面が見つかりません */
    SIGNAGE_001("SIGNAGE_001", "画面が見つかりません", Severity.WARN),

    /** アクセスが拒否されました（トークン無効/IP制限） */
    SIGNAGE_002("SIGNAGE_002", "アクセスが拒否されました（トークン無効/IP制限）", Severity.WARN),

    /** スロットが見つかりません */
    SIGNAGE_003("SIGNAGE_003", "スロットが見つかりません", Severity.WARN),

    /** スケジュールが見つかりません */
    SIGNAGE_004("SIGNAGE_004", "スケジュールが見つかりません", Severity.WARN),

    /** トークンが見つかりません */
    SIGNAGE_005("SIGNAGE_005", "トークンが見つかりません", Severity.WARN),

    /** バージョンが一致しません */
    SIGNAGE_006("SIGNAGE_006", "バージョンが一致しません", Severity.WARN),

    /** アクティブな緊急メッセージが既に存在します */
    SIGNAGE_007("SIGNAGE_007", "アクティブな緊急メッセージが既に存在します", Severity.WARN),

    /** slot_orderの重複があります */
    SIGNAGE_008("SIGNAGE_008", "slot_orderの重複があります", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

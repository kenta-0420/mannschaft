package com.mannschaft.app.team;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F01.2 チーム管理機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum TeamErrorCode implements ErrorCode {

    /** チームが見つかりません */
    TEAM_001("TEAM_001", "チームが見つかりません", Severity.WARN),

    /** チームはアーカイブ済みです */
    TEAM_002("TEAM_002", "チームはアーカイブ済みです", Severity.WARN),

    /** 既にこのチームに所属しています */
    TEAM_003("TEAM_003", "既にこのチームに所属しています", Severity.WARN),

    /** ブロックされているため参加できません */
    TEAM_004("TEAM_004", "ブロックされているため参加できません", Severity.WARN),

    /** この操作を行う権限がありません */
    TEAM_005("TEAM_005", "この操作を行う権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

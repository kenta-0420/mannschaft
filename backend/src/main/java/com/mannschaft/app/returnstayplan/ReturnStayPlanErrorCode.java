package com.mannschaft.app.returnstayplan;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** F02.11 の API・Service が共有するエラー契約。 */
@Getter
@RequiredArgsConstructor
public enum ReturnStayPlanErrorCode implements ErrorCode {

    NOT_FOUND("RETURN_STAY_PLAN_001", "帰省・滞在予定が見つかりません", Severity.WARN),
    INVALID_REQUEST("RETURN_STAY_PLAN_002", "帰省・滞在予定の入力が不正です", Severity.WARN),
    LIMIT_EXCEEDED("RETURN_STAY_PLAN_003", "帰省・滞在予定の上限を超えています", Severity.WARN),
    INVALID_PAGING("RETURN_STAY_PLAN_004", "ページング指定が不正です", Severity.WARN),
    VERSION_CONFLICT("RETURN_STAY_PLAN_005", "帰省・滞在予定が更新されています", Severity.WARN),
    MEMBERSHIP_CHANGED("RETURN_STAY_PLAN_006", "公開先チームの所属状態が変わりました", Severity.WARN),
    TEAM_ACCESS_DENIED("RETURN_STAY_PLAN_007", "チームが見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

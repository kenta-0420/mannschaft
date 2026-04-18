package com.mannschaft.app.committee.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.10 組織委員会機能のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum CommitteeErrorCode implements ErrorCode {

    /** 委員会が見つからない */
    NOT_FOUND("COMMITTEE_NOT_FOUND", "委員会が見つかりません", Severity.WARN),

    /** 委員会メンバーが見つからない */
    MEMBER_NOT_FOUND("COMMITTEE_MEMBER_NOT_FOUND", "委員会メンバーが見つかりません", Severity.WARN),

    /** 同じ組織内に同名の委員会が既に存在する */
    NAME_DUPLICATE("COMMITTEE_NAME_DUPLICATE", "同じ組織内に同名の委員会が既に存在します", Severity.WARN),

    /** 許可されていない状態遷移 */
    INVALID_STATUS_TRANSITION("COMMITTEE_INVALID_STATUS_TRANSITION", "この状態遷移は許可されていません", Severity.WARN),

    /** 委員会には少なくとも1名のCHAIRが必要 */
    CHAIR_REQUIRED("COMMITTEE_CHAIR_REQUIRED", "委員会には少なくとも1名のCHAIRが必要です", Severity.WARN),

    /** 唯一のCHAIRは後任を設定するまで離脱できない */
    LAST_CHAIR_CANNOT_LEAVE("COMMITTEE_LAST_CHAIR_CANNOT_LEAVE", "唯一のCHAIRは後任を設定するまで離脱できません", Severity.WARN),

    /** 委員会のメンバーではない */
    NOT_MEMBER("COMMITTEE_NOT_MEMBER", "委員会のメンバーではありません", Severity.WARN),

    /** DRAFT状態の委員会は伝達処理できない */
    DRAFT_CANNOT_DISTRIBUTE("COMMITTEE_DRAFT_CANNOT_DISTRIBUTE", "DRAFT 状態の委員会は伝達処理できません", Severity.WARN),

    /** 既に委員会のメンバーである */
    ALREADY_MEMBER("COMMITTEE_ALREADY_MEMBER", "既に委員会のメンバーです", Severity.WARN),

    /** 招集状が見つからない */
    INVITATION_NOT_FOUND("COMMITTEE_INVITATION_NOT_FOUND", "招集状が見つかりません", Severity.WARN),

    /** 招集状は既に解決済み */
    INVITATION_ALREADY_RESOLVED("COMMITTEE_INVITATION_ALREADY_RESOLVED", "招集状は既に解決済みです", Severity.WARN),

    /** 招集状の有効期限が切れている */
    INVITATION_EXPIRED("COMMITTEE_INVITATION_EXPIRED", "招集状の有効期限が切れています", Severity.WARN),

    /** 招集トークンが無効 */
    INVITATION_TOKEN_INVALID("COMMITTEE_INVITATION_TOKEN_INVALID", "招集トークンが無効です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

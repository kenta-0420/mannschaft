package com.mannschaft.app.joinrequest;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 柱③-A「MEMBER 参加申請（join request）」のエラーコード定義（CMP-260901-1538）。
 */
@Getter
@RequiredArgsConstructor
public enum JoinRequestErrorCode implements ErrorCode {

    /**
     * スコープが見つからない。存在しない・PRIVATE・PROVISIONED・アーカイブ済みのいずれも
     * 同一コードへ畳んで返す（存在秘匿。{@code VillageAccessGate} と同じ流儀）。
     */
    SCOPE_NOT_FOUND("JOIN_REQUEST_001", "対象が見つかりません", Severity.WARN),

    /** 既に当該スコープのメンバーである。 */
    ALREADY_MEMBER("JOIN_REQUEST_002", "既にメンバーです", Severity.WARN),

    /** 申請が見つからない（他スコープの申請 ID 指定を含む IDOR 対策で不在と同一コード）。 */
    REQUEST_NOT_FOUND("JOIN_REQUEST_003", "参加申請が見つかりません", Severity.WARN),

    /** PENDING でない申請への審査操作。 */
    ALREADY_REVIEWED("JOIN_REQUEST_004", "この参加申請は既に処理済みです", Severity.WARN),

    /** scopeType が TEAM/ORGANIZATION 以外。 */
    INVALID_SCOPE_TYPE("JOIN_REQUEST_005", "スコープ種別が不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}

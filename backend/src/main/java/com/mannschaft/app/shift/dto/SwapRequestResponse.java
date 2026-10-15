package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * シフト交代リクエストレスポンスDTO。
 */
@Getter
@Builder
public class SwapRequestResponse {

    private final Long id;
    private final Long slotId;
    private final Long requesterId;
    private final Long accepterId;
    private final String status;
    private final String reason;
    private final String adminNote;
    private final Long resolvedBy;
    private final LocalDateTime resolvedAt;
    private final LocalDateTime createdAt;

    /** 受信者モード: SPECIFIC=特定ユーザー指定 / OPEN_CALL=全体公開 */
    private final String recipientMode;

    /**
     * 交代対象ユーザーIDリスト（SPECIFIC モード時）。
     * エンティティの JSON 文字列から変換済みのリスト。
     */
    private final List<Long> targetUserIds;

    /** 手挙げユーザーID（OPEN_CALL モード時に先着1名が手挙げした場合） */
    private final Long claimedBy;

    /** 手挙げ日時 */
    private final LocalDateTime claimedAt;
}

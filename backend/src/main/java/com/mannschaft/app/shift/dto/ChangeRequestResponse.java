package com.mannschaft.app.shift.dto;

import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ChangeRequestType;

import java.time.LocalDateTime;

/**
 * シフト変更依頼レスポンス DTO。
 */
public record ChangeRequestResponse(

        /** 変更依頼ID */
        Long id,

        /** 対象スケジュールID */
        Long scheduleId,

        /** 対象シフト枠ID（NULL=スケジュール全体） */
        Long slotId,

        /** 変更依頼種別 */
        ChangeRequestType requestType,

        /** ステータス */
        ChangeRequestStatus status,

        /** 依頼者ユーザーID */
        Long requestedBy,

        /** 依頼理由 */
        String reason,

        /** 審査者ユーザーID */
        Long reviewerId,

        /** 審査コメント */
        String reviewComment,

        /** 審査日時 */
        LocalDateTime reviewedAt,

        /** 有効期限 */
        LocalDateTime expiresAt,

        /** 作成日時 */
        LocalDateTime createdAt
) {
}

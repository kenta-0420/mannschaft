package com.mannschaft.app.shift.dto;

import com.mannschaft.app.shift.ChangeRequestType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * シフト変更依頼作成リクエスト DTO。
 */
public record CreateChangeRequestRequest(

        /** 対象スケジュールID */
        @NotNull Long scheduleId,

        /** 対象シフト枠ID（NULL=スケジュール全体への依頼） */
        Long slotId,

        /** 変更依頼種別 */
        @NotNull ChangeRequestType requestType,

        /** 依頼理由 */
        @Size(max = 1000) String reason
) {
}

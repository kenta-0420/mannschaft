package com.mannschaft.app.school.dto;

import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureMode;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureRecipients;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 出席要件評価結果の開示リクエストDTO（F03.13 Phase 15）。 */
public record DisclosureRequest(

        /** 開示モード */
        @NotNull
        DisclosureMode mode,

        /** 通知先 */
        @NotNull
        DisclosureRecipients recipients,

        /** 担任メッセージ（任意） */
        @Size(max = 1000)
        String message
) {
}

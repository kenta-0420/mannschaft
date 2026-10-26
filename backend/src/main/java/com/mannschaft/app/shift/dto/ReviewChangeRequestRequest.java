package com.mannschaft.app.shift.dto;

import com.mannschaft.app.shift.ChangeRequestStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * シフト変更依頼審査リクエスト DTO。
 */
public record ReviewChangeRequestRequest(

        /** 審査結果（ACCEPTED または REJECTED） */
        @NotNull ChangeRequestStatus decision,

        /** 審査コメント */
        @Size(max = 500) String reviewComment,

        /** 楽観ロック用バージョン */
        @NotNull Integer version
) {
}

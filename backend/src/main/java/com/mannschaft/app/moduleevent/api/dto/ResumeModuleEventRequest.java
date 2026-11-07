package com.mannschaft.app.moduleevent.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 再開理由だけを受け付ける。操作者は認証情報から取得する。 */
public record ResumeModuleEventRequest(@NotBlank @Size(max = 500) String reason) {

    /** 文字数制約は入力の前後空白を除いた理由へ適用する。 */
    public ResumeModuleEventRequest {
        reason = reason == null ? null : reason.trim();
    }
}

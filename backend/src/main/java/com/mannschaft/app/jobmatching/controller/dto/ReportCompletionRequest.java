package com.mannschaft.app.jobmatching.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 業務完了報告リクエスト。Worker が Requester に伝えるコメント（任意）を保持する。
 */
public record ReportCompletionRequest(
        @Size(max = 1000) String message
) {
}

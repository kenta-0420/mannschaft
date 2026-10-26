package com.mannschaft.app.jobmatching.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 契約キャンセルリクエスト。キャンセル理由（任意）を保持する。
 */
public record CancelContractRequest(
        @Size(max = 500) String reason
) {
}
